import { useCallback, useEffect, useState } from 'react';
import * as api from '../api/cafe';
import { Button, Card, EmptyState, ErrorState, LoadingState, ToastStack, useEntrance, useToasts } from '../components/ui';

/**
 * User administration (admin-only route — non-admins are redirected by the router and
 * the backend additionally enforces role checks server-side).
 */
export default function UsersPage(): JSX.Element {
  const { toasts, push, dismiss } = useToasts();
  const [users, setUsers] = useState<api.UserWrapper[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setUsers(await api.getAllUsers());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load users.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEntrance('.card, table.data tr', [users?.length]);

  const setStatus = async (user: api.UserWrapper, status: 'true' | 'false'): Promise<void> => {
    try {
      const message = await api.updateUserStatus(user.id, status);
      push('ok', message);
      await load();
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Update failed.');
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  return (
    <>
      <Card>
        <p className="muted" style={{ margin: 0 }}>
          Accounts are created via sign-up with role <strong>user</strong> and are active
          immediately. Disabling an account blocks sign-in immediately.
        </p>
      </Card>

      <div className="mt-2">
        {!users ? (
          <LoadingState label="Loading users…" />
        ) : users.length === 0 ? (
          <Card>
            <EmptyState ico="👥" title="No user accounts yet" hint="Signed-up users appear here automatically." />
          </Card>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Email</th>
                  <th>Contact</th>
                  <th>Status</th>
                  <th style={{ width: 170 }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id}>
                    <td><strong>{u.name}</strong></td>
                    <td className="mono">{u.email}</td>
                    <td>{u.contactNumber}</td>
                    <td>
                      <span className={`badge ${u.status === 'true' ? 'badge-ok' : 'badge-off'}`}>
                        {u.status === 'true' ? 'Approved' : 'Pending / disabled'}
                      </span>
                    </td>
                    <td>
                      {u.status === 'true' ? (
                        <Button size="sm" variant="danger" onClick={() => void setStatus(u, 'false')}>
                          Disable
                        </Button>
                      ) : (
                        <Button size="sm" variant="primary" onClick={() => void setStatus(u, 'true')}>
                          Approve
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <ToastStack toasts={toasts} dismiss={dismiss} />
    </>
  );
}
