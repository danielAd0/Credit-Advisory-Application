import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { features, type Role } from './api';
import { AuthProvider } from './auth/AuthContext';
import RequireAuth from './auth/RequireAuth';
import { RequireRole } from './auth/RequireRole';
import Layout from './components/Layout';
import ChangePasswordPage from './pages/ChangePasswordPage';
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
import EmployersPage from './pages/EmployersPage';
import ProductQuotationsPage from './pages/ProductQuotationsPage';
import ProductsPage from './pages/ProductsPage';
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
              <Route path="clients/new" element={<AddClientPage />} />
              <Route path="clients/:id" element={<ClientDetailPage />} />
              <Route path="clients/:id/edit" element={<EditClientPage />} />
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
                path="audit-log"
                element={
                  <RequireRole roles={['ADMIN']}>
                    <AuditLogPage />
                  </RequireRole>
                }
              />
              <Route path="reports" element={<ReportsPage />} />
              <Route path="products" element={<ProductsPage />} />
              <Route path="employers" element={<EmployersPage />} />
              <Route
                path="product-quotations"
                element={
                  <RequireRole roles={managers}>
                    <ProductQuotationsPage />
                  </RequireRole>
                }
              />
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
