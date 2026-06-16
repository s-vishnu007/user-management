import { afterEach, describe, expect, it, vi } from 'vitest';
import { auth, http } from './api';
import type { MeResponse } from './types';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('auth.me — unified {user, permissions, orgs} contract', () => {
  it('maps the /me envelope (permissions/orgs live on the envelope, not the user)', async () => {
    const me: MeResponse = {
      user: {
        id: 'u1',
        email: 'admin@example.com',
        fullName: 'Ada Admin',
        status: 'ACTIVE',
        superAdmin: true,
      },
      orgs: [{ orgId: 'o1', slug: 'example', name: 'Example', role: 'OWNER' }],
      permissions: ['SUPER_ADMIN', 'org.read', 'plan.write'],
    };
    vi.spyOn(http, 'get').mockResolvedValue({ data: me, headers: {} });

    const id = await auth.me();
    expect(id.user.email).toBe('admin@example.com');
    // The permission set is the flat authority list from the envelope.
    expect(id.permissions).toContain('plan.write');
    expect(id.permissions).toContain('SUPER_ADMIN');
    expect(id.orgs[0].slug).toBe('example');
  });

  it('defaults permissions/orgs to empty arrays when absent', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { user: { id: 'u1', email: 'x@y.z' } },
      headers: {},
    });
    const id = await auth.me();
    expect(id.permissions).toEqual([]);
    expect(id.orgs).toEqual([]);
  });
});

describe('auth.login — MFA challenge contract', () => {
  it('passes through the mfaRequired challenge from /auth/login', async () => {
    vi.spyOn(http, 'post').mockResolvedValue({
      data: { mfaRequired: true, mfaChallenge: 'signed.jwt.challenge', mfaChallengeExpiresAt: 'soon' },
      headers: {},
    });
    const res = await auth.login('admin@example.com', 'pw');
    expect(res.mfaRequired).toBe(true);
    expect(res.mfaChallenge).toBe('signed.jwt.challenge');
  });

  it('mfaLogin posts the signed challenge + code to /auth/mfa/login', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: {}, headers: {} });
    await auth.mfaLogin('signed.jwt.challenge', '123456');
    const [url, body] = post.mock.calls[0];
    expect(url).toBe('/api/v1/auth/mfa/login');
    expect(body).toEqual({ challenge: 'signed.jwt.challenge', code: '123456' });
  });
});
