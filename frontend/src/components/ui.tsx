import { useEffect, useState, type ReactNode, type ButtonHTMLAttributes } from 'react';
import { animateIn, countUp } from '../utils/motion';

/* ------------------------------------------------------------------ buttons */

type BtnVariant = 'primary' | 'default' | 'danger' | 'ghost';

export function Button({
  variant = 'default',
  size,
  loading = false,
  children,
  className = '',
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: BtnVariant; size?: 'sm'; loading?: boolean }): JSX.Element {
  const variantClass = variant === 'primary' ? 'btn-primary' : variant === 'danger' ? 'btn-danger' : variant === 'ghost' ? 'btn-ghost' : '';
  return (
    <button
      type="button"
      className={`btn ${variantClass} ${size === 'sm' ? 'btn-sm' : ''} ${className}`}
      disabled={rest.disabled || loading}
      {...rest}
    >
      {loading && <span className="spinner" style={{ width: 14, height: 14, borderWidth: 2 }} aria-hidden="true" />}
      {children}
    </button>
  );
}

/* ------------------------------------------------------------------ layout helpers */

export function Card({ title, children, className = '', style }: { title?: string; children: ReactNode; className?: string; style?: React.CSSProperties }): JSX.Element {
  return (
    <section className={`card ${className}`} style={style}>
      {title && <h2 className="card-title">{title}</h2>}
      {children}
    </section>
  );
}

export function StatCard({ label, value, ico, countTo }: { label: string; value: number | string; ico: string; countTo?: number }): JSX.Element {
  const ref = (el: HTMLDivElement | null) => {
    if (el && typeof countTo === 'number') {
      countUp(el, countTo);
    }
  };
  return (
    <div className="card stat-card">
      <div className="stat-ico" aria-hidden="true">{ico}</div>
      <div className="stat-value" ref={ref}>{typeof value === 'number' ? value.toLocaleString() : value}</div>
      <div className="stat-label">{label}</div>
    </div>
  );
}

/* ------------------------------------------------------------------ states */

export function LoadingState({ label = 'Loading…' }: { label?: string }): JSX.Element {
  return (
    <div className="state-box" role="status" aria-live="polite">
      <div className="spinner" aria-hidden="true" />
      <p className="mt-1">{label}</p>
    </div>
  );
}

export function EmptyState({ ico = '🗂️', title, hint, action }: { ico?: string; title: string; hint?: string; action?: ReactNode }): JSX.Element {
  return (
    <div className="state-box">
      <div className="state-ico" aria-hidden="true">{ico}</div>
      <h3>{title}</h3>
      {hint && <p>{hint}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }): JSX.Element {
  return (
    <div className="state-box" role="alert">
      <div className="state-ico" aria-hidden="true">⚠️</div>
      <h3>Something went wrong</h3>
      <p>{message}</p>
      {onRetry && (
        <div className="mt-2">
          <Button size="sm" onClick={onRetry}>Try again</Button>
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ modal */

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }): JSX.Element {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="modal-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ toasts */

export interface Toast {
  id: number;
  kind: 'ok' | 'err';
  text: string;
}

let toastId = 0;

export function useToasts(): { toasts: Toast[]; push: (kind: 'ok' | 'err', text: string) => void; dismiss: (id: number) => void } {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = (kind: 'ok' | 'err', text: string) => {
    const id = ++toastId;
    setToasts((prev) => [...prev, { id, kind, text }]);
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 5000);
  };
  const dismiss = (id: number) => setToasts((prev) => prev.filter((t) => t.id !== id));
  return { toasts, push, dismiss };
}

export function ToastStack({ toasts, dismiss }: { toasts: Toast[]; dismiss: (id: number) => void }): JSX.Element {
  return (
    <div className="toast-stack" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.kind === 'ok' ? 'toast-ok' : 'toast-err'}`} role="status">
          <span aria-hidden="true">{t.kind === 'ok' ? '✅' : '⛔'}</span>
          <span style={{ flex: 1 }}>{t.text}</span>
          <button className="btn-ghost btn-sm" onClick={() => dismiss(t.id)} aria-label="Dismiss notification">×</button>
        </div>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ entrance */

/** Runs the shared staggered entrance on mount. */
export function useEntrance(selector: string, deps: unknown[] = []): void {
  useEffect(() => {
    const raf = requestAnimationFrame(() => animateIn(selector));
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}
