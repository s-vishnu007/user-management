export type Role = 'SUPER_ADMIN' | 'ORG_OWNER' | 'ORG_ADMIN' | 'ORG_MEMBER' | 'VIEWER';

export interface User {
  id: string;
  email: string;
  displayName?: string;
  status?: 'ACTIVE' | 'DISABLED' | 'INVITED';
  permissions?: string[];
  roles?: { code: Role; orgId?: string }[];
  createdAt?: string;
}

export interface LoginResponse {
  accessToken: string;
  expiresAt: string;
  user: User;
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

export interface Plan {
  id: string;
  code: string;
  name: string;
  description?: string;
  status?: 'ACTIVE' | 'DRAFT' | 'RETIRED';
  permissions: string[];
  features: PlanFeature[];
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

export interface License {
  jti: string;
  subscriptionId: string;
  orgId?: string;
  orgName?: string;
  planCode?: string;
  kid: string;
  issuedAt: string;
  expiresAt: string;
  revokedAt?: string | null;
  revokeReason?: string | null;
  fingerprint?: string;
  lastSeenAt?: string | null;
  lastSeenIp?: string | null;
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

export interface SsoConfig {
  orgId: string;
  protocol: 'SAML' | 'OIDC';
  enabled: boolean;
  metadataUrl?: string;
  metadataXml?: string;
  issuer?: string;
  clientId?: string;
  clientSecret?: string;
  discoveryUrl?: string;
  attributeMapping?: Record<string, string>;
}

export interface ApiKey {
  id: string;
  name: string;
  prefix: string;
  scopes: string[];
  createdAt: string;
  lastUsedAt?: string | null;
  expiresAt?: string | null;
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
