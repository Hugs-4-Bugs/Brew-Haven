import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import * as api from '../api/cafe';
import { useAuth } from '../auth/useAuth';
import { Button, Card, EmptyState, ErrorState, LoadingState, Modal, ToastStack, useEntrance, useToasts } from '../components/ui';

export default function CategoriesPage(): JSX.Element {
  const { isAdmin } = useAuth();
  const { toasts, push, dismiss } = useToasts();
  const [categories, setCategories] = useState<api.Category[] | null>(null);
  const [products, setProducts] = useState<api.ProductWrapper[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [filterWithProducts, setFilterWithProducts] = useState(false);
  const [editing, setEditing] = useState<{ id?: number; name: string } | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [c, p] = await Promise.all([api.getCategories(), api.getProducts()]);
      setCategories(c);
      setProducts(p);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load categories.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEntrance('.card, table.data tr', [categories?.length]);

  /** Nested product visibility: products per category from the real product list. */
  const productsByCategory = useMemo(() => {
    const map = new Map<number, api.ProductWrapper[]>();
    for (const p of products) {
      if (p.categoryId == null) continue;
      const list = map.get(p.categoryId) ?? [];
      list.push(p);
      map.set(p.categoryId, list);
    }
    return map;
  }, [products]);

  const visible = useMemo(() => {
    if (!categories) return [];
    if (!filterWithProducts) return categories;
    return categories.filter((c) => (productsByCategory.get(c.id) ?? []).some((p) => p.status === 'true'));
  }, [categories, filterWithProducts, productsByCategory]);

  const save = async (e: FormEvent): Promise<void> => {
    e.preventDefault();
    if (!editing) return;
    const name = editing.name.trim();
    if (!name) {
      push('err', 'Category name cannot be empty.');
      return;
    }
    setSaving(true);
    try {
      const message = editing.id
        ? await api.updateCategory(editing.id, name)
        : await api.addCategory(name);
      push('ok', message);
      setEditing(null);
      await load();
    } catch (err) {
      // backend: "Item Already Exists" (400), "Category id doesn't exist" (400), etc.
      push('err', err instanceof Error ? err.message : 'Save failed.');
    } finally {
      setSaving(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  return (
    <>
      <Card>
        <div className="spread">
          <label className="row" style={{ cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={filterWithProducts}
              onChange={(e) => setFilterWithProducts(e.target.checked)}
            />
            Only categories with available products
            <span className="muted">(mirrors backend filterValue=true)</span>
          </label>
          {isAdmin && (
            <Button variant="primary" onClick={() => setEditing({ name: '' })}>
              ＋ Add category
            </Button>
          )}
        </div>
      </Card>

      <div className="mt-2">
        {!categories ? (
          <LoadingState label="Loading categories…" />
        ) : visible.length === 0 ? (
          <Card>
            <EmptyState
              ico="🏷️"
              title={categories.length === 0 ? 'No categories yet' : 'No categories with available products'}
              hint={categories.length === 0 ? 'Create your first category to start organizing the menu.' : 'Toggle the filter off to see all categories.'}
              action={isAdmin ? <Button size="sm" variant="primary" onClick={() => setEditing({ name: '' })}>Add category</Button> : undefined}
            />
          </Card>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Category</th>
                  <th>Products</th>
                  <th>Available</th>
                  {isAdmin && <th style={{ width: 110 }}>Actions</th>}
                </tr>
              </thead>
              <tbody>
                {visible.map((c) => {
                  const items = productsByCategory.get(c.id) ?? [];
                  const available = items.filter((p) => p.status === 'true').length;
                  return (
                    <tr key={c.id}>
                      <td><strong>{c.name}</strong></td>
                      <td>
                        {items.length === 0 ? (
                          <span className="badge badge-off">No products</span>
                        ) : (
                          <span className="badge badge-warn">{items.length} product{items.length > 1 ? 's' : ''}</span>
                        )}
                        {items.length > 0 && (
                          <div className="muted mt-1">{items.slice(0, 4).map((p) => p.name).join(', ')}{items.length > 4 ? ` +${items.length - 4} more` : ''}</div>
                        )}
                      </td>
                      <td>{available}</td>
                      {isAdmin && (
                        <td>
                          <Button size="sm" onClick={() => setEditing({ id: c.id, name: c.name })}>Rename</Button>
                        </td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {editing && (
        <Modal title={editing.id ? 'Rename category' : 'Add category'} onClose={() => setEditing(null)}>
          <form onSubmit={save}>
            <div className="field">
              <label htmlFor="cat-name">Category name</label>
              <input
                id="cat-name"
                className="input"
                value={editing.name}
                onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                autoFocus
                required
              />
            </div>
            <div className="modal-actions">
              <Button variant="ghost" onClick={() => setEditing(null)}>Cancel</Button>
              <Button variant="primary" type="submit" loading={saving}>
                {editing.id ? 'Save' : 'Create'}
              </Button>
            </div>
          </form>
        </Modal>
      )}

      <ToastStack toasts={toasts} dismiss={dismiss} />
    </>
  );
}
