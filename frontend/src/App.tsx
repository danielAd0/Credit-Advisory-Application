import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { features, type Role } from './api';
import { AuthProvider } from './auth/AuthContext';
import RequireAuth from './auth/RequireAuth';
import { RequireRole } from './auth/RequireRole';
import Layout from './components/Layout';
import ChangePasswordPage from './pages/ChangePasswordPage';
import ChartsPage from './pages/ChartsPage';
import AuditLogPage from './pages/AuditLogPage';
import AddClientPage from './pages/AddClientPage';
import ClientDetailPage from './pages/ClientDetailPage';
import ClientsPage from './pages/ClientsPage';
import AddConsultantPage from './pages/AddConsultantPage';
import ConsultantsPage from './pages/ConsultantsPage';
import Dashboard from './pages/Dashboard';
import EditClientPage from './pages/EditClientPage';
import ImportPage from './pages/ImportPage';
import LoginPage from './pages/LoginPage';
import ManagersPage from './pages/ManagersPage';
import EmployersPage from './pages/EmployersPage';
import ProductQuotationsPage from './pages/ProductQuotationsPage';
import ProductsPage from './pages/ProductsPage';
import ConsultantActionsPage from './pages/ConsultantActionsPage';
import SalesHistoryPage from './pages/SalesHistoryPage';
import ReportsPage from './pages/ReportsPage';
import ReassignPage from './pages/ReassignPage';
import TwoStepPage from './pages/TwoStepPage';

const managers: Role[] = ['ADMIN', 'MANAGER'];

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="login" element={<LoginPage />} />
          <Route element={<RequireAuth />}>
            <Route path="two-step" element={<TwoStepPage />} />
            <Route path="change-password" element={<ChangePasswordPage />} />
            <Route element={<Layout />}>
              <Route index element={<Dashboard />} />
              <Route path="clients" element={<ClientsPage />} />
              <Route
                path="clients/new"
                element={
                  <RequireRole roles={['CONSULTANT']}>
                    <AddClientPage />
                  </RequireRole>
                }
              />
              <Route path="clients/:id" element={<ClientDetailPage />} />
              <Route
                path="clients/:id/edit"
                element={
                  <RequireRole roles={['CONSULTANT']}>
                    <EditClientPage />
                  </RequireRole>
                }
              />
              <Route
                path="consultants"
                element={
                  <RequireRole roles={managers}>
                    <ConsultantsPage />
                  </RequireRole>
                }
              />
              <Route
                path="consultants/new"
                element={
                  <RequireRole roles={['ADMIN']}>
                    <AddConsultantPage />
                  </RequireRole>
                }
              />
              <Route
                path="managers"
                element={
                  <RequireRole roles={['ADMIN']}>
                    <ManagersPage />
                  </RequireRole>
                }
              />
              <Route
                path="audit-log"
                element={
                  <RequireRole roles={['ADMIN']}>
                    <AuditLogPage />
                  </RequireRole>
                }
              />
              <Route
                path="charts"
                element={
                  <RequireRole roles={managers}>
                    <ChartsPage />
                  </RequireRole>
                }
              />
              <Route path="reports" element={<ReportsPage />} />
              <Route
                path="consultant-actions"
                element={
                  <RequireRole roles={['CONSULTANT']}>
                    <ConsultantActionsPage />
                  </RequireRole>
                }
              />
              <Route path="sales-history" element={<SalesHistoryPage />} />
              <Route path="products" element={<ProductsPage />} />
              <Route path="employers" element={<EmployersPage />} />
              <Route
                path="branches"
                element={
                  <RequireRole roles={managers}>
                    <ProductQuotationsPage />
                  </RequireRole>
                }
              />
              {/* The old address, from before product quotations were renamed to branches. */}
              <Route path="product-quotations" element={<Navigate to="/branches" replace />} />
              <Route
                path="reassign"
                element={
                  <RequireRole roles={managers}>
                    <ReassignPage />
                  </RequireRole>
                }
              />
              {features.import && (
                <Route
                  path="import"
                  element={
                    <RequireRole roles={managers}>
                      <ImportPage />
                    </RequireRole>
                  }
                />
              )}
              <Route
                path="*"
                element={
                  <div className="panel">
                    <h1>Page not found</h1>
                  </div>
                }
              />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
