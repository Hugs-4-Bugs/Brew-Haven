import { Fragment } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { Button } from './ui';

interface NavItem {
  to: string;
  label: string;
  ico: string;
  adminOnly?: boolean;
  end?: boolean;
}

/**
 * App shell (sidebar + topbar) — role-aware navigation:
 *  - Everyone gets "Customer view" (guest home + menu) and Account.
 *  - Only admins see the Operations/Manage management sections.
 * Normal registered users are CUSTOMERS — never labelled "Staff".
 */

const NAV_CUSTOMER: NavItem[] = [
  { to: '/', label: 'Home', ico: '🏠', end: true },
  { to: '/menu', label: 'Browse Menu', ico: '📖' },
];

const NAV_MAIN: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', ico: '📊', adminOnly: true },
  { to: '/billing', label: 'New Bill (POS)', ico: '🧾', adminOnly: true },
  { to: '/bills', label: 'Bills', ico: '📄', adminOnly: true },
];

const NAV_MANAGE: NavItem[] = [
  { to: '/products', label: 'Products', ico: '🍽️', adminOnly: true },
  { to: '/categories', label: 'Categories', ico: '🏷️', adminOnly: true },
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
    items.map((item) => (
      <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
        <span className="nav-ico" aria-hidden="true">{item.ico}</span>
        {item.label}
      </NavLink>
    ));

  /** A section renders only when the current role can see at least one of its links. */
  const renderSection = (label: string, items: NavItem[]) => {
    const visible = items.filter((i) => !i.adminOnly || isAdmin);
    if (visible.length === 0) return null;
    return (
      <Fragment key={label}>
        <div className="nav-section">{label}</div>
        {renderLinks(visible)}
      </Fragment>
    );
  };

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
          {renderSection('Customer view', NAV_CUSTOMER)}
          {renderSection('Operations', NAV_MAIN)}
          {renderSection('Manage', NAV_MANAGE)}
          {renderSection('Account', NAV_ACCOUNT)}
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
            <span className={`role-chip ${isAdmin ? '' : 'user'}`}>{isAdmin ? 'Admin' : 'Customer'}</span>
          </div>
        </header>
        <main className="content" id="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
