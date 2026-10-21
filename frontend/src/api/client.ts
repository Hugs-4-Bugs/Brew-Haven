/**
 * Central API client — the single place every network call goes through.
 *
 * Contract notes (verified against the live backend, see docs/audit/ENDPOINT_INVENTORY.md):
 *  - The backend frequently returns ResponseEntity<String> whose body is a JSON string
 *    (e.g. `{"token":"..."}`, `{"message":"..."}`) served as text/plain. We therefore
 *    ALWAYS read the body as text and attempt JSON.parse defensively.
 *  - Authentication failures surface as HTTP 403 (Spring's Http403ForbiddenEntryPoint —
 *    no 401 semantics exist for missing/invalid tokens) OR HTTP 401 with
 *    `{"message":"Unauthorized access"}` for service-level role checks. Both are mapped
 *    to ApiError; 403/401 on protected calls triggers session-expiry handling.
 *  - The JWT is kept in localStorage under 'cafe.token'.
 */

const TOKEN_KEY = 'cafe.token';

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* storage unavailable (private mode) — session lives in memory only */
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

/** Decodes the JWT payload (no signature verification — the backend verifies). */
export function decodeJwtRole(token: string): 'admin' | 'user' | null {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    const role = payload?.role;
    return role === 'admin' || role === 'user' ? role : null;
  } catch {
    return null;
  }
}

export function decodeJwtEmail(token: string): string | null {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return typeof payload?.sub === 'string' ? payload.sub : null;
  } catch {
    return null;
  }
}

export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

const BASE_URL = import.meta.env.VITE_API_BASE ?? '';

export type SessionExpiredHandler = () => void;
let onSessionExpired: SessionExpiredHandler | null = null;

/** The AuthContext registers here to react to 403/401 on any call. */
export function registerSessionExpiredHandler(handler: SessionExpiredHandler): void {
  onSessionExpired = handler;
}

function parseBody(text: string): unknown {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function extractMessage(body: unknown, status: number): string {
  if (body && typeof body === 'object') {
    const obj = body as Record<string, unknown>;
    if (typeof obj.message === 'string') return obj.message;
    if (typeof obj.error === 'string') return obj.error;
  }
  if (typeof body === 'string' && body.trim()) return body;
  return `Request failed (HTTP ${status})`;
}

/**
 * Like request(), but unwraps the backend's ubiquitous `{"message": "..."}` string
 * bodies into a plain string so callers never accidentally render an object.
 */
export async function requestMessage(
  path: string,
  options: { method?: string; body?: unknown; formData?: FormData; rawBody?: string } = {},
): Promise<string> {
  const result = await request<unknown>(path, options);
  if (typeof result === 'string') return result;
  if (result && typeof result === 'object') {
    const obj = result as Record<string, unknown>;
    if (typeof obj.message === 'string') return obj.message;
    if (typeof obj.token === 'string') return obj.token;
  }
  return JSON.stringify(result);
}

export async function request<T>(
  path: string,
  options: { method?: string; body?: unknown; formData?: FormData; rawBody?: string } = {},
): Promise<T> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  let body: string | FormData | undefined;

  if (options.formData) {
    body = options.formData; // browser sets multipart boundary
  } else if (options.rawBody !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = options.rawBody;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }

  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, { method: options.method ?? 'GET', headers, body });
  } catch {
    throw new ApiError('Cannot reach the server. Is the backend running?', 0);
  }

  const text = await res.text();
  const parsed = parseBody(text);

  if (!res.ok) {
    // 403 = backend entry point for missing/invalid token; 401 = service-level rejection.
    if ((res.status === 401 || res.status === 403) && token && path !== '/user/login') {
      onSessionExpired?.();
    }
    throw new ApiError(extractMessage(parsed, res.status), res.status);
  }
  return parsed as T;
}
