export type Role = 'SUPER_ADMIN' | 'ORG_OWNER' | 'ORG_ADMIN' | 'ORG_MEMBER' | 'VIEWER';

/**
 * Mirror of the backend {@code UserDto} returned by both {@code /auth/login} and
 * {@code /auth/me}. Note: the backend field is {@code fullName} (not displayName) and
 * {@code superAdmin}; permissions/roles are NOT on the user object — the authoritative
 * permission set comes from the {@code /auth/me} envelope (see {@link MeResponse}).
 */
export interface User {
  id: string;
  email: string;
  fullName?: string;
  status?: 'ACTIVE' | 'DISABLED' | 'INVITED';
  superAdmin?: boolean;
  /** Whether the user has confirmed their email. Self-service signups start false (non-blocking). */
  emailVerified?: boolean;
  createdAt?: string;
  lastLoginAt?: string;
}

/** Org membership entry from the {@code /auth/me} envelope. */
export interface OrgMembership {
  orgId: string;
  slug?: string;
  name?: string;
  role: string;
}

/**
 * Unified identity contract used by the SPA. Both the login completion and {@code /auth/me}
 * are normalized to this shape: a user, the flat authority/permission set, and org memberships.
 */
export interface AuthIdentity {
  user: User;
  permissions: string[];
  orgs: OrgMembership[];
}

/**
 * Raw {@code /auth/login} response. For an MFA-enabled user step 1 returns
 * {@code mfaRequired=true} with a short-lived {@code mfaChallenge} and NO session; the client
 * must then call {@code /auth/mfa/login} with a TOTP code to obtain a session.
 */
export interface LoginResponse {
  accessToken?: string;
  expiresAt?: string;
  user?: User;
  mfaRequired?: boolean;
  mfaChallenge?: string;
  mfaChallengeExpiresAt?: string;
}

/** Raw {@code /auth/me} response envelope. */
export interface MeResponse {
  user: User;
  orgs: OrgMembership[];
  permissions: string[];
}

/**
 * Raw {@code POST /auth/register} response. The backend auto-logs-in by setting the {@code
 * cp_session} cookie, so the SPA bootstraps identity via {@code /me} just like login. In dev
 * (app.auth.expose-verification-token=true) the raw {@code verificationToken} is returned so a
 * developer can verify without an email server; in prod it is omitted (emailed via the outbox).
 */
export interface RegisterResponse {
  accessToken?: string;
  expiresAt?: string;
  user?: User;
  orgSlug: string;
  emailVerificationSent: boolean;
  verificationToken?: string;
}

/** A login-screen-visible SSO provider for an org (secrets-free). */
export interface SsoProviderSummary {
  id: string;
  type: SsoType;
  label: string;
}

/**
 * Public SSO discovery for the login/signup screen: which per-org providers are available for a
 * given org slug, plus whether the global "Continue with Google" button is configured.
 */
export interface SsoDiscovery {
  orgSlug?: string;
  providers: SsoProviderSummary[];
  googleEnabled: boolean;
}

export interface Organization {
  id: string;
  slug: string;
  name: string;
  status?: 'ACTIVE' | 'DISABLED';
  createdAt?: string;
  memberCount?: number;
}

export interface OrgMember {
  userId: string;
  email: string;
  displayName?: string;
  role: Role;
  joinedAt?: string;
}

export interface Permission {
  code: string;
  description?: string;
  category?: string;
}

export interface RoleDef {
  code: Role;
  name: string;
  permissions: string[];
}

export interface PlanFeature {
  key: string;
  value: string | number | boolean;
}

/**
 * Mirror of the backend {@code PlanDto}. The backend exposes {@code active} (boolean) and
 * {@code tier} (not a {@code status} string), and {@code features} is a JSON object
 * ({@code Map<String,Object>}) keyed by feature key — NOT an array.
 */
export interface Plan {
  id: string;
  code: string;
  name: string;
  description?: string;
  tier?: string;
  active?: boolean;
  permissions: string[];
  features: Record<string, unknown>;
  defaultTtlDays?: number;
  createdAt?: string;
}

export type SubscriptionStatus = 'ACTIVE' | 'SUSPENDED' | 'EXPIRED' | 'CANCELLED' | 'PENDING';

export interface SubscriptionOverride {
  id?: string;
  type: 'PERMISSION_ADD' | 'PERMISSION_REMOVE' | 'FEATURE_SET';
  key: string;
  value?: string | number | boolean;
}

export interface Subscription {
  id: string;
  orgId: string;
  orgName?: string;
  planId: string;
  planCode?: string;
  planName?: string;
  status: SubscriptionStatus;
  startsAt: string;
  endsAt: string;
  seats: number;
  overrides?: SubscriptionOverride[];
  createdAt?: string;
}

/** Mirror of the backend {@code LicenseDto} returned by the licenses list/get endpoints. */
export interface License {
  id?: string;
  jti: string;
  subscriptionId: string;
  kid: string;
  issuedAt: string;
  expiresAt: string;
  revokedAt?: string | null;
  revokeReason?: string | null;
  fingerprint?: string;
  lastSeenAt?: string | null;
  lastSeenIp?: string | null;
  status?: 'ACTIVE' | 'REVOKED' | 'EXPIRED' | string;
  licenseType?: string | null;
  activeSeats?: number | null;
  // The list endpoint is subscription-scoped and does not carry org/plan; these are
  // enriched client-side (see LicensesPage) when iterating an org's subscriptions.
  orgId?: string;
  orgName?: string;
  planCode?: string;
}

/** Raw response of the {@code POST /subscriptions/{subId}/licenses} issue endpoint. */
export interface IssuedLicense {
  jti: string;
  kid: string;
  issuedAt: string;
  expiresAt: string;
  license: string;
  downloadUrl: string;
}

export interface SigningKey {
  kid: string;
  algorithm: string;
  status: 'ACTIVE' | 'RETIRED' | 'PENDING';
  createdAt: string;
  activatedAt?: string;
  retiredAt?: string | null;
  publicKey?: string;
}

export interface UsageEvent {
  id?: string;
  subscriptionId: string;
  jti?: string;
  featureKey: string;
  quantity: number;
  occurredAt: string;
  metadata?: Record<string, unknown>;
}

export interface UsageSeries {
  featureKey: string;
  points: { ts: string; quantity: number }[];
}

export interface UsageQuota {
  featureKey: string;
  limit: number | null;
  used: number;
  remaining: number | null;
}

export interface UsageReport {
  subscriptionId: string;
  windowStart: string;
  windowEnd: string;
  series: UsageSeries[];
  quotas: UsageQuota[];
}

export interface AuditEntry {
  id: string;
  actorId?: string;
  actorEmail?: string;
  action: string;
  targetType?: string;
  targetId?: string;
  payload?: Record<string, unknown>;
  occurredAt: string;
  ip?: string;
}

export type SsoType = 'SAML' | 'OIDC';

/**
 * Per-protocol SSO settings. The backend persists these as the opaque {@code config} JSON
 * object of an {@code SsoProvider}; the SPA flattens the typed fields it knows about.
 */
export interface SsoProviderConfig {
  enabled?: boolean;
  metadataUrl?: string;
  metadataXml?: string;
  issuer?: string;
  clientId?: string;
  clientSecret?: string;
  discoveryUrl?: string;
  allowedEmailDomains?: string;
  [key: string]: unknown;
}

/**
 * Mirror of the backend {@code SsoController.SsoDto}: one provider per {@code type}, with
 * {@code config} returned as a JSON string. The org may have multiple providers (one SAML,
 * one OIDC), so the SSO page operates on the list, not a single flat object.
 */
export interface SsoProviderDto {
  id: string;
  orgId: string;
  type: SsoType;
  config: string;
  enabled: boolean;
  createdAt?: string;
}

/** Decoded view used by the SSO config page. */
export interface SsoProviderView {
  id: string;
  type: SsoType;
  enabled: boolean;
  config: SsoProviderConfig;
}

/** Mirror of the backend {@code ApiKeyDto}. {@code scopes} arrives as a JSON-encoded string. */
export interface ApiKeyDto {
  id: string;
  orgId?: string;
  name: string;
  keyPrefix: string;
  scopes: string;
  createdAt: string;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
}

/** Normalized API key used by the UI (scopes parsed to an array). */
export interface ApiKey {
  id: string;
  orgId?: string;
  name: string;
  prefix: string;
  scopes: string[];
  createdAt: string;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
  // Present only on the one-time create response (backend field name is `key`).
  plaintext?: string;
}

export interface Paged<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ApiError {
  status: number;
  code?: string;
  message: string;
  details?: Record<string, unknown>;
}
