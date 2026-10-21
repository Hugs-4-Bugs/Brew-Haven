import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import * as api from '../api/cafe';
import { useAuth } from '../auth/useAuth';
import { Button, Card, EmptyState, ErrorState, LoadingState, Modal, useEntrance, useToasts, ToastStack } from '../components/ui';
import { statusLabel } from '../utils/format';

interface ProductForm {
  id?: string;
  name: string;
  description: string;
  price: string;
  categoryId: string;
  status: 'true' | 'false';
}

const emptyForm: ProductForm = { name: '', description: '', price: '', categoryId: '', status: 'true' };

export default function ProductsPage(): JSX.Element {
  const { isAdmin } = useAuth();
  const { toasts, push, dismiss } = useToasts();
  const [products, setProducts] = useState<api.ProductWrapper[] | null>(null);
  const [categories, setCategories] = useState<api.Category[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [statusFilter, setStatusFilter] = useState('all');
  const [editing, setEditing] = useState<ProductForm | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<api.ProductWrapper | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [p, c] = await Promise.all([api.getProducts(), api.getCategories()]);
      setProducts(p);
      setCategories(c);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load products.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEntrance('.stat-card, .card, table.data tr', [products?.length]);

  const filtered = useMemo(() => {
    if (!products) return [];
    return products.filter((p) => {
      if (search && !p.name?.toLowerCase().includes(search.toLowerCase())) return false;
      if (categoryFilter !== 'all' && String(p.categoryId) !== categoryFilter) return false;
      if (statusFilter !== 'all' && p.status !== statusFilter) return false;
      return true;
    });
  }, [products, search, categoryFilter, statusFilter]);

  const openAdd = (): void => setEditing({ ...emptyForm, categoryId: categories[0] ? String(categories[0].id) : '' });
  const openEdit = (p: api.ProductWrapper): void =>
    setEditing({
      id: String(p.id),
      name: p.name,
      description: p.description ?? '',
      price: String(p.price ?? ''),
      categoryId: String(p.categoryId ?? ''),
      status: (p.status as 'true' | 'false') ?? 'true',
    });

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    if (!editing.name.trim() || !editing.price || !editing.categoryId) {
      push('err', 'Name, price and category are required.');
      return;
    }
    const priceNum = Number(editing.price);
    if (!Number.isFinite(priceNum) || priceNum < 0 || !Number.isInteger(priceNum)) {
      push('err', 'Price must be a non-negative whole number (backend stores integer prices).');
      return;
    }
    setSaving(true);
    try {
      const payload = {
        name: editing.name.trim(),
        description: editing.description.trim(),
        price: editing.price,
        categoryId: editing.categoryId,
        status: editing.status,
      };
      const message = editing.id
        ? await api.updateProduct({ id: editing.id, ...payload })
        : await api.addProduct(payload);
      push('ok', message);
      setEditing(null);
      await load();
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Save failed.');
    } finally {
      setSaving(false);
    }
  };

  const toggleStatus = async (p: api.ProductWrapper): Promise<void> => {
    try {
      const next = p.status === 'true' ? 'false' : 'true';
      const message = await api.updateProductStatus(p.id, next);
      push('ok', message);
      await load();
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Status update failed.');
    }
  };

  const doDelete = async (): Promise<void> => {
    if (!confirmDelete) return;
    try {
      const message = await api.deleteProduct(confirmDelete.id);
      push('ok', message);
      setConfirmDelete(null);
      await load();
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Delete failed.');
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  return (
    <>
      <Card>
        <div className="spread">
          <div className="row">
            <input
              className="input"
              style={{ width: 220 }}
              type="search"
              placeholder="Search products…"
              aria-label="Search products"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <select className="select" style={{ width: 170 }} aria-label="Filter by category" value={categoryFilter} onChange={(e) => setCategoryFilter(e.target.value)}>
              <option value="all">All categories</option>
              {categories.map((c) => (
                <option key={c.id} value={String(c.id)}>{c.name}</option>
              ))}
            </select>
            <select className="select" style={{ width: 140 }} aria-label="Filter by status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
              <option value="all">Any status</option>
              <option value="true">Available</option>
              <option value="false">Unavailable</option>
            </select>
          </div>
          {isAdmin && (
            <Button variant="primary" onClick={openAdd}>
              ＋ Add product
            </Button>
          )}
        </div>
      </Card>

      <div className="mt-2">
        {!products ? (
          <LoadingState label="Loading products…" />
        ) : filtered.length === 0 ? (
          <Card>
            <EmptyState
              ico="🍽️"
              title={products.length === 0 ? 'No products yet' : 'No products match your filters'}
              hint={products.length === 0 ? 'Add your first product or import a menu via Excel.' : 'Try clearing the search or filters.'}
              action={isAdmin ? <Button size="sm" variant="primary" onClick={openAdd}>Add product</Button> : undefined}
            />
          </Card>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Category</th>
                  <th>Price</th>
                  <th>Status</th>
                  {isAdmin && <th style={{ width: 240 }}>Actions</th>}
                </tr>
              </thead>
              <tbody>
                {filtered.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <strong>{p.name}</strong>
                      {p.description && <div className="muted">{p.description}</div>}
                    </td>
                    <td>{p.categoryName ?? '—'}</td>
                    <td>₹{p.price?.toLocaleString?.() ?? p.price}</td>
                    <td>
                      <span className={`badge ${p.status === 'true' ? 'badge-ok' : 'badge-off'}`}>
                        {statusLabel(p.status)}
                      </span>
                    </td>
                    {isAdmin && (
                      <td>
                        <div className="row">
                          <Button size="sm" onClick={() => openEdit(p)}>Edit</Button>
                          <Button size="sm" variant="ghost" onClick={() => void toggleStatus(p)}>
                            {p.status === 'true' ? 'Mark unavailable' : 'Mark available'}
                          </Button>
                          <Button size="sm" variant="danger" onClick={() => setConfirmDelete(p)}>Delete</Button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {editing && (
        <Modal title={editing.id ? 'Edit product' : 'Add product'} onClose={() => setEditing(null)}>
          <form onSubmit={save}>
            <div className="field">
              <label htmlFor="pf-name">Name</label>
              <input id="pf-name" className="input" value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} required />
            </div>
            <div className="field">
              <label htmlFor="pf-desc">Description</label>
              <input id="pf-desc" className="input" value={editing.description} onChange={(e) => setEditing({ ...editing, description: e.target.value })} />
            </div>
            <div className="form-row">
              <div className="field">
                <label htmlFor="pf-price">Price (₹, integer)</label>
                <input id="pf-price" className="input" inputMode="numeric" value={editing.price} onChange={(e) => setEditing({ ...editing, price: e.target.value })} required />
              </div>
              <div className="field">
                <label htmlFor="pf-cat">Category</label>
                <select id="pf-cat" className="select" value={editing.categoryId} onChange={(e) => setEditing({ ...editing, categoryId: e.target.value })} required>
                  <option value="">Select category…</option>
                  {categories.map((c) => (
                    <option key={c.id} value={String(c.id)}>{c.name}</option>
                  ))}
                </select>
              </div>
            </div>
            <div className="field">
              <label htmlFor="pf-status">Status</label>
              <select id="pf-status" className="select" value={editing.status} onChange={(e) => setEditing({ ...editing, status: e.target.value as 'true' | 'false' })}>
                <option value="true">Available</option>
                <option value="false">Unavailable</option>
              </select>
            </div>
            <div className="modal-actions">
              <Button variant="ghost" onClick={() => setEditing(null)}>Cancel</Button>
              <Button variant="primary" type="submit" loading={saving}>
                {editing.id ? 'Save changes' : 'Add product'}
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {confirmDelete && (
        <Modal title="Delete product" onClose={() => setConfirmDelete(null)}>
          <p>
            Delete <strong>{confirmDelete.name}</strong>? This cannot be undone. Bills that already
            reference this product keep their stored line items.
          </p>
          <div className="modal-actions">
            <Button variant="ghost" onClick={() => setConfirmDelete(null)}>Cancel</Button>
            <Button variant="danger" onClick={() => void doDelete()}>Delete</Button>
          </div>
        </Modal>
      )}

      <ToastStack toasts={toasts} dismiss={dismiss} />
    </>
  );
}
