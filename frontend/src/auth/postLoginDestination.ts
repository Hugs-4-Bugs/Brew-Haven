/**
 * Where a user lands right after a successful login.
 * - Admins open the management dashboard.
 * - Normal customers open the guest (restaurant) experience — the homepage —
 *   not the staff console.
 * - A remembered "from" destination (e.g. /change-password) always wins.
 */
export function postLoginDestination(role: 'admin' | 'user' | null, from?: string | null): string {
  if (from && from !== '/login') return from;
  return role === 'admin' ? '/dashboard' : '/';
}
