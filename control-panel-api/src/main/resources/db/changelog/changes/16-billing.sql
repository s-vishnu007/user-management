--liquibase formatted sql
-- Owner: Bucket BILLING (provider-agnostic billing + invoicing + usage rating; NO real payment provider).
-- Adds three tables and one read permission:
--   billing_accounts    – one per org; binds an org to a (provider, external_customer_id, currency).
--   invoices            – a rated period for a subscription, in DRAFT/ISSUED/PAID/VOID state.
--   invoice_line_items  – the per-feature rated lines that sum to the invoice total.
-- Addresses ROADMAP gaps #74 (invoicing) and #75 (usage rating); the payment provider itself
-- (gap #73) is deliberately out of scope — ManualBillingProvider records to the DB with no external call.
-- Registered in db.changelog-master.yaml (by the CONFIG bucket) as: db/changelog/changes/16-billing.sql
--
-- Column/entity mapping (spring.jpa.hibernate.ddl-auto=validate, so each MUST match the entities):
--   billing_accounts   -> com.example.cp.billing.BillingAccount
--     id                   UUID PK                         -> id (UUID)
--     org_id               UUID NOT NULL UNIQUE FK orgs(id)-> orgId (UUID)
--     provider             VARCHAR(64) NOT NULL            -> provider (String)
--     external_customer_id TEXT                            -> externalCustomerId (String, nullable)
--     currency             VARCHAR(3) NOT NULL             -> currency (String)
--     created_at           TIMESTAMPTZ NOT NULL            -> createdAt (OffsetDateTime)
--   invoices           -> com.example.cp.billing.Invoice
--     id                   UUID PK                         -> id (UUID)
--     org_id               UUID NOT NULL FK orgs(id)       -> orgId (UUID)
--     subscription_id      UUID NOT NULL FK subscriptions  -> subscriptionId (UUID)
--     period_start         TIMESTAMPTZ NOT NULL            -> periodStart (OffsetDateTime)
--     period_end           TIMESTAMPTZ NOT NULL            -> periodEnd (OffsetDateTime)
--     status               VARCHAR(16) NOT NULL 'DRAFT'    -> status (enum DRAFT/ISSUED/PAID/VOID)
--     currency             VARCHAR(3) NOT NULL             -> currency (String)
--     total_amount         NUMERIC NOT NULL DEFAULT 0      -> totalAmount (BigDecimal)
--     issued_at            TIMESTAMPTZ                     -> issuedAt (OffsetDateTime, nullable)
--     created_at           TIMESTAMPTZ NOT NULL            -> createdAt (OffsetDateTime)
--   invoice_line_items -> com.example.cp.billing.InvoiceLineItem
--     id            UUID PK                                -> id (UUID)
--     invoice_id    UUID NOT NULL FK invoices(id) CASCADE  -> invoiceId (UUID)
--     feature_key   VARCHAR(64) NOT NULL                   -> featureKey (String)
--     quantity      NUMERIC NOT NULL                       -> quantity (BigDecimal)
--     unit_amount   NUMERIC NOT NULL                       -> unitAmount (BigDecimal)
--     amount        NUMERIC NOT NULL                       -> amount (BigDecimal)
--     description   TEXT                                   -> description (String, nullable)

--changeset cp:16-billing-accounts
CREATE TABLE billing_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL,
    external_customer_id TEXT,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE billing_accounts;

--changeset cp:16-invoices
CREATE TABLE invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ISSUED','PAID','VOID')),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    total_amount NUMERIC NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    issued_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_invoices_org ON invoices(org_id);
CREATE INDEX idx_invoices_subscription ON invoices(subscription_id);
CREATE INDEX idx_invoices_status ON invoices(status);
--rollback DROP TABLE invoices;

--changeset cp:16-invoice-line-items
CREATE TABLE invoice_line_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    feature_key VARCHAR(64) NOT NULL,
    quantity NUMERIC NOT NULL DEFAULT 0,
    unit_amount NUMERIC NOT NULL DEFAULT 0,
    amount NUMERIC NOT NULL DEFAULT 0,
    description TEXT
);
CREATE INDEX idx_invoice_line_items_invoice ON invoice_line_items(invoice_id);
--rollback DROP TABLE invoice_line_items;

--changeset cp:16-billing-permissions-seed
INSERT INTO permissions (code, name, description, category) VALUES
    ('billing.read', 'Read billing', 'View billing account, invoices, and line items', 'billing')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'billing.read'
WHERE r.code IN ('SUPER_ADMIN','ORG_OWNER','ORG_ADMIN','ORG_MEMBER','VIEWER')
ON CONFLICT DO NOTHING;
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code = 'billing.read'); DELETE FROM permissions WHERE code = 'billing.read';
