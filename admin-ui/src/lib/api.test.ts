import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  apiKeys,
  decodeSsoProvider,
  http,
  licenses,
  newIdempotencyKey,
  normalizeApiKey,
  sso,
} from './api';
import type { ApiKeyDto, SsoProviderDto } from './types';

function mockResponse(data: unknown) {
  return { data, headers: {} as Record<string, string> };
}

afterEach(() => {
  vi.restoreAllMocks();
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

describe('newIdempotencyKey', () => {
  it('returns a non-empty unique-ish string', () => {
    const a = newIdempotencyKey();
    const b = newIdempotencyKey();
    expect(a).toBeTruthy();
    expect(a).not.toBe(b);
  });
});
