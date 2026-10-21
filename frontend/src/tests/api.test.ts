import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { ApiError, clearToken, decodeJwtEmail, decodeJwtRole, getToken, request, setToken } from '../api/client';

// ------------------------------------------------------------------ helpers

function mockFetch(status: number, body: string): ReturnType<typeof vi.fn> {
  const fn = vi.fn().mockResolvedValue(
    new Response(body, { status, headers: { 'Content-Type': 'text/plain' } }),
  );
  vi.stubGlobal('fetch', fn);
  return fn;
}

beforeEach(() => {
  clearToken();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

// ------------------------------------------------------------------ token storage

describe('token storage', () => {
  it('stores and clears the JWT', () => {
    setToken('abc');
    expect(getToken()).toBe('abc');
    clearToken();
    expect(getToken()).toBeNull();
  });
});

// ------------------------------------------------------------------ JWT decoding

describe('JWT payload decoding', () => {
  // header.payload.signature with base64url payload {"role":"admin","sub":"a@b.c"}
  const token = `eyJhbGciOiJIUzI1NiJ9.${btoa('{"role":"admin","sub":"admin@cafe.local"}')}.sig`;

  it('extracts the role', () => {
    expect(decodeJwtRole(token)).toBe('admin');
  });

  it('extracts the email (sub)', () => {
    expect(decodeJwtEmail(token)).toBe('admin@cafe.local');
  });

  it('returns null for garbage tokens', () => {
    expect(decodeJwtRole('not-a-jwt')).toBeNull();
    expect(decodeJwtEmail('')).toBeNull();
  });
});

// ------------------------------------------------------------------ request()

describe('request()', () => {
  it('parses JSON-string bodies served as text/plain (backend contract)', async () => {
    const fn = mockFetch(200, '{"token":"tok123"}');
    const result = await request<{ token: string }>('/user/login', { method: 'POST', body: { email: 'a', password: 'b' } });
    expect(result.token).toBe('tok123');
    const [, init] = fn.mock.calls[0] as [string, RequestInit];
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json' });
  });

  it('sends the Authorization header when a token is stored', async () => {
    setToken('tok123');
    const fn = mockFetch(200, '[]');
    await request('/product/get');
    const [, init] = fn.mock.calls[0] as [string, RequestInit];
    expect(init.headers).toMatchObject({ Authorization: 'Bearer tok123' });
  });

  it('extracts the message from error bodies', async () => {
    mockFetch(400, '{"message":"Wait for admin approval."}');
    await expect(request('/user/login', { method: 'POST', body: {} })).rejects.toThrow('Wait for admin approval.');
  });

  it('maps non-JSON error bodies to a readable message', async () => {
    mockFetch(500, 'Internal Server Error');
    await expect(request('/x')).rejects.toThrow('Internal Server Error');
  });

  it('wraps network failures in a friendly ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(request('/x')).rejects.toThrow('Cannot reach the server');
  });

  it('produces ApiError with status codes', async () => {
    mockFetch(403, '{"message":"Unauthorized access"}');
    const err = await request('/product/get').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(403);
    expect((err as ApiError).message).toBe('Unauthorized access');
  });

  it('passes raw string bodies verbatim (UPI verify contract)', async () => {
    const fn = mockFetch(200, 'UPI ID is valid');
    await request('/api/payment/upi/verify', { method: 'POST', rawBody: '"prabhat@okhdfc"' });
    const [, init] = fn.mock.calls[0] as [string, RequestInit];
    expect(init.body).toBe('"prabhat@okhdfc"');
  });
});
