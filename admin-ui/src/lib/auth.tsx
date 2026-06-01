import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { auth, getStoredToken, setStoredToken, setUnauthorizedHandler } from './api';
import type { User } from './types';

interface AuthCtx {
  user: User | null;
  accessToken: string | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  hasPermission: (perm: string) => boolean;
}

const Ctx = createContext<AuthCtx | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => getStoredToken());
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState<boolean>(!!getStoredToken());

  const refresh = useCallback(async () => {
    if (!getStoredToken()) {
      setUser(null);
      setLoading(false);
      return;
    }
    try {
      const me = await auth.me();
      setUser(me);
    } catch {
      setStoredToken(null);
      setToken(null);
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setToken(null);
      setUser(null);
    });
    refresh();
  }, [refresh]);

  const login = useCallback(async (email: string, password: string) => {
    const res = await auth.login(email, password);
    setStoredToken(res.accessToken);
    setToken(res.accessToken);
    setUser(res.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await auth.logout();
    } catch {
      // ignore
    }
    setStoredToken(null);
    setToken(null);
    setUser(null);
  }, []);

  const hasPermission = useCallback(
    (perm: string) => {
      if (!user) return false;
      const perms = user.permissions ?? [];
      if (perms.includes('*') || perms.includes(perm)) return true;
      // role-based fallback: SUPER_ADMIN gets everything
      return (user.roles ?? []).some((r) => r.code === 'SUPER_ADMIN');
    },
    [user],
  );

  const value = useMemo<AuthCtx>(
    () => ({ user, accessToken: token, loading, login, logout, refresh, hasPermission }),
    [user, token, loading, login, logout, refresh, hasPermission],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth must be used inside AuthProvider');
  return v;
}
