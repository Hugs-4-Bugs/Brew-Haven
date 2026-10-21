import { Link } from 'react-router-dom';

export default function NotFoundPage(): JSX.Element {
  return (
    <div className="auth-wrap">
      <div className="card auth-card" style={{ textAlign: 'center' }}>
        <div className="auth-logo" aria-hidden="true">🤷</div>
        <h1 className="auth-title">Page not found</h1>
        <p className="auth-sub">The page you were looking for does not exist.</p>
        <Link to="/dashboard" className="btn btn-primary" style={{ display: 'inline-flex' }}>
          Go to dashboard
        </Link>
      </div>
    </div>
  );
}
