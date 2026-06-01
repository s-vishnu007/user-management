import axios, { AxiosError, type AxiosInstance } from 'axios';
import type {
  ApiKey,
  AuditEntry,
  License,
  LoginResponse,
  OrgMember,
  Organization,
  Paged,
  Permission,
  Plan,
  RoleDef,
  SigningKey,
  SsoConfig,
  Subscription,
  SubscriptionOverride,
  UsageReport,
  User,
} from './types';

export const API_BASE: string =
  (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://localhost:8080';

const TOKEN_STORAGE_KEY = 'cp.accessToken';

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY);
}
export function setStoredToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_STORAGE_KEY, token);
  else localStorage.removeItem(TOKEN_STORAGE_KEY);
}

export const http: AxiosInstance = axios.create({
  baseURL: API_BASE,
  withCredentials: true,
});

http.interceptors.request.use((cfg) => {
  const t = getStoredToken();
  if (t) {
    cfg.headers = cfg.headers ?? {};
    (cfg.headers as Record<string, string>).Authorization = `Bearer ${t}`;
  }
  return cfg;
});

let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(h: () => void) {
  onUnauthorized = h;
}

http.interceptors.response.use(
  (r) => r,
  (err: AxiosError) => {
    if (err.response?.status === 401) {
      setStoredToken(null);
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

// ---------- Auth ----------
export const auth = {
  login: async (email: string, password: string): Promise<LoginResponse> => {
    const { data } = await http.post<LoginResponse>('/api/v1/auth/login', { email, password });
    return data;
  },
  logout: async (): Promise<void> => {
    await http.post('/api/v1/auth/logout');
  },
  me: async (): Promise<User> => {
    const { data } = await http.get<User>('/api/v1/auth/me');
    return data;
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
  create: async (payload: { slug: string; name: string }): Promise<Organization> => {
    const { data } = await http.post<Organization>('/api/v1/orgs', payload);
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
    const { data } = await http.get<RoleDef[]>('/api/v1/rbac/roles');
    return data;
  },
  permissions: async (): Promise<Permission[]> => {
    const { data } = await http.get<Permission[]>('/api/v1/rbac/permissions');
    return data;
  },
  assignRole: async (userId: string, payload: { roleCode: string; orgId?: string }) => {
    await http.post(`/api/v1/rbac/users/${userId}/roles`, payload);
  },
};

// ---------- Plans ----------
export const plans = {
  list: async (): Promise<Plan[]> => {
    const { data } = await http.get<Plan[] | Paged<Plan>>('/api/v1/plans');
    return Array.isArray(data) ? data : data.items;
  },
  get: async (id: string): Promise<Plan> => {
    const { data } = await http.get<Plan>(`/api/v1/plans/${id}`);
    return data;
  },
  create: async (payload: Partial<Plan>): Promise<Plan> => {
    const { data } = await http.post<Plan>('/api/v1/plans', payload);
    return data;
  },
  update: async (id: string, patch: Partial<Plan>): Promise<Plan> => {
    const { data } = await http.patch<Plan>(`/api/v1/plans/${id}`, patch);
    return data;
  },
  setPermissions: async (id: string, permissions: string[]) => {
    await http.post(`/api/v1/plans/${id}/permissions`, { permissions });
  },
  setFeatures: async (id: string, features: { key: string; value: unknown }[]) => {
    await http.post(`/api/v1/plans/${id}/features`, { features });
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
      overrides?: SubscriptionOverride[];
    },
  ): Promise<Subscription> => {
    const { data } = await http.post<Subscription>(
      `/api/v1/orgs/${orgId}/subscriptions`,
      payload,
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
  setOverrides: async (id: string, overrides: SubscriptionOverride[]) => {
    await http.post(`/api/v1/subscriptions/${id}/overrides`, { overrides });
  },
};

// ---------- Licenses ----------
export const licenses = {
  issue: async (subId: string, payload?: { ttlDays?: number; notes?: string }): Promise<License> => {
    const { data } = await http.post<License>(`/api/v1/subscriptions/${subId}/licenses`, payload ?? {});
    return data;
  },
  listForSubscription: async (subId: string): Promise<License[]> => {
    const { data } = await http.get<License[] | Paged<License>>(
      `/api/v1/licenses?subscriptionId=${encodeURIComponent(subId)}`,
    );
    return Array.isArray(data) ? data : data.items;
  },
  list: async (params: Record<string, string | number | undefined> = {}): Promise<License[]> => {
    const q = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== '') q.set(k, String(v));
    });
    const { data } = await http.get<License[] | Paged<License>>(`/api/v1/licenses?${q.toString()}`);
    return Array.isArray(data) ? data : data.items;
  },
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
    const { data } = await http.get<SigningKey[]>('/api/v1/admin/keys');
    return data;
  },
  rotate: async (): Promise<SigningKey> => {
    const { data } = await http.post<SigningKey>('/api/v1/admin/keys/rotate');
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
export const sso = {
  get: async (orgId: string): Promise<SsoConfig | null> => {
    try {
      const { data } = await http.get<SsoConfig>(`/api/v1/orgs/${orgId}/sso`);
      return data;
    } catch (e) {
      if (axios.isAxiosError(e) && e.response?.status === 404) return null;
      throw e;
    }
  },
  save: async (orgId: string, payload: SsoConfig): Promise<SsoConfig> => {
    const { data } = await http.post<SsoConfig>(`/api/v1/orgs/${orgId}/sso`, payload);
    return data;
  },
};

// ---------- API keys ----------
export const apiKeys = {
  list: async (orgId: string): Promise<ApiKey[]> => {
    const { data } = await http.get<ApiKey[] | Paged<ApiKey>>(`/api/v1/orgs/${orgId}/api-keys`);
    return Array.isArray(data) ? data : data.items;
  },
  create: async (
    orgId: string,
    payload: { name: string; scopes: string[]; expiresAt?: string },
  ): Promise<ApiKey> => {
    const { data } = await http.post<ApiKey>(`/api/v1/orgs/${orgId}/api-keys`, payload);
    return data;
  },
  remove: async (orgId: string, id: string) => {
    await http.delete(`/api/v1/orgs/${orgId}/api-keys/${id}`);
  },
};
