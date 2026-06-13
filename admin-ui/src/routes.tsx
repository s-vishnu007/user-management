import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { ProtectedRoute } from './components/ProtectedRoute';
import { LoginPage } from './pages/LoginPage';
import { PasswordResetRequestPage } from './pages/PasswordResetRequestPage';
import { PasswordResetConfirmPage } from './pages/PasswordResetConfirmPage';
import { DashboardPage } from './pages/DashboardPage';
import { OrgsListPage } from './pages/OrgsListPage';
import { OrgDetailPage } from './pages/OrgDetailPage';
import { PlansListPage } from './pages/PlansListPage';
import { PlanEditPage } from './pages/PlanEditPage';
import { SubscriptionCreateWizard } from './pages/SubscriptionCreateWizard';
import { SubscriptionDetailPage } from './pages/SubscriptionDetailPage';
import { LicensesPage } from './pages/LicensesPage';
import { KeysPage } from './pages/KeysPage';
import { UsagePage } from './pages/UsagePage';
import { AuditPage } from './pages/AuditPage';
import { SsoConfigPage } from './pages/SsoConfigPage';
import { ApiKeysPage } from './pages/ApiKeysPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
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
        <Route path="/plans" element={<PlansListPage />} />
        <Route path="/plans/:planId/edit" element={<PlanEditPage />} />
        <Route path="/subscriptions/new" element={<SubscriptionCreateWizard />} />
        <Route path="/subscriptions/:subId" element={<SubscriptionDetailPage />} />
        <Route path="/subscriptions/:subId/usage" element={<UsagePage />} />
        <Route path="/licenses" element={<LicensesPage />} />
        <Route path="/keys" element={<KeysPage />} />
        <Route path="/audit" element={<AuditPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
