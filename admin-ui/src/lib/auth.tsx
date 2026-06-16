import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { auth, setUnauthorizedHandler } from './api';
import type { LoginResponse, OrgMembership, RegisterResponse, User } from './types';

/** Result of a login attempt: either authenticated or an MFA challenge requiring a TOTP step. */
export type LoginResult =
  | { mfaRequired: false }
  | { mfaRequired: true; challenge: string; challengeExpiresAt?: string };

interface AuthCtx {
  user: User | null;
  permissions: string[];
  orgs: OrgMembership[];
  /** True only after the initial cookie-based bootstrap has settled. */
  ready: boolean;
  loading: boolean;
  login: (email: string, password: string) => Promise<LoginResult>;
  /** Self-service signup; the backend auto-logs-in, so this bootstraps identity on success. */
  register: (payload: {
    fullName: string;
    email: string;
    password: string;
    orgName: string;
  }) => Promise<RegisterResponse>;
  /** Completes an MFA login by exchanging the signed challenge + TOTP code for a session. */
  completeMfa: (challenge: string, code: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  hasPermission: (perm: string) => boolean;
}

const Ctx = createContext<AuthCtx | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [orgs, setOrgs] = useState<OrgMembership[]>([]);
  const [ready, setReady] = useState(false);

  const clear = useCallback(() => {
    setUser(null);
    setPermissions([]);
    setOrgs([]);
  }, []);

  // Bootstrap identity purely from the HttpOnly cp_session cookie. This is the ONLY way a
  // post-SSO browser (which only ever receives the cookie + an ?sso=success redirect) can become
  // authenticated, and it also restores a session across reloads without persisting any token in
  // JS-readable storage (finding P1-16 / P2 #32).
  const refresh = useCallback(async () => {
    try {
      const id = await auth.me();
      setUser(id.user);
      setPermissions(id.permissions);
      setOrgs(id.orgs);
    } catch {
      clear();
    } finally {
      setReady(true);
    }
  }, [clear]);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      clear();
    });
    void refresh();
  }, [refresh, clear]);

  const login = useCallback(async (email: string, password: string): Promise<LoginResult> => {
    const res: LoginResponse = await auth.login(email, password);
    if (res.mfaRequired) {
      return {
        mfaRequired: true,
        challenge: res.mfaChallenge ?? '',
        challengeExpiresAt: res.mfaChallengeExpiresAt,
      };
    }
    // Session cookie is set by the backend; load the unified identity envelope from /me so the
    // permission set (which is NOT on the login UserDto) is available for client-side gating.
    await refresh();
    return { mfaRequired: false };
  }, [refresh]);

  const register = useCallback(
    async (payload: { fullName: string; email: string; password: string; orgName: string }) => {
      const res = await auth.register(payload);
      // The backend set the cp_session cookie (auto-login); bootstrap the identity envelope so the
      // permission set is available, exactly like login().
      await refresh();
      return res;
    },
    [refresh],
  );

  const completeMfa = useCallback(async (challenge: string, code: string) => {
    await auth.mfaLogin(challenge, code);
    await refresh();
  }, [refresh]);

  const logout = useCallback(async () => {
    try {
      await auth.logout();
    } catch {
      // ignore — logout is best-effort client-side; the backend clears the cookie/denylists jti.
    }
    clear();
  }, [clear]);

  const hasPermission = useCallback(
    (perm: string) => {
      if (!user) return false;
      if (user.superAdmin) return true;
      // The /me authority set lists every granted permission code; a super-admin additionally
      // carries the literal "SUPER_ADMIN" authority (and "*" if the backend ever emits it).
      if (permissions.includes('SUPER_ADMIN') || permissions.includes('*')) return true;
      return permissions.includes(perm);
    },
    [user, permissions],
  );

  const value = useMemo<AuthCtx>(
    () => ({
      user,
      permissions,
      orgs,
      ready,
      loading: !ready,
      login,
      register,
      completeMfa,
      logout,
      refresh,
      hasPermission,
    }),
    [user, permissions, orgs, ready, login, register, completeMfa, logout, refresh, hasPermission],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth must be used inside AuthProvider');
  return v;
}
