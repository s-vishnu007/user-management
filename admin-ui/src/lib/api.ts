import axios, { AxiosError, type AxiosInstance } from 'axios';
import type {
  ApiKey,
  ApiKeyDto,
  AssignableGrants,
  AuditEntry,
  AuthIdentity,
  IssuedLicense,
  License,
  LoginResponse,
  MeResponse,
  OrgMember,
  Organization,
  Paged,
  Permission,
  Plan,
  RegisterResponse,
  RoleDef,
  SigningKey,
  SsoDiscovery,
  SsoProviderConfig,
  SsoProviderDto,
  SsoProviderView,
  SsoType,
  Subscription,
  SubscriptionOverride,
  UsageReport,
  User,
} from './types';

export const API_BASE: string =
  (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://localhost:8080';

// The session lives in the HttpOnly `cp_session` cookie set by the backend on login/SSO. We do
// NOT persist the JWT in localStorage (it is reachable by XSS, defeating the HttpOnly cookie —
// finding P2 #32). `withCredentials` makes the browser send the cookie on every request, so the
// SPA authenticates purely via the cookie and never needs to read or attach a Bearer token.
export const http: AxiosInstance = axios.create({
  baseURL: API_BASE,
  withCredentials: true,
});

let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(h: () => void) {
  onUnauthorized = h;
}

http.interceptors.response.use(
  (r) => r,
  (err: AxiosError) => {
    if (err.response?.status === 401) {
      onUnauthorized?.();
    }
    return Promise.reject(err);
  },
);

export function apiErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: string; error?: string } | undefined;
    return data?.message ?? data?.error ?? err.message;
  }
  if (err instanceof Error) return err.message;
  return 'Unknown error';
}

/** Generates an RFC4122-ish idempotency key for create mutations. */
export function newIdempotencyKey(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  // Fallback for non-secure contexts / older runtimes.
  return 'idm-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
}

function idempotent(key?: string) {
  return { headers: { 'Idempotency-Key': key ?? newIdempotencyKey() } };
}

// ---------- Auth ----------
export const auth = {
  login: async (email: string, password: string): Promise<LoginResponse> => {
    const { data } = await http.post<LoginResponse>('/api/v1/auth/login', { email, password });
    return data;
  },
  /** Step 2 of MFA login: exchange the signed challenge + TOTP code for a session. */
  mfaLogin: async (challenge: string, code: string): Promise<LoginResponse> => {
    const { data } = await http.post<LoginResponse>('/api/v1/auth/mfa/login', { challenge, code });
    return data;
  },
  logout: async (): Promise<void> => {
    await http.post('/api/v1/auth/logout');
  },
  /** Self-service signup: creates a new org with the signer as OWNER and auto-logs-in. */
  register: async (payload: {
    fullName: string;
    email: string;
    password: string;
    orgName: string;
  }): Promise<RegisterResponse> => {
    const { data } = await http.post<RegisterResponse>('/api/v1/auth/register', payload);
    return data;
  },
  /** Consumes an email-verification token (from the link the user received). */
  verifyEmail: async (token: string): Promise<{ verified: boolean; email: string }> => {
    const { data } = await http.post<{ verified: boolean; email: string }>(
      '/api/v1/auth/verify-email',
      { token },
    );
    return data;
  },
  /** Re-issues a verification email for the currently signed-in, still-unverified user. */
  resendVerification: async (): Promise<{
    status: string;
    alreadyVerified: boolean;
    verification_token?: string;
  }> => {
    const { data } = await http.post('/api/v1/auth/verify-email/resend');
    return data as { status: string; alreadyVerified: boolean; verification_token?: string };
  },
  /**
   * Public SSO discovery for the login/signup screen. Pass a work email or org slug; the backend
   * resolves the org and returns its enabled providers plus the global Google flag. Never leaks
   * secrets. Returns null on 404 (unknown org) so callers can show "no SSO for that org".
   */
  ssoDiscovery: async (orgOrEmail: string): Promise<SsoDiscovery | null> => {
    try {
      const { data } = await http.get<SsoDiscovery>('/api/v1/auth/sso/discovery', {
        params: { q: orgOrEmail },
      });
      return data;
    } catch {
      return null;
    }
  },
  /** Browser-navigation URL that starts the per-org SSO flow (302 → IdP). Not an axios call. */
  ssoStartUrl: (orgSlug: string, providerId?: string): string => {
    const base = `${API_BASE}/api/v1/auth/sso/${encodeURIComponent(orgSlug)}/start`;
    return providerId ? `${base}?provider=${encodeURIComponent(providerId)}` : base;
  },
  /** Browser-navigation URL for the global "Continue with Google" OIDC flow. */
  ssoGoogleStartUrl: (): string => `${API_BASE}/api/v1/auth/sso/google/start`,
  /** Cookie-authenticated identity bootstrap; returns the unified {user, permissions, orgs}. */
  me: async (): Promise<AuthIdentity> => {
    const { data } = await http.get<MeResponse>('/api/v1/auth/me');
    return { user: data.user, permissions: data.permissions ?? [], orgs: data.orgs ?? [] };
  },
  requestPasswordReset: async (email: string) => {
    await http.post('/api/v1/auth/password-reset/request', { email });
  },
  confirmPasswordReset: async (token: string, newPassword: string) => {
    await http.post('/api/v1/auth/password-reset/confirm', { token, newPassword });
  },
};

// ---------- Orgs ----------
export const orgs = {
  list: async (): Promise<Organization[]> => {
    const { data } = await http.get<Organization[] | Paged<Organization>>('/api/v1/orgs');
    return Array.isArray(data) ? data : data.items;
  },
  create: async (
    payload: { slug: string; name: string },
    idempotencyKey?: string,
  ): Promise<Organization> => {
    const { data } = await http.post<Organization>('/api/v1/orgs', payload, idempotent(idempotencyKey));
    return data;
  },
  get: async (id: string): Promise<Organization> => {
    const { data } = await http.get<Organization>(`/api/v1/orgs/${id}`);
    return data;
  },
  members: async (id: string): Promise<OrgMember[]> => {
    const { data } = await http.get<OrgMember[] | Paged<OrgMember>>(`/api/v1/orgs/${id}/members`);
    return Array.isArray(data) ? data : data.items;
  },
  addMember: async (id: string, payload: { email: string; role: string }): Promise<OrgMember> => {
    const { data } = await http.post<OrgMember>(`/api/v1/orgs/${id}/members`, payload);
    return data;
  },
  removeMember: async (id: string, userId: string): Promise<void> => {
    await http.delete(`/api/v1/orgs/${id}/members/${userId}`);
  },
};

// ---------- Users ----------
export const users = {
  get: async (id: string): Promise<User> => {
    const { data } = await http.get<User>(`/api/v1/users/${id}`);
    return data;
  },
  update: async (id: string, patch: Partial<User>): Promise<User> => {
    const { data } = await http.patch<User>(`/api/v1/users/${id}`, patch);
    return data;
  },
};

// ---------- RBAC ----------
export const rbac = {
  roles: async (): Promise<RoleDef[]> => {
    const { data } = await http.get<RoleDef[] | Paged<RoleDef>>('/api/v1/rbac/roles');
    return Array.isArray(data) ? data : data.items;
  },
  permissions: async (): Promise<Permission[]> => {
    // The endpoint returns a PagedResponse<PermissionDto>; unwrap `.items`.
    const { data } = await http.get<Permission[] | Paged<Permission>>('/api/v1/rbac/permissions');
    return Array.isArray(data) ? data : data.items;
  },
  assignRole: async (userId: string, payload: { roleCode: string; orgId?: string }) => {
    await http.post(`/api/v1/rbac/users/${userId}/roles`, payload);
  },
};

// ---------- Plans ----------
// The backend create endpoint takes `permissions` (string[]) and `features` (a JSON object
// keyed by feature key) inline; the permissions/features endpoints take `permissionCodes` and
// `features` respectively. `features` is a Map<String,Object>, NOT an array.
export const plans = {
  list: async (): Promise<Plan[]> => {
    const { data } = await http.get<Plan[] | Paged<Plan>>('/api/v1/plans');
    return Array.isArray(data) ? data : data.items;
  },
  get: async (id: string): Promise<Plan> => {
    const { data } = await http.get<Plan>(`/api/v1/plans/${id}`);
    return data;
  },
  create: async (
    payload: {
      code: string;
      name: string;
      description?: string;
      tier?: string;
      defaultTtlDays?: number;
      active?: boolean;
      permissions?: string[];
      features?: Record<string, unknown>;
    },
    idempotencyKey?: string,
  ): Promise<Plan> => {
    const { data } = await http.post<Plan>('/api/v1/plans', payload, idempotent(idempotencyKey));
    return data;
  },
  update: async (
    id: string,
    patch: {
      name?: string;
      description?: string;
      tier?: string;
      defaultTtlDays?: number;
      active?: boolean;
    },
  ): Promise<Plan> => {
    const { data } = await http.patch<Plan>(`/api/v1/plans/${id}`, patch);
    return data;
  },
  // Backend DTO field is `permissionCodes` — sending `permissions` binds null and DELETES every
  // entitlement permission on the plan (finding P0-2).
  setPermissions: async (id: string, permissionCodes: string[]): Promise<Plan> => {
    const { data } = await http.post<Plan>(`/api/v1/plans/${id}/permissions`, { permissionCodes });
    return data;
  },
  // Backend DTO field is `features`, typed as a JSON object (Map<String,Object>).
  setFeatures: async (id: string, features: Record<string, unknown>): Promise<Plan> => {
    const { data } = await http.post<Plan>(`/api/v1/plans/${id}/features`, { features });
    return data;
  },
};

// ---------- Subscriptions ----------
export const subscriptions = {
  listForOrg: async (orgId: string): Promise<Subscription[]> => {
    const { data } = await http.get<Subscription[] | Paged<Subscription>>(
      `/api/v1/orgs/${orgId}/subscriptions`,
    );
    return Array.isArray(data) ? data : data.items;
  },
  create: async (
    orgId: string,
    payload: {
      planId: string;
      startsAt: string;
      endsAt: string;
      seats: number;
      notes?: string;
      overrides?: SubscriptionOverride[];
    },
    idempotencyKey?: string,
  ): Promise<Subscription> => {
    const { data } = await http.post<Subscription>(
      `/api/v1/orgs/${orgId}/subscriptions`,
      payload,
      idempotent(idempotencyKey),
    );
    return data;
  },
  get: async (id: string): Promise<Subscription> => {
    const { data } = await http.get<Subscription>(`/api/v1/subscriptions/${id}`);
    return data;
  },
  suspend: async (id: string, reason?: string) => {
    await http.post(`/api/v1/subscriptions/${id}/suspend`, { reason });
  },
  cancel: async (id: string, reason?: string) => {
    await http.post(`/api/v1/subscriptions/${id}/cancel`, { reason });
  },
  reactivate: async (id: string) => {
    await http.post(`/api/v1/subscriptions/${id}/reactivate`);
  },
  // The backend endpoint adds ONE override per call: POST /subscriptions/{id}/overrides with a
  // single {type, key, value} body (not an array).
  addOverride: async (id: string, override: SubscriptionOverride) => {
    await http.post(`/api/v1/subscriptions/${id}/overrides`, override);
  },
  removeOverride: async (id: string, overrideId: string) => {
    await http.delete(`/api/v1/subscriptions/${id}/overrides/${overrideId}`);
  },
};

// ---------- Licenses ----------
export const licenses = {
  issue: async (
    subId: string,
    payload?: { ttlDays?: number; audience?: string[]; notes?: string; trial?: boolean },
    idempotencyKey?: string,
  ): Promise<IssuedLicense> => {
    const { data } = await http.post<IssuedLicense>(
      `/api/v1/subscriptions/${subId}/licenses`,
      payload ?? {},
      idempotent(idempotencyKey),
    );
    return data;
  },
  /**
   * Per-user issuance (the primary Licenses workspace flow): mints a JWT for a user inside an org
   * carrying a hand-picked RBAC grant set — no plan/subscription. Identify the subject by `userId`
   * or `email` (an unknown email is provisioned + added to the org). `permissions` (when present) is
   * the authoritative fine-tuned set; `roleCodes` are stored as the preset snapshot.
   */
  issueForOrg: async (
    orgId: string,
    payload: {
      userId?: string;
      email?: string;
      roleCodes?: string[];
      permissions?: string[];
      ttlDays?: number;
      audience?: string[];
      trial?: boolean;
      notes?: string;
    },
    idempotencyKey?: string,
  ): Promise<IssuedLicense> => {
    const { data } = await http.post<IssuedLicense>(
      `/api/v1/orgs/${orgId}/licenses`,
      payload,
      idempotent(idempotencyKey),
    );
    return data;
  },
  /** Org-anchored license list backing the Licenses workspace (shows every per-user token in the org). */
  listForOrg: async (orgId: string, status?: string): Promise<License[]> => {
    const q = new URLSearchParams();
    if (status) q.set('status', status);
    const qs = q.toString();
    const { data } = await http.get<License[] | Paged<License>>(
      `/api/v1/orgs/${orgId}/licenses${qs ? `?${qs}` : ''}`,
    );
    return Array.isArray(data) ? data : data.items;
  },
  /** Catalog of roles (with expanded permissions) + the permission catalog to populate the grant picker. */
  assignableGrants: async (): Promise<AssignableGrants> => {
    const { data } = await http.get<AssignableGrants>('/api/v1/licenses/assignable-grants');
    return data;
  },
  // The /licenses endpoint REQUIRES subscriptionId (the tenant-leak fix). There is no unscoped
  // global enumeration; callers must pass a subscriptionId (see licenses.list / LicensesPage).
  listForSubscription: async (subId: string, status?: string): Promise<License[]> => {
    const q = new URLSearchParams({ subscriptionId: subId });
    if (status) q.set('status', status);
    const { data } = await http.get<License[] | Paged<License>>(`/api/v1/licenses?${q.toString()}`);
    return Array.isArray(data) ? data : data.items;
  },
  /** Alias retained for clarity; subscriptionId is mandatory. */
  list: async (params: { subscriptionId: string; status?: string }): Promise<License[]> =>
    licenses.listForSubscription(params.subscriptionId, params.status),
  revoke: async (jti: string, reason?: string) => {
    await http.post(`/api/v1/licenses/${jti}/revoke`, { reason });
  },
  download: async (jti: string): Promise<{ blob: Blob; filename: string }> => {
    const resp = await http.get(`/api/v1/licenses/${jti}/download`, { responseType: 'blob' });
    const cd = resp.headers['content-disposition'] as string | undefined;
    let filename = `${jti}.lic`;
    if (cd) {
      const m = /filename\*?=(?:UTF-8'')?["']?([^;"']+)["']?/i.exec(cd);
      if (m?.[1]) filename = decodeURIComponent(m[1]);
    }
    return { blob: resp.data as Blob, filename };
  },
};

// ---------- Signing keys ----------
export const keys = {
  list: async (): Promise<SigningKey[]> => {
    const { data } = await http.get<SigningKey[] | Paged<SigningKey>>('/api/v1/admin/keys');
    return Array.isArray(data) ? data : data.items;
  },
  rotate: async (idempotencyKey?: string): Promise<SigningKey> => {
    const { data } = await http.post<SigningKey>(
      '/api/v1/admin/keys/rotate',
      {},
      idempotent(idempotencyKey),
    );
    return data;
  },
};

// ---------- Usage ----------
export const usage = {
  forSubscription: async (
    subId: string,
    params: { from?: string; to?: string } = {},
  ): Promise<UsageReport> => {
    const q = new URLSearchParams();
    if (params.from) q.set('from', params.from);
    if (params.to) q.set('to', params.to);
    const { data } = await http.get<UsageReport>(
      `/api/v1/subscriptions/${subId}/usage?${q.toString()}`,
    );
    return data;
  },
};

// ---------- Audit ----------
export const audit = {
  list: async (params: {
    actorId?: string;
    action?: string;
    targetType?: string;
    from?: string;
    to?: string;
    page?: number;
    pageSize?: number;
  } = {}): Promise<Paged<AuditEntry>> => {
    const q = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== '') q.set(k, String(v));
    });
    const { data } = await http.get<Paged<AuditEntry> | AuditEntry[]>(
      `/api/v1/audit?${q.toString()}`,
    );
    if (Array.isArray(data)) {
      return { items: data, total: data.length, page: 1, pageSize: data.length };
    }
    return data;
  },
  forOrg: async (orgId: string, params: { page?: number; pageSize?: number } = {}) => {
    const q = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined) q.set(k, String(v));
    });
    const { data } = await http.get<Paged<AuditEntry> | AuditEntry[]>(
      `/api/v1/orgs/${orgId}/audit?${q.toString()}`,
    );
    if (Array.isArray(data)) {
      return { items: data, total: data.length, page: 1, pageSize: data.length };
    }
    return data;
  },
};

// ---------- SSO ----------
/** Parses an SsoProviderDto's JSON `config` string into a typed view; tolerant of bad JSON. */
export function decodeSsoProvider(dto: SsoProviderDto): SsoProviderView {
  let config: SsoProviderConfig = {};
  try {
    const parsed = dto.config ? JSON.parse(dto.config) : {};
    if (parsed && typeof parsed === 'object') config = parsed as SsoProviderConfig;
  } catch {
    config = {};
  }
  return { id: dto.id, type: dto.type, enabled: dto.enabled, config };
}

export const sso = {
  // The API returns a LIST of {id, type, config(JSON string), enabled} — one per protocol —
  // NOT a single flat object.
  list: async (orgId: string): Promise<SsoProviderView[]> => {
    const { data } = await http.get<SsoProviderDto[] | Paged<SsoProviderDto>>(
      `/api/v1/orgs/${orgId}/sso`,
    );
    const arr = Array.isArray(data) ? data : data.items;
    return arr.map(decodeSsoProvider);
  },
  // Create accepts {type, config} where config is a JSON object (Map<String,Object>).
  create: async (
    orgId: string,
    payload: { type: SsoType; config: SsoProviderConfig },
  ): Promise<SsoProviderView> => {
    const { data } = await http.post<SsoProviderDto>(`/api/v1/orgs/${orgId}/sso`, payload);
    return decodeSsoProvider(data);
  },
  remove: async (orgId: string, id: string) => {
    await http.delete(`/api/v1/orgs/${orgId}/sso/${id}`);
  },
  test: async (orgId: string, id: string) => {
    const { data } = await http.post(`/api/v1/orgs/${orgId}/sso/${id}/test`);
    return data as { ok?: boolean; message?: string } & Record<string, unknown>;
  },
};

// ---------- API keys ----------
function parseScopes(raw: string | string[] | undefined): string[] {
  if (Array.isArray(raw)) return raw;
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    // Tolerate a plain comma-separated fallback.
    return raw.split(',').map((s) => s.trim()).filter(Boolean);
  }
}

/** Normalizes a backend ApiKeyDto (scopes is a JSON string) into the UI ApiKey shape. */
export function normalizeApiKey(dto: ApiKeyDto): ApiKey {
  return {
    id: dto.id,
    orgId: dto.orgId,
    name: dto.name,
    prefix: dto.keyPrefix,
    scopes: parseScopes(dto.scopes),
    createdAt: dto.createdAt,
    lastUsedAt: dto.lastUsedAt,
    revokedAt: dto.revokedAt,
  };
}

export const apiKeys = {
  list: async (orgId: string): Promise<ApiKey[]> => {
    const { data } = await http.get<ApiKeyDto[] | Paged<ApiKeyDto>>(
      `/api/v1/orgs/${orgId}/api-keys`,
    );
    const arr = Array.isArray(data) ? data : data.items;
    return arr.map(normalizeApiKey);
  },
  create: async (
    orgId: string,
    payload: { name: string; scopes: string[] },
    idempotencyKey?: string,
  ): Promise<ApiKey> => {
    // The create response is a CreateResponse: {id, name, key (plaintext), keyPrefix, scopes}.
    const { data } = await http.post<{
      id: string;
      name: string;
      key: string;
      keyPrefix: string;
      scopes: string[] | null;
      createdAt: string;
    }>(`/api/v1/orgs/${orgId}/api-keys`, payload, idempotent(idempotencyKey));
    return {
      id: data.id,
      name: data.name,
      prefix: data.keyPrefix,
      scopes: data.scopes ?? payload.scopes,
      createdAt: data.createdAt,
      plaintext: data.key,
    };
  },
  remove: async (orgId: string, id: string) => {
    await http.delete(`/api/v1/orgs/${orgId}/api-keys/${id}`);
  },
};
