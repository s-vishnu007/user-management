import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/lib/auth';
import { PageLoader } from './ui/Spinner';
import type { ReactNode } from 'react';

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const loc = useLocation();
  // Wait for the cookie-based /me bootstrap to settle before deciding (otherwise a post-SSO or
  // reloaded session would be bounced to /login before /me resolves).
  if (!ready) return <PageLoader />;
  if (!user) {
    return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  }
  return <>{children}</>;
}
