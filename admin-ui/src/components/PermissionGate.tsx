import type { ReactNode } from 'react';
import { useAuth } from '@/lib/auth';

export function PermissionGate({
  permission,
  permissions,
  any,
  fallback = null,
  children,
}: {
  permission?: string;
  permissions?: string[];
  any?: boolean;
  fallback?: ReactNode;
  children: ReactNode;
}) {
  const { hasPermission } = useAuth();
  const list = permissions ?? (permission ? [permission] : []);
  if (list.length === 0) return <>{children}</>;
  const ok = any ? list.some(hasPermission) : list.every(hasPermission);
  return <>{ok ? children : fallback}</>;
}
