import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { ProtectedRoute } from './components/ProtectedRoute';
import { LoginPage } from './pages/LoginPage';
import { SignupPage } from './pages/SignupPage';
import { VerifyEmailPage } from './pages/VerifyEmailPage';
import { PasswordResetRequestPage } from './pages/PasswordResetRequestPage';
import { PasswordResetConfirmPage } from './pages/PasswordResetConfirmPage';
import { DashboardPage } from './pages/DashboardPage';
import { OrgsListPage } from './pages/OrgsListPage';
import { OrgDetailPage } from './pages/OrgDetailPage';
import { LicensesPage } from './pages/LicensesPage';
import { KeysPage } from './pages/KeysPage';
import { AuditPage } from './pages/AuditPage';
import { SsoConfigPage } from './pages/SsoConfigPage';
import { ApiKeysPage } from './pages/ApiKeysPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      {/* Email-verification link lands here with ?token=...; the page POSTs it back. Public so an
          unauthenticated user (e.g. clicking the link on another device) can still verify. */}
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route path="/password-reset/request" element={<PasswordResetRequestPage />} />
      {/* Token is passed as a query param (?token=...), not in the path, to keep it out of
          referrer headers / server access logs / browser history path segments. */}
      <Route path="/password-reset/confirm" element={<PasswordResetConfirmPage />} />
      <Route
        element={
          <ProtectedRoute>
            <AppShell />
          </ProtectedRoute>
        }
      >
        <Route path="/" element={<DashboardPage />} />
        <Route path="/orgs" element={<OrgsListPage />} />
        <Route path="/orgs/:orgId" element={<OrgDetailPage />} />
        <Route path="/orgs/:orgId/sso" element={<SsoConfigPage />} />
        <Route path="/orgs/:orgId/api-keys" element={<ApiKeysPage />} />
        <Route path="/licenses" element={<LicensesPage />} />
        <Route path="/keys" element={<KeysPage />} />
        <Route path="/audit" element={<AuditPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
