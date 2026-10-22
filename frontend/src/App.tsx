import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { useAuth } from './auth/useAuth';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import DashboardPage from './pages/DashboardPage';
import ProductsPage from './pages/ProductsPage';
import CategoriesPage from './pages/CategoriesPage';
import UsersPage from './pages/UsersPage';
import BillingPage from './pages/BillingPage';
import BillsPage from './pages/BillsPage';
import ExcelImportPage from './pages/ExcelImportPage';
import ChangePasswordPage from './pages/ChangePasswordPage';
import NotFoundPage from './pages/NotFoundPage';
import GuestHomePage from './pages/GuestHomePage';
import MenuPage from './pages/MenuPage';
import MyOrdersPage from './pages/MyOrdersPage';
import AnalyticsPage from './pages/AnalyticsPage';
import CustomersPage from './pages/CustomersPage';
import CampaignsPage from './pages/CampaignsPage';
import AutomationPage from './pages/AutomationPage';

function RequireAuth({ children, adminOnly = false }: { children: JSX.Element; adminOnly?: boolean }): JSX.Element {
  const { token, isAdmin } = useAuth();
  const location = useLocation();
  if (!token) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  if (adminOnly && !isAdmin) {
    // Non-admins (customers) are pointed at the guest experience, not the staff console.
    return <Navigate to="/" replace />;
  }
  return children;
}

export default function App(): JSX.Element {
  return (
    <AuthProvider>
      <Routes>
        {/* ------------------------------------------------ public guest surface */}
        <Route path="/" element={<GuestHomePage />} />
        <Route path="/menu" element={<MenuPage />} />

        {/* customer order history — any signed-in role */}
        <Route
          path="my-orders"
          element={
            <RequireAuth>
              <MyOrdersPage />
            </RequireAuth>
          }
        />

        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />

        {/* ------------- authenticated app: management = admin-only, account = all roles */}
        <Route
          element={
            <ProtectedRoute>
              <Layout />
            </ProtectedRoute>
          }
        >
          <Route
            path="dashboard"
            element={
              <RequireAuth adminOnly>
                <DashboardPage />
              </RequireAuth>
            }
          />
          <Route
            path="billing"
            element={
              <RequireAuth adminOnly>
                <BillingPage />
              </RequireAuth>
            }
          />
          <Route
            path="bills"
            element={
              <RequireAuth adminOnly>
                <BillsPage />
              </RequireAuth>
            }
          />
          <Route
            path="products"
            element={
              <RequireAuth adminOnly>
                <ProductsPage />
              </RequireAuth>
            }
          />
          <Route
            path="categories"
            element={
              <RequireAuth adminOnly>
                <CategoriesPage />
              </RequireAuth>
            }
          />
          <Route
            path="users"
            element={
              <RequireAuth adminOnly>
                <UsersPage />
              </RequireAuth>
            }
          />
          <Route
            path="excel-import"
            element={
              <RequireAuth adminOnly>
                <ExcelImportPage />
              </RequireAuth>
            }
          />
          <Route
            path="change-password"
            element={
              <RequireAuth>
                <ChangePasswordPage />
              </RequireAuth>
            }
          />
          <Route
            path="analytics"
            element={
              <RequireAuth adminOnly>
                <AnalyticsPage />
              </RequireAuth>
            }
          />
          <Route
            path="customers"
            element={
              <RequireAuth adminOnly>
                <CustomersPage />
              </RequireAuth>
            }
          />
          <Route
            path="campaigns"
            element={
              <RequireAuth adminOnly>
                <CampaignsPage />
              </RequireAuth>
            }
          />
          <Route
            path="automation"
            element={
              <RequireAuth adminOnly>
                <AutomationPage />
              </RequireAuth>
            }
          />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </AuthProvider>
  );
}
