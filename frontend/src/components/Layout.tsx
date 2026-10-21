import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { Button } from './ui';

interface NavItem {
  to: string;
  label: string;
  ico: string;
  adminOnly?: boolean;
}

const NAV_MAIN: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', ico: '📊' },
  { to: '/billing', label: 'New Bill (POS)', ico: '🧾' },
  { to: '/bills', label: 'Bills', ico: '📄' },
];

const NAV_MANAGE: NavItem[] = [
  { to: '/products', label: 'Products', ico: '🍽️' },
  { to: '/categories', label: 'Categories', ico: '🏷️' },
  { to: '/users', label: 'Users', ico: '👥', adminOnly: true },
  { to: '/excel-import', label: 'Excel Import', ico: '📥', adminOnly: true },
];

const NAV_ACCOUNT: NavItem[] = [{ to: '/change-password', label: 'Change Password', ico: '🔑' }];

const TITLES: Record<string, string> = {
  '/dashboard': 'Dashboard',
  '/billing': 'New Bill',
  '/bills': 'Bills',
  '/products': 'Products',
  '/categories': 'Categories',
  '/users': 'User Administration',
  '/excel-import': 'Excel Import',
  '/change-password': 'Change Password',
};

export default function Layout(): JSX.Element {
  const { isAdmin, email, logout } = useAuth();
  const location = useLocation();
  const title = TITLES[location.pathname] ?? 'Cafe Management';

  const renderLinks = (items: NavItem[]) =>
    items
      .filter((i) => !i.adminOnly || isAdmin)
      .map((item) => (
        <NavLink key={item.to} to={item.to} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
          <span className="nav-ico" aria-hidden="true">{item.ico}</span>
          {item.label}
        </NavLink>
      ));

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-logo" aria-hidden="true">☕</div>
          <span>
            Cafe<span style={{ color: 'var(--accent)' }}>MS</span>
          </span>
        </div>
        <nav aria-label="Main navigation">
          <div className="nav-section">Operations</div>
          {renderLinks(NAV_MAIN)}
          <div className="nav-section">Manage</div>
          {renderLinks(NAV_MANAGE)}
          <div className="nav-section">Account</div>
          {renderLinks(NAV_ACCOUNT)}
        </nav>
        <div style={{ marginTop: 'auto', paddingTop: 16 }}>
          <Button variant="ghost" size="sm" onClick={() => logout()} aria-label="Log out">
            ⏻ Log out
          </Button>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <h1>{title}</h1>
          <div className="topbar-user">
            <span aria-hidden="true">👤</span>
            <span>{email ?? 'unknown'}</span>
            <span className={`role-chip ${isAdmin ? '' : 'user'}`}>{isAdmin ? 'Admin' : 'Staff'}</span>
          </div>
        </header>
        <main className="content" id="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
