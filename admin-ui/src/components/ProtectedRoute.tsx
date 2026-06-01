import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/lib/auth';
import { PageLoader } from './ui/Spinner';
import type { ReactNode } from 'react';

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { user, accessToken, loading } = useAuth();
  const loc = useLocation();
  if (loading) return <PageLoader />;
  if (!accessToken || !user) {
    return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  }
  return <>{children}</>;
}
