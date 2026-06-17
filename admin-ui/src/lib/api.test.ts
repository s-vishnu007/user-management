import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  apiKeys,
  decodeSsoProvider,
  http,
  licenses,
  newIdempotencyKey,
  normalizeApiKey,
  plans,
  sso,
  subscriptions,
} from './api';
import type { ApiKeyDto, SsoProviderDto } from './types';

function mockResponse(data: unknown) {
  return { data, headers: {} as Record<string, string> };
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('plans client — P0-2 field-name contract', () => {
  it('setPermissions sends { permissionCodes }, never { permissions }', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue(mockResponse({}));
    await plans.setPermissions('plan-1', ['license.read', 'license.issue']);
    expect(post).toHaveBeenCalledTimes(1);
    const [url, body] = post.mock.calls[0];
    expect(url).toBe('/api/v1/plans/plan-1/permissions');
    expect(body).toEqual({ permissionCodes: ['license.read', 'license.issue'] });
    expect(body).not.toHaveProperty('permissions');
  });

  it('setFeatures sends features as a JSON object (Map), not an array', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue(mockResponse({}));
    await plans.setFeatures('plan-1', { max_users: 50, beta: true });
    const [, body] = post.mock.calls[0];
    expect(body).toEqual({ features: { max_users: 50, beta: true } });
    expect(Array.isArray((body as { features: unknown }).features)).toBe(false);
  });

  it('create sends an Idempotency-Key header', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue(mockResponse({}));
    await plans.create({ code: 'pro', name: 'Pro', permissions: [], features: {} });
    const config = post.mock.calls[0][2] as { headers: Record<string, string> };
    expect(config.headers['Idempotency-Key']).toBeTruthy();
  });
});

describe('sso client — list-of-providers contract', () => {
  it('create posts { type, config } (config is an object)', async () => {
    const dto: SsoProviderDto = {
      id: 's1',
      orgId: 'o1',
      type: 'OIDC',
      config: '{"issuer":"https://idp"}',
      enabled: true,
    };
    const post = vi.spyOn(http, 'post').mockResolvedValue(mockResponse(dto));
    await sso.create('o1', { type: 'OIDC', config: { issuer: 'https://idp', enabled: true } });
    const [url, body] = post.mock.calls[0];
    expect(url).toBe('/api/v1/orgs/o1/sso');
    expect((body as { type: string }).type).toBe('OIDC');
    expect(typeof (body as { config: unknown }).config).toBe('object');
  });

  it('decodeSsoProvider parses the JSON config string into an object', () => {
    const view = decodeSsoProvider({
      id: 's1',
      orgId: 'o1',
      type: 'SAML',
      config: '{"metadataUrl":"https://idp/meta","enabled":true}',
      enabled: true,
    });
    expect(view.config.metadataUrl).toBe('https://idp/meta');
    expect(view.type).toBe('SAML');
  });

  it('decodeSsoProvider tolerates malformed JSON without throwing (no white-screen)', () => {
    const view = decodeSsoProvider({
      id: 's1',
      orgId: 'o1',
      type: 'SAML',
      config: 'not-json{',
      enabled: false,
    });
    expect(view.config).toEqual({});
  });
});

describe('apiKeys client — scopes parsing & plaintext surfacing', () => {
  it('normalizeApiKey parses a JSON-string scopes field into an array', () => {
    const dto: ApiKeyDto = {
      id: 'k1',
      name: 'ci',
      keyPrefix: 'cp_live_abc',
      scopes: '["license.read","license.issue"]',
      createdAt: '2026-01-01T00:00:00Z',
    };
    const k = normalizeApiKey(dto);
    expect(Array.isArray(k.scopes)).toBe(true);
    expect(k.scopes).toEqual(['license.read', 'license.issue']);
    expect(k.prefix).toBe('cp_live_abc');
  });

  it('normalizeApiKey tolerates a malformed scopes string (no .map crash)', () => {
    const dto: ApiKeyDto = {
      id: 'k1',
      name: 'ci',
      keyPrefix: 'cp_live_abc',
      scopes: 'garbage',
      createdAt: '2026-01-01T00:00:00Z',
    };
    const k = normalizeApiKey(dto);
    expect(Array.isArray(k.scopes)).toBe(true);
  });

  it('create surfaces the one-time plaintext from the CreateResponse.key field', async () => {
    vi.spyOn(http, 'post').mockResolvedValue(
      mockResponse({
        id: 'k1',
        name: 'ci',
        key: 'cp_live_FULLPLAINTEXT',
        keyPrefix: 'cp_live_FUL',
        scopes: ['license.read'],
        createdAt: '2026-01-01T00:00:00Z',
      }),
    );
    const k = await apiKeys.create('o1', { name: 'ci', scopes: ['license.read'] });
    expect(k.plaintext).toBe('cp_live_FULLPLAINTEXT');
    expect(k.prefix).toBe('cp_live_FUL');
    expect(k.scopes).toEqual(['license.read']);
  });

  it('list normalizes each row (JSON-string scopes -> arrays)', async () => {
    vi.spyOn(http, 'get').mockResolvedValue(
      mockResponse([
        { id: 'k1', name: 'a', keyPrefix: 'p1', scopes: '["a"]', createdAt: '2026-01-01T00:00:00Z' },
      ]),
    );
    const out = await apiKeys.list('o1');
    expect(out[0].scopes).toEqual(['a']);
  });
});

describe('licenses client — subscription-scoped contract', () => {
  it('listForSubscription always passes subscriptionId', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue(mockResponse([]));
    await licenses.listForSubscription('sub-9');
    expect(get.mock.calls[0][0]).toContain('subscriptionId=sub-9');
  });

  it('list requires a subscriptionId param and forwards it', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue(mockResponse([]));
    await licenses.list({ subscriptionId: 'sub-1', status: 'ACTIVE' });
    expect(get.mock.calls[0][0]).toContain('subscriptionId=sub-1');
    expect(get.mock.calls[0][0]).toContain('status=ACTIVE');
  });
});

describe('licenses client — per-user org flow', () => {
  it('issueForOrg posts to /orgs/{orgId}/licenses with the grant payload + Idempotency-Key', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue(mockResponse({ jti: 'lic_1' }));
    await licenses.issueForOrg('org-1', {
      email: 'jane@acme.com',
      roleCodes: ['ORG_ADMIN'],
      permissions: ['license.read', 'usage.read'],
      ttlDays: 365,
      trial: false,
    });
    const [url, body, config] = post.mock.calls[0];
    expect(url).toBe('/api/v1/orgs/org-1/licenses');
    expect(body).toMatchObject({
      email: 'jane@acme.com',
      roleCodes: ['ORG_ADMIN'],
      permissions: ['license.read', 'usage.read'],
      ttlDays: 365,
    });
    expect((config as { headers: Record<string, string> }).headers['Idempotency-Key']).toBeTruthy();
  });

  it('listForOrg targets the org-scoped endpoint and forwards status', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue(mockResponse([]));
    await licenses.listForOrg('org-9', 'ACTIVE');
    expect(get.mock.calls[0][0]).toBe('/api/v1/orgs/org-9/licenses?status=ACTIVE');
  });

  it('assignableGrants reads the grant catalog endpoint', async () => {
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValue(mockResponse({ permissions: [], roles: [] }));
    const grants = await licenses.assignableGrants();
    expect(get.mock.calls[0][0]).toBe('/api/v1/licenses/assignable-grants');
    expect(grants).toEqual({ permissions: [], roles: [] });
  });
});

describe('subscriptions client — idempotent create', () => {
  it('create sends an Idempotency-Key header and honors an explicit key', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue(mockResponse({}));
    await subscriptions.create(
      'o1',
      { planId: 'p1', startsAt: 'a', endsAt: 'b', seats: 5 },
      'fixed-key-123',
    );
    const config = post.mock.calls[0][2] as { headers: Record<string, string> };
    expect(config.headers['Idempotency-Key']).toBe('fixed-key-123');
  });
});

describe('newIdempotencyKey', () => {
  it('returns a non-empty unique-ish string', () => {
    const a = newIdempotencyKey();
    const b = newIdempotencyKey();
    expect(a).toBeTruthy();
    expect(a).not.toBe(b);
  });
});
