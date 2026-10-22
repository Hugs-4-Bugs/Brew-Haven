import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Layout from '../components/Layout';
import GuestNav from '../components/guest/GuestNav';
import { AuthProvider } from '../auth/AuthContext';
import { postLoginDestination } from '../auth/postLoginDestination';

const TOKEN_KEY = 'cafe.token';

/** Unsigned fake JWT — client.ts decodes the payload without verifying. */
function fakeJwt(role: 'admin' | 'user', email = 'who@cafe.local'): string {
  const part = (o: unknown) => btoa(JSON.stringify(o));
  return `${part({ alg: 'none', typ: 'JWT' })}.${part({ sub: email, role })}.sig`;
}

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/change-password']}>
      <AuthProvider>
        <Layout />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function renderGuestNav() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <GuestNav onEditPreferences={() => undefined} prefRecord={null} />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('postLoginDestination — role-aware landing', () => {
  it('sends admins to the management dashboard by default', () => {
    expect(postLoginDestination('admin', null)).toBe('/dashboard');
  });

  it('sends normal customers to the guest experience by default', () => {
    expect(postLoginDestination('user', null)).toBe('/');
    expect(postLoginDestination(null, undefined)).toBe('/');
  });

  it('honours a remembered destination (e.g. change-password)', () => {
    expect(postLoginDestination('user', '/change-password')).toBe('/change-password');
  });

  it('never loops back to the login page', () => {
    expect(postLoginDestination('user', '/login')).toBe('/');
    expect(postLoginDestination('admin', '/login')).toBe('/dashboard');
  });
});

describe('Layout — role-aware sidebar and role chip', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('labels a normal user as Customer (never Staff)', () => {
    localStorage.setItem(TOKEN_KEY, fakeJwt('user'));
    renderLayout();
    expect(screen.getByText('Customer')).toBeTruthy();
    expect(screen.queryByText('Staff')).toBeNull();
  });

  it('shows a customer only the customer view + account sections', () => {
    localStorage.setItem(TOKEN_KEY, fakeJwt('user'));
    renderLayout();
    expect(screen.getByRole('link', { name: 'Home' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Browse Menu' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Change Password' })).toBeTruthy();
    for (const management of ['Dashboard', 'New Bill (POS)', 'Bills', 'Products', 'Categories', 'Users', 'Excel Import']) {
      expect(screen.queryByRole('link', { name: management })).toBeNull();
    }
  });

  it('keeps the full management console for admins AND adds the customer view', () => {
    localStorage.setItem(TOKEN_KEY, fakeJwt('admin'));
    renderLayout();
    expect(screen.getByText('Admin')).toBeTruthy();
    for (const link of ['Home', 'Browse Menu', 'Dashboard', 'New Bill (POS)', 'Bills', 'Products', 'Categories', 'Users', 'Excel Import', 'Change Password']) {
      expect(screen.getByRole('link', { name: link })).toBeTruthy();
    }
  });
});

describe('GuestNav — role-aware signed-in CTA', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('offers customers "My account" instead of the management dashboard', () => {
    localStorage.setItem(TOKEN_KEY, fakeJwt('user'));
    renderGuestNav();
    const myAccount = screen.getByRole('link', { name: 'My account' });
    expect(myAccount.getAttribute('href')).toBe('/change-password');
    expect(screen.queryByRole('link', { name: 'Open dashboard' })).toBeNull();
  });

  it('keeps "Open dashboard" for admins', () => {
    localStorage.setItem(TOKEN_KEY, fakeJwt('admin'));
    renderGuestNav();
    expect(screen.getByRole('link', { name: 'Open dashboard' })).toBeTruthy();
  });
});
