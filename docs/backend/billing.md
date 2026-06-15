# `com.example.cp.billing` — Provider-Neutral Billing, Rating & Invoicing

## Module overview

This package is the control panel's **internal billing engine**. It does *not* talk to Stripe, Paddle, or any external payment processor — that is a deliberate, documented scope decision (ROADMAP gap #73 is out of scope). Instead it:

1. Keeps **one `BillingAccount` per organization**, binding the org to a `(provider, external_customer_id, currency)` triple. The default provider is `ManualBillingProvider`, which never makes a network call.
2. **Rates** a single billing period's metered usage (`usage_quotas` rows) against a plan's *price book* into a set of priced line items (`RatingService`).
3. **Persists** those lines as an `Invoice` (header) + `InvoiceLineItem`s (detail) in `DRAFT` state, and drives the invoice lifecycle `DRAFT → ISSUED → PAID | VOID` (`BillingService`).
4. Exposes the whole thing as an **org-scoped REST API** under `/api/v1/orgs/{orgId}/billing` (`BillingController`).

Two correctness pillars run through the design and are worth keeping in mind while reading every file:

- **Period scoping + duplicate-invoice dedup (P1-10):** an invoice covers exactly one UTC-month period, and at most one *non-VOID* invoice may exist per `(subscription_id, period_start)`. Enforced both in application code *and* by a partial unique index.
- **Optimistic locking (P2):** `Invoice` carries a JPA `@Version` so concurrent `issue`/`void`/regenerate operations can't silently clobber each other; the loser gets a `409 Conflict`, never a corrupted state or double-charge.

The package is small (11 files) but dense; the interesting logic lives in `RatingService` (the rating math) and `BillingService` (the persistence/lifecycle/concurrency orchestration). The rest are JPA entities, Spring Data repositories, the provider seam, and DTOs.

### File map

| File | Kind | Role in one line |
|------|------|------------------|
| `BillingAccount.java` | `@Entity` | One billing account per org; `(provider, external_customer_id, currency)`. |
| `BillingAccountRepository.java` | repo | Lookup/existence by `orgId`. |
| `Invoice.java` | `@Entity` | A rated period for a subscription; carries the `@Version` lock and status enum. |
| `InvoiceRepository.java` | repo | Org-scoped + per-period (dedup) invoice queries. |
| `InvoiceLineItem.java` | `@Entity` | One rated line: `quantity × unitAmount = amount`. |
| `InvoiceLineItemRepository.java` | repo | Lines by `invoiceId`. |
| `BillingProvider.java` | interface | The seam where a real payment provider would plug in. |
| `ManualBillingProvider.java` | `@Component` | Default no-network, bookkeeping-only provider. |
| `RatingService.java` | `@Service` | Pure rating math: usage × price book → line items + total, currency-aware rounding. |
| `BillingService.java` | `@Service` | Persistence + lifecycle + concurrency orchestrator; the only toucher of `BillingProvider`. |
| `BillingController.java` | `@RestController` | Org-scoped REST endpoints + DTOs. |

---

## Data model & flow at a glance

```
Subscription ──┐                        usage_quotas (per period bucket)
   (planId)    │                        (subscription_id, feature_key, period_start)
               ▼                                   │
          PlanService.getFeatures(planId)          │ filter: period_start == requested month
               │  price.*  entries                 ▼
               ▼                          consumedByFeature: Map<featureKey, qty>
        RatingService.loadPriceBook ───────────────┘
               │
               ▼
   RatingService.rate(planId, consumedByFeature, currency)
        → RatedInvoice { lines: [RatedLine...], total }
               │
               ▼
   BillingService.generateDraftInvoice
        → Invoice (DRAFT) + InvoiceLineItem[]     (dedup-guarded)
               │
issueInvoice   ▼                                  voidInvoice
   DRAFT ──────────────► ISSUED ──► (PAID)            ─────► VOID
        provider.recordCharge + finalizeInvoice
```

Status meanings (`Invoice.Status`): `DRAFT` (freshly rated, mutable in spirit), `ISSUED` (finalized/locked, charge recorded with the provider, lines + total frozen), `PAID` (terminal-paid; not produced by any method in this package — reserved for a future payment-confirmation flow), `VOID` (cancelled; frees the `(subscription, period)` dedup slot).

---

## `BillingAccount.java`

**Responsibility.** JPA entity for the `billing_accounts` table. There is exactly **one row per org** (enforced by a `UNIQUE` constraint on `org_id`). It binds an org to whichever provider created it plus that provider's opaque customer id and the ISO-4217 currency the org bills in.

**Public type.** `public class BillingAccount` — Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`. No methods of its own.

**Fields / column mapping** (DDL in `16-billing.sql`; `spring.jpa.hibernate.ddl-auto=validate`, so entity ↔ column must match exactly):

| Field | Column | Notes |
|-------|--------|-------|
| `id` (`UUID`) | `id` PK | App-assigned via `Ids.newId()` — **not** DB-generated despite the DDL default. |
| `orgId` (`UUID`) | `org_id` NOT NULL **UNIQUE** | FK → `organizations(id)` `ON DELETE CASCADE`. The uniqueness is the backstop for the get-or-create race. |
| `provider` (`String`) | `provider` NOT NULL | `BillingProvider.name()` of the creator, e.g. `"manual"`. |
| `externalCustomerId` (`String`, nullable) | `external_customer_id` TEXT | Opaque provider customer id; for the manual provider it's `"manual_cust_<orgId>"`. |
| `currency` (`String`) | `currency` NOT NULL `length=3` | Normalized upper-case ISO-4217 (defaults to `"USD"`). |
| `createdAt` (`OffsetDateTime`) | `created_at` NOT NULL | Set in `BillingService` to `OffsetDateTime.now()`. |

**Collaborators.** Created/read by `BillingService` only; surfaced to clients via `BillingController.BillingAccountDto`.

**Gotchas.**
- `id` is assigned in Java (`Ids.newId()`), so don't rely on the DB `gen_random_uuid()` default — it never fires for inserts that come through JPA with a populated id.
- `currency` here is the **account-level** currency. Invoices copy the account's currency at generation time; changing the account currency later does *not* retroactively re-denominate existing invoices.

---

## `BillingAccountRepository.java`

**Responsibility.** Spring Data JPA repository for `BillingAccount`.

```java
public interface BillingAccountRepository extends JpaRepository<BillingAccount, UUID> {
    Optional<BillingAccount> findByOrgId(UUID orgId);
    boolean existsByOrgId(UUID orgId);
}
```

- `findByOrgId` — primary access path; `Optional` is correct because of the `org_id` UNIQUE constraint.
- `existsByOrgId` — currently unused by `BillingService` (which uses the `findByOrgId(...).orElseGet(...)` get-or-create pattern instead) but kept as a cheap existence probe for callers/tests.

**Gotcha.** `getOrCreateAccount` deliberately does *not* use `existsByOrgId` as a gate — it relies on `findByOrgId` + catch-the-unique-violation, because an `exists`-then-`save` would still race. See `BillingService.getOrCreateAccount`.

---

## `Invoice.java`

**Responsibility.** JPA entity for `invoices` — the **header** of a rated billing period for one subscription. Holds the lifecycle status, the period window, the copied currency, and the **denormalized** total (sum of its line amounts, so reads never re-aggregate). Carries the optimistic-locking `@Version`.

**Public types.**
- `public class Invoice`
- `public enum Status { DRAFT, ISSUED, PAID, VOID }` — persisted as `EnumType.STRING` into `status VARCHAR(16)` with a DB `CHECK (status IN (...))`.

**Fields / column mapping:**

| Field | Column | Notes |
|-------|--------|-------|
| `id` (`UUID`) | `id` PK | App-assigned. |
| `version` (`long`) | `version` NOT NULL DEFAULT 0 | **`@Version`** — Hibernate bumps it on every UPDATE (see concurrency note below). Added by migration `18-billing-invoice-integrity.sql`. |
| `orgId` (`UUID`) | `org_id` NOT NULL | FK → `organizations`. Used for cross-tenant-safe scoping. |
| `subscriptionId` (`UUID`) | `subscription_id` NOT NULL | FK → `subscriptions`. Half of the dedup key. |
| `periodStart` (`OffsetDateTime`) | `period_start` NOT NULL | UTC month boundary; the period key and other half of the dedup key. |
| `periodEnd` (`OffsetDateTime`) | `period_end` NOT NULL | Always `periodStart.plusMonths(1)`. |
| `status` (`Status`) | `status` NOT NULL | Defaults `DRAFT`. |
| `currency` (`String`) | `currency` `length=3` | Copied from the `BillingAccount` at generation. |
| `totalAmount` (`BigDecimal`) | `total_amount` NOT NULL, DB `CHECK (>= 0)` | Sum of line amounts from `RatingService`. |
| `issuedAt` (`OffsetDateTime`, nullable) | `issued_at` | Stamped only on `issue`. |
| `createdAt` (`OffsetDateTime`) | `created_at` NOT NULL | Set at generation. |

**Concurrency — why `@Version` matters here.** Two admins (or two API calls) can act on the same `DRAFT` invoice at once — one issuing, one voiding, or one re-generating. Without the version column, the second flush would silently overwrite the first (a VOID could be rewritten back to ISSUED, or a charge recorded against an invoice another transaction already voided). With `@Version`, Hibernate emits `UPDATE ... WHERE id=? AND version=?` and checks the affected-row count: the loser's update touches 0 rows → `ObjectOptimisticLockingFailureException`, which `GlobalExceptionHandler` maps to **`409 Conflict` with `retryable=true`** (not a 500). See `BillingService.issueInvoice` / `voidInvoice` for how the flush is deliberately ordered *before* the provider call.

**Gotchas.**
- The DB partial unique index `uq_invoices_subscription_period` (on `(subscription_id, period_start) WHERE status <> 'VOID'`) is *separate* from `@Version`; they guard different things (duplicate-period vs. concurrent-mutation). Both are load-bearing.
- `PAID` exists in the enum and DB check but **no method in this package transitions to it** — it's reserved for a future payment-confirmation path. `voidInvoice` already refuses to void a `PAID` invoice.

---

## `InvoiceRepository.java`

**Responsibility.** Spring Data repository for `Invoice` with org-scoped and dedup queries.

| Method | Returns | Purpose / why |
|--------|---------|---------------|
| `findByOrgIdOrderByCreatedAtDesc(orgId)` | `List<Invoice>` | The org's invoice list, newest first (controller list endpoint). |
| `findBySubscriptionIdOrderByCreatedAtDesc(subId)` | `List<Invoice>` | Per-subscription history (not wired to the controller, but available). |
| `findByIdAndOrgId(id, orgId)` | `Optional<Invoice>` | **Cross-tenant-safe single lookup.** Always used instead of `findById` so an attacker can't read another org's invoice by guessing its UUID — the `orgId` predicate makes a foreign invoice a 404. |
| `findActiveForPeriod(subscriptionId, periodStart)` | `Optional<Invoice>` | The dedup query (see below). |

**`findActiveForPeriod` — the dedup query:**

```java
@Query("""
    SELECT i FROM Invoice i
    WHERE i.subscriptionId = :subscriptionId
      AND i.periodStart = :periodStart
      AND i.status <> com.example.cp.billing.Invoice$Status.VOID
    """)
Optional<Invoice> findActiveForPeriod(UUID subscriptionId, OffsetDateTime periodStart);
```

It returns the single non-VOID invoice (if any) covering a subscription's period. It deliberately **mirrors the partial unique index** `uq_invoices_subscription_period` (same predicate, `status <> 'VOID'`), so at most one row can match — hence `Optional` is correct, not `List`. Used by `generateDraftInvoice` both as the up-front "already billed?" check and as the post-race recovery read.

**Gotcha.** Note the JPQL enum reference `com.example.cp.billing.Invoice$Status.VOID` — the `$`-qualified nested-class form is required so JPQL resolves the inner enum. If `Status` were ever moved out of `Invoice`, this query string must be updated.

---

## `InvoiceLineItem.java`

**Responsibility.** JPA entity for `invoice_line_items` — one **rated line** of an invoice. Modeled as a first-class entity (its own table + stable `id`) rather than a JPA `@ElementCollection`, so each line can be queried/referenced independently.

**Public type.** `public class InvoiceLineItem` (Lombok-generated accessors/builder).

**Fields / column mapping:**

| Field | Column | Notes |
|-------|--------|-------|
| `id` (`UUID`) | `id` PK | App-assigned. |
| `invoiceId` (`UUID`) | `invoice_id` NOT NULL | FK → `invoices(id)` `ON DELETE CASCADE` — deleting an invoice deletes its lines. Plain UUID column, **not** a JPA `@ManyToOne` relationship (the package favors explicit ids + repositories over object graphs). |
| `featureKey` (`String`) | `feature_key` `length=64` | The metered feature, e.g. `"seats"`, `"api_calls"`. |
| `quantity` (`BigDecimal`) | `quantity` NOT NULL | Consumed units for the period. |
| `unitAmount` (`BigDecimal`) | `unit_amount` NOT NULL | Per-unit price resolved from the price book (the **raw configured rate**, never rescaled). |
| `amount` (`BigDecimal`) | `amount` NOT NULL | `quantity × unitAmount`, rounded HALF_UP to the currency scale. |
| `description` (`String`, nullable) | `description` TEXT | Human-readable, e.g. `"seats usage (10 @ 5.00)"`. |

**Gotcha.** Because there's no `@ManyToOne`/`@OneToMany`, you don't get cascade persistence from the Java side — `BillingService` saves each line explicitly after the invoice header is flushed, and the controller fetches lines separately via `InvoiceLineItemRepository.findByInvoiceId`. The cascade-delete is a *DB-side* FK behavior, not a JPA cascade.

---

## `InvoiceLineItemRepository.java`

```java
public interface InvoiceLineItemRepository extends JpaRepository<InvoiceLineItem, UUID> {
    List<InvoiceLineItem> findByInvoiceId(UUID invoiceId);
}
```

Single query: all lines for an invoice. Called by `BillingService.lineItems(invoiceId)` and indirectly by every controller endpoint that builds an `InvoiceDto`.

**Gotcha (N+1 in list endpoint).** `BillingController.listInvoices` maps each invoice to a DTO and calls `lineItems(inv.getId())` per invoice — i.e. one query per invoice. Fine for the expected handful-of-invoices-per-org scale, but worth knowing if an org accumulates thousands of invoices.

---

## `BillingProvider.java`

**Responsibility.** The **provider-neutral seam**. This interface is shaped like a typical payment-provider SDK so a real implementation (Stripe, Paddle, …) can be dropped in *without touching* the rating, invoicing, or controller layers. No implementation in this codebase contacts an external system.

**Contract — three methods:**

| Method | Signature | Meaning | Manual impl behavior |
|--------|-----------|---------|----------------------|
| `name()` | `String name()` | Stable lowercase provider name persisted on `BillingAccount.provider`. | `"manual"` |
| `createCustomer` | `String createCustomer(UUID orgId, String currency)` | Ensure a provider customer exists for the org; return its external id. | Returns `"manual_cust_<orgId>"`, no network. |
| `recordCharge` | `String recordCharge(String externalCustomerId, UUID invoiceId, BigDecimal amount, String currency)` | Record/initiate a charge for the invoice total; return an opaque charge reference. | Bookkeeping no-op; returns `"manual_charge_<invoiceId>"`. |
| `finalizeInvoice` | `String finalizeInvoice(String externalCustomerId, UUID invoiceId)` | Lock/issue the invoice on the provider side; return an opaque provider invoice ref. | Returns `"manual_inv_<invoiceId>"`. |

**Critical contract requirement — idempotency.** The Javadoc mandates that implementations be **side-effect-idempotent under retries**: `createCustomer` for an org that already has an external id must return the existing id rather than create a duplicate, and `recordCharge`/`finalizeInvoice` must be safe to call again. This requirement is *why* `BillingService` orders its operations as it does (durable status flip first, provider call after — see `issueInvoice`): if the transaction commits but the process dies before/after the provider call, a retry must not double-charge.

**Note on units.** `recordCharge`'s `amount` is documented as "minor or major units per provider convention." The manual provider treats it as the **major-unit decimal** stored on the invoice (`Invoice.totalAmount`). A real Stripe adapter would typically convert to minor units (cents) here — a thing to remember when writing one.

**Collaborators.** Injected into `BillingService` as a single `BillingProvider` bean. With multiple implementations on the classpath, a real one can be marked `@Primary` to win autowiring over `ManualBillingProvider`.

---

## `ManualBillingProvider.java`

**Responsibility.** The default, **dependency-free** `BillingProvider` (`@Component`, so it's the lone bean unless another `@Primary` provider is registered). Makes **no external/network calls**: every reference is generated locally and operations are pure bookkeeping + a `DEBUG` log line. This lets the full pipeline (`account → rate → invoice → issue`) run end-to-end in dev/test and in deployments that reconcile money manually.

**Public surface.**
- `public static final String NAME = "manual"` — the provider name (also returned by `name()`).
- `name()` → `"manual"`.
- `createCustomer(orgId, currency)` → `"manual_cust_" + orgId`.
- `recordCharge(customer, invoiceId, amount, currency)` → `"manual_charge_" + invoiceId`.
- `finalizeInvoice(customer, invoiceId)` → `"manual_inv_" + invoiceId`.

**Why the references are deterministic.** Each ref is derived from a stable id (`orgId` / `invoiceId`), so calling any method twice yields the *same* string — trivially satisfying the interface's idempotency contract. The references are opaque bookkeeping handles, not anything the manual provider later looks up.

**Gotcha.** Because `createCustomer` is a pure function of `orgId`, the customer id is stable even if the account row is deleted and re-created — handy, but don't assume that property for a real provider (real customer ids are server-assigned and *not* derivable).

---

## `RatingService.java`

**Responsibility.** The **rating math** — turns a period's metered usage (`feature_key → consumed quantity`) into priced `RatedLine`s plus a summed total, using the plan's *price book*. It is intentionally **pure**: it performs no persistence and no usage gathering. `BillingService` collects the usage and persists the result; `RatingService` just computes. This purity is what makes the rating logic unit-testable in isolation (hence the price-book overloads).

**Public constants.**
- `SCALE = 2` — default decimal places (the common two-minor-digit currencies, USD/EUR) used when no currency is supplied.
- `PRICE_PREFIX = "price."` — plan-feature prefix marking a per-unit price (vs. a metered usage feature).
- `DEFAULT_PRICE_KEY = "price.default"` — the plan-wide fallback per-unit rate key.

### The price-book model

Pricing is read from the subscription's plan features (`PlanService.getFeatures(planId)` → `Map<String,Object>`). For a metered feature `F`, the per-unit rate is resolved in priority order:

1. **Explicit** plan feature `"price.F"` whose value is numeric (e.g. `price.seats = 5.00`).
2. **Plan-wide default** `"price.default"` — one fallback rate applied to every metered feature lacking an explicit price.
3. **Zero** — the line is still emitted (quantity recorded) but contributes `0` to the total. So *unpriced usage is visible without being charged* — a deliberate, auditable choice rather than dropping it.

`price.*` entries are themselves **never** emitted as usage lines (they're filtered out). Output lines are **sorted by feature key** for deterministic invoices.

### Methods

**`rate(UUID planId, Map<String,BigDecimal> consumedByFeature)`** — convenience overload; delegates to the currency-aware form with `currencyCode = null` (→ default `SCALE` of 2). `planId == null` ⇒ empty price book ⇒ all rates 0.

**`rate(UUID planId, Map<String,BigDecimal> consumedByFeature, String currencyCode)`** — the production entry point used by `BillingService`. Loads the price book for the plan and rates with the **currency's** minor-unit scale (P3). This is the only method that reads `PlanService`.

**`rateWithPriceBook(priceBook, consumedByFeature)`** and **`rateWithPriceBook(priceBook, consumedByFeature, int scale)`** — rate against an explicit, already-built price book. Exposed primarily for deterministic unit tests of the math (no `PlanService` needed). The 2-arg form rounds to `SCALE`.

The core loop (in the 3-arg `rateWithPriceBook`):

```java
defaultRate = priceBook.get("price.default");           // may be null
features = consumedByFeature.keySet()
             minus null and any "price.*"                // price.* are not usage lines
             sorted ascending;                            // deterministic order
total = 0 scaled to `scale`;
for each feature:
    qty    = normalizeQuantity(consumed)                  // null/negative -> 0
    rate   = resolveRate(...)                             // explicit -> default -> 0; negative clamped to 0
    amount = qty.multiply(rate).setScale(scale, HALF_UP)  // ONLY the money amount is rounded
    line   = RatedLine(feature, qty, rate, amount, "<feature> usage (<qty> @ <rate>)")
    total += amount
return RatedInvoice(immutable copy of lines, total.setScale(scale, HALF_UP));
```

**`scaleFor(String currencyCode)`** (public static) — returns the number of decimals to round money to for an ISO-4217 code: its **minor-unit exponent** via `Currency.getInstance(code).getDefaultFractionDigits()`. Examples: USD/EUR → 2, JPY/KRW → 0, BHD/KWD → 3. Falls back to `SCALE` (2) when the code is `null`/blank, **unknown** (`IllegalArgumentException` caught), or has *no* defined minor unit (exponent `-1`, e.g. `XAU` gold). This is the P3 currency-correct rounding fix — JPY invoices no longer carry phantom cents, KWD invoices keep their three fils digits.

**`loadPriceBook(UUID planId)`** (public) — reads `PlanService.getFeatures(planId)` and extracts the `price.*` entries into a rate map: keys are the *metered* feature keys (prefix stripped), plus `price.default` kept verbatim. Non-numeric values are silently ignored (via `toBigDecimal`).

**Private helpers.**
- `resolveRate(priceBook, defaultRate, feature)` — explicit `price.feature` → else `defaultRate` → else `ZERO`; **clamps a negative rate to 0** (defense against a misconfigured negative price producing a credit).
- `normalizeQuantity(qty)` — `null` or negative → `ZERO` (negative usage never credits).
- `toBigDecimal(value)` — coerces `BigDecimal`/`Number`/numeric-`String` to `BigDecimal`; returns `null` (= "not a price") for anything else or unparseable strings. Note `Number → new BigDecimal(n.toString())` deliberately routes through the *string* form to avoid `new BigDecimal(double)`'s binary-float artifacts.

**Public records (the result types).**
- `RatedLine(String featureKey, BigDecimal quantity, BigDecimal unitAmount, BigDecimal amount, String description)` — one priced line before persistence (no id yet).
- `RatedInvoice(List<RatedLine> lines, BigDecimal total)` — the immutable rated result (lines are a `List.copyOf`).

**Rounding subtleties a new engineer must know.**
- Only **money amounts** (`amount`, `total`) are scaled; `unitAmount` is stored as the raw configured rate — so a `price.seats = 5` shows `5`, not `5.00`, in the line's `unitAmount`, while the line `amount` is fully scaled.
- The total is the sum of **already-rounded** line amounts, then re-scaled. This is "round-then-sum," so the invoice total always exactly equals the sum of the displayed line amounts (no penny-reconciliation drift between total and lines).

**Collaborators.** Reads `PlanService.getFeatures`; called by `BillingService.generateDraftInvoice`.

---

## `BillingService.java`

**Responsibility.** The orchestrator and **persistence + lifecycle layer**. It is the *only* class that touches `BillingProvider`, and it never mutates usage or subscription state (both are read-only through their repositories). Rating math is delegated wholesale to `RatingService`.

**Dependencies (constructor-injected):** `BillingAccountRepository`, `InvoiceRepository`, `InvoiceLineItemRepository`, `SubscriptionRepository`, `UsageQuotaRepository`, `RatingService`, `BillingProvider`, `AuditWriter`.

### Billing account

**`getOrCreateAccount(UUID orgId, String currency)` `@Transactional`** — idempotent get-or-create, one row per org.

Flow:
```
findByOrgId(orgId)
  └─ present → return it
  └─ empty   → cur = normalizeCurrency(currency)          // null/blank -> "USD", else trim+upper
              externalId = billingProvider.createCustomer(orgId, cur)
              build BillingAccount(Ids.newId(), ..., createdAt=now)
              try saveAndFlush
                catch DataIntegrityViolationException race:
                    return findByOrgId(orgId).orElseThrow(() -> race)
```

This is a **check-then-insert**, so two concurrent first-access calls can both miss `findByOrgId`. The `org_id` UNIQUE constraint guarantees only one `INSERT` wins; the loser catches `DataIntegrityViolationException` and re-reads the winner's row — turning a would-be 500 into a clean idempotent return. `saveAndFlush` (not plain `save`) is essential: the violation must surface *here*, inside the try, not at some later flush outside the catch.

**`getAccount(UUID orgId)` `@Transactional(readOnly = true)`** — returns the account or throws `ApiException.notFound` (→ 404). Does *not* auto-create (that's a read endpoint).

### Invoice reads

- `listInvoices(orgId)` → `findByOrgIdOrderByCreatedAtDesc`.
- `getInvoiceForOrg(orgId, invoiceId)` → `findByIdAndOrgId(...)` or 404. The org predicate is the cross-tenant guard.
- `lineItems(invoiceId)` → `findByInvoiceId`.

### `generateDraftInvoice(UUID orgId, UUID subscriptionId, OffsetDateTime period)` `@Transactional`

The heart of the package — rate exactly one period into a `DRAFT` invoice. Step by step:

1. **Tenant check.** `subscriptionRepo.findById(subscriptionId).filter(s -> orgId.equals(s.getOrgId()))` or `notFound` → a subscription belonging to another org is a 404, *never* cross-tenant access.
2. **Period normalization.** `periodStart = monthStartUtc(period == null ? now() : period)`, `periodEnd = periodStart.plusMonths(1)`. `monthStartUtc` converts to UTC (`withOffsetSameInstant(UTC)`), then `withDayOfMonth(1).truncatedTo(DAYS)` — i.e. the first instant of the UTC month. This is the exact key `usage_quotas` are bucketed under. A `null` period defaults to the current month.
3. **Dedup pre-check.** `invoiceRepo.findActiveForPeriod(subscriptionId, periodStart)` — if a non-VOID invoice already covers the period, **return it as-is** (idempotent; no re-bill).
4. **Ensure account.** `getOrCreateAccount(orgId, null)` — generate always works for a valid org; currency defaults to USD if the account is new.
5. **Gather this period's usage.** Iterate `usageQuotaRepo.findBySubscriptionId(subscriptionId)`, **keep only rows where `periodStart.isEqual(q.getPeriodStart())`**, and fold each row's `consumedValue` (null → 0) into `consumedByFeature` keyed by `featureKey` (`LinkedHashMap`, summed with `BigDecimal::add`). Critically, this bills **exactly that one period** — never merged lifetime usage. The window is the period itself, regardless of which features happened to record usage.
6. **Rate.** `ratingService.rate(sub.getPlanId(), consumedByFeature, account.getCurrency())` → `RatedInvoice`.
7. **Persist header (dedup backstop).** Build the `Invoice` (`DRAFT`, currency from account, `totalAmount = rated.total()`) and `saveAndFlush`. If a concurrent generate for the same `(subscription, period)` committed first and tripped the partial unique index `uq_invoices_subscription_period`, catch `DataIntegrityViolationException` and **return the winner's invoice** via `findActiveForPeriod` — idempotent, not a 500 or a double-bill.
8. **Persist lines.** For each `RatedLine`, save an `InvoiceLineItem` (fresh `Ids.newId()`, `invoiceId = saved.getId()`).
9. Return the saved invoice.

**Why two layers of dedup (step 3 + step 7)?** Step 3 handles the common case cheaply (already billed → just return). Step 7 is the concurrency backstop for the narrow window where two requests both pass step 3 before either commits — the DB's partial unique index is the single source of truth, and the catch makes the race idempotent.

**`monthStartUtc(OffsetDateTime t)`** (private static) — the period-bucketing helper described above. Time-zone correctness lives entirely here: callers may pass any instant within the target month in any offset; it always collapses to the UTC month start.

### `issueInvoice(UUID orgId, UUID invoiceId)` `@Transactional`

Finalize `DRAFT → ISSUED`.

1. Load via `getInvoiceForOrg` (404 if foreign/missing).
2. If status `!= DRAFT` → `ApiException.conflict` (**409**) — only a draft can be issued.
3. `getOrCreateAccount(orgId, invoice.getCurrency())`.
4. **Claim the transition first.** Set status `ISSUED`, stamp `issuedAt = now()`, and `saveAndFlush` — this takes the `@Version` bump *before* the provider is called.
5. **Then** call the provider: `recordCharge(externalCustomerId, id, totalAmount, currency)` and `finalizeInvoice(externalCustomerId, id)`.
6. **Audit fail-closed:** `auditWriter.record(actor, orgId, "billing.invoice.issued", "invoice", id, payload, ip, SUCCESS, true)` with the trailing `true` requesting a synchronous/atomic audit row (commits with the status change), then `AuditContext.markRecorded()`.

**Why flush-before-provider (P2).** Ordering the durable `@Version`-guarded status flip ahead of the money-moving provider call means a concurrent second `issue` (or a concurrent `void`) that read the same `DRAFT` will **lose its optimistic-lock check at flush** (`ObjectOptimisticLockingFailureException` → 409) — so the charge is never recorded twice and a VOID is never silently overwritten back to ISSUED. The provider call follows the committed transition and is *required to be idempotent* (per the `BillingProvider` contract), covering the crash-after-flush-before-provider window via retry.

### `voidInvoice(UUID orgId, UUID invoiceId)` `@Transactional`

Cancel `DRAFT`/`ISSUED → VOID`.

1. Load via `getInvoiceForOrg`.
2. If status is `PAID` or already `VOID` → `ApiException.conflict` (**409**).
3. Set `VOID`, `saveAndFlush` (takes the `@Version` bump).
4. Audit fail-closed: `"billing.invoice.voided"` + `markRecorded()`.

**Effect on dedup.** Because the partial unique index excludes VOID rows, voiding **frees the `(subscription, period)` slot** — a fresh draft can then be generated for that same period. So void-then-regenerate is the supported "re-bill a period" path.

**Concurrency.** Same `@Version` story: a concurrent `issue` that read the same `DRAFT` loses the lock race rather than overwriting this VOID back to ISSUED.

### Private helpers

- `normalizeCurrency(String)` — `null`/blank → `"USD"`, else `trim().toUpperCase()`.
- `actorUserId()` — prefers `AuditContext.currentActorUserId()`, falling back to `SecurityUtils.currentUser().userId()` (returns `null` if neither is set, e.g. a system context).

**Collaborators.**
- *Calls:* `RatingService`, `BillingProvider`, all five repositories, `AuditWriter`, `AuditContext`, `SecurityUtils`, `Ids`, `ApiException`.
- *Reads cross-package:* `Subscription` (`getOrgId`, `getPlanId`), `UsageQuota` (`getPeriodStart`, `getFeatureKey`, `getConsumedValue`).
- *Called by:* `BillingController`.

**Gotchas for a new engineer.**
- Every public method is transactional; the `DataIntegrityViolationException` catches *only* work because the inserts use `saveAndFlush` — replacing them with plain `save` would defer the violation past the catch and you'd get a 500 on the race.
- `generateDraftInvoice` filters usage in Java (`findBySubscriptionId` then equality filter) rather than a period-scoped query — fine for the row counts here, but it does load all of a subscription's quota rows.
- Issuing audits with the *committed-in-transaction* flag (`true`); if the audit write fails the whole issue rolls back (fail-closed by design).

---

## `BillingController.java`

**Responsibility.** The org-scoped REST surface. Base path `/api/v1/orgs/{orgId}/billing`. Thin: it delegates to `BillingService` and maps entities ↔ DTOs.

### Authorization model

Every endpoint is gated by Spring Security `@PreAuthorize` SpEL combining an **authority** (the permission scope) with a **tenant check** (`@tenantAccess`, i.e. `com.example.cp.security.TenantAccessChecker`):

| Endpoint | Method | `@PreAuthorize` | Who can call |
|----------|--------|-----------------|--------------|
| `GET /account` | `getAccount` | `hasAuthority('billing.read') and @tenantAccess.canAccessOrg(#orgId)` | any org member (or super-admin / org-bound API key) with read scope |
| `GET /invoices` | `listInvoices` | same as above | same |
| `GET /invoices/{invoiceId}` | `getInvoice` | same as above | same |
| `POST /invoices/generate` | `generate` | `@tenantAccess.canManageOrg(#orgId)` | OWNER/ADMIN or super-admin; **API keys denied writes** |
| `POST /invoices/{invoiceId}/issue` | `issue` | `@tenantAccess.canManageOrg(#orgId)` | same |
| `POST /invoices/{invoiceId}/void` | `voidInvoice` | `@tenantAccess.canManageOrg(#orgId)` | same |

Two-part SpEL design: the **tenant check** makes cross-tenant access impossible (the checker ignores global authorities and verifies the principal really belongs to `#orgId`), while the **authority gate** keeps a read-only role from mutating billing. The `billing.read` permission is seeded for `SUPER_ADMIN/ORG_OWNER/ORG_ADMIN/ORG_MEMBER/VIEWER` in `16-billing.sql`. Writes intentionally have *no* `billing.write` authority requirement — `canManageOrg` (role-based: OWNER/ADMIN/super-admin) is the gate, and it independently denies API-key principals.

### Endpoint behavior

- `getAccount` → `BillingAccountDto.from(billingService.getAccount(orgId))`.
- `listInvoices` → maps each invoice to `InvoiceDto`, fetching lines per invoice (see N+1 note under `InvoiceLineItemRepository`).
- `getInvoice` → org-scoped single invoice + lines.
- `generate` → `generateDraftInvoice(orgId, body.subscriptionId(), body.period())`, returns **HTTP 201** with the DTO. (Idempotent: a repeat generate for the same period returns the existing invoice — still 201 with that invoice's body.)
- `issue` / `voidInvoice` → call the matching service method, return the updated DTO (HTTP 200).

### DTOs (records)

- `GenerateRequest(@NotNull UUID subscriptionId, OffsetDateTime period)` — `period` is optional (any instant within the target UTC month; absent → current month). `@NotNull` on `subscriptionId` is validated by Spring before the handler runs (→ 400 on omission).
- `BillingAccountDto(id, orgId, provider, externalCustomerId, currency, createdAt)` with static `from(BillingAccount)`.
- `LineItemDto(id, featureKey, quantity, unitAmount, amount, description)` with `from(InvoiceLineItem)`.
- `InvoiceDto(id, orgId, subscriptionId, periodStart, periodEnd, status, currency, totalAmount, issuedAt, createdAt, lineItems)` with `from(Invoice, List<InvoiceLineItem>)` — note `status` is serialized as the enum **name string**.

### HTTP status outcomes (worth memorizing)

| Situation | Status | Source |
|-----------|--------|--------|
| Subscription not in org / invoice not in org | 404 | `ApiException.notFound` |
| Missing `subscriptionId` in generate body | 400 | bean validation `@NotNull` |
| Issue a non-DRAFT, or void a PAID/VOID | 409 | `ApiException.conflict` |
| Concurrent generate loses period race | (recovered → 201 with existing invoice) | caught `DataIntegrityViolationException` |
| Concurrent issue/void loses `@Version` race | 409 `retryable=true` | `OptimisticLockingFailureException` → `GlobalExceptionHandler` |
| Get-or-create account loses unique race | (recovered → success) | caught `DataIntegrityViolationException` |
| Cross-tenant / insufficient role | 403 | `@PreAuthorize` |

---

## How it fits the bigger picture

Billing is a **downstream consumer** of three other control-panel subsystems and a producer for the admin UI:

- **Subscriptions** (`com.example.cp.subscriptions`) supply the `orgId`/`planId` and define what's being billed; billing reads them but never writes them.
- **Usage** (`com.example.cp.usage`) records metered consumption into `usage_quotas` (bucketed by UTC month). Billing's period scoping is precisely tuned to that bucketing — `monthStartUtc` mirrors how `UsageIngestService` keys quota rows.
- **Plans** (`com.example.cp.plans`) hold the price book as `price.*` plan features; `RatingService` reads them via `PlanService.getFeatures`.
- **Audit** (`com.example.cp.audit`) records every issue/void fail-closed; **security** (`TenantAccessChecker`, RBAC permissions seeded in migrations) enforces tenant isolation and authority; **common** (`ApiException`, `GlobalExceptionHandler`, `Ids`, `AuditContext`, `SecurityUtils`) provides the error→HTTP mapping (including the optimistic-lock/integrity → 409 translations that the concurrency design depends on).

The deliberately-empty `BillingProvider`/`ManualBillingProvider` seam is the headline architectural feature: **everything except actual money movement is implemented**, so the system bills, rates, dedups, and locks correctly today, and a real Stripe/Paddle adapter can be added as a single `@Primary` bean implementing three idempotent methods — no change to rating, invoicing, the controller, or the schema required. The known deferrals (real payment capture, the `PAID` transition) are the natural next steps that this seam was shaped to accept.
