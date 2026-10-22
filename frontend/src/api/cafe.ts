import { request, requestMessage, getToken } from './client';

// ------------------------------------------------------------------ types (backend contract)

export interface UserWrapper {
  id: number;
  name: string;
  contactNumber: string;
  email: string;
  status: string; // "true" | "false"
}

export interface Category {
  id: number;
  name: string;
  version?: number | null;
  products?: unknown;
}

export interface ProductWrapper {
  id: number;
  name: string;
  description?: string;
  price?: number;
  status?: string; // "true" | "false"
  categoryId?: number;
  categoryName?: string;
  /** Admin-curated signature flag (additive; null/undefined = not featured). */
  featured?: boolean | null;
}

export interface Bill {
  id: number;
  uuid: string;
  name: string;
  email: string;
  contactNumber: string;
  paymentMethod: string;
  total: number;
  productDetails: string; // JSON string of BillLine[]
  createdBy: string;
}

export interface BillLine {
  id?: number;
  name: string;
  category?: string;
  quantity: number;
  price: number;
  total: number;
}

export interface PaymentRecord {
  id: number;
  userId: string;
  paymentType: 'CARD' | 'UPI' | 'WALLET';
  provider?: string;
  transactionId?: string;
  amount: number;
  status: boolean;
  timestamp?: string;
  upiId?: string;
  walletType?: string;
  cardNumber?: string; // masked (****-****-****-1234)
  qrCode?: string;
}

export interface DashboardCounts {
  category: number;
  product: number;
  bill: number;
}

export interface ExcelImportResult {
  fileName: string | null;
  parsed: boolean;
  message: string;
  totalRows: number;
  importedCount: number;
  duplicateCount: number;
  failedCount: number;
  categoriesCreated: number;
  idColumnIgnored: boolean;
  errors: { row: number; field: string; reason: string }[];
}

// ------------------------------------------------------------------ auth

export interface LoginResult {
  token: string;
}

/** POST /user/login — body is a raw string containing JSON; both 200 and 400 carry messages. */
export async function login(email: string, password: string): Promise<LoginResult> {
  // The backend returns `{"token":"..."}` (200) or `{"message":"Wait for admin approval."|"Bad Credential."}` (400).
  // Errors are thrown as ApiError by request(); the message text is surfaced verbatim.
  return request<LoginResult>('/user/login', {
    method: 'POST',
    body: { email, password },
  });
}

export async function signup(payload: { name: string; contactNumber: string; email: string; password: string }): Promise<string> {
  return requestMessage('/user/signup', { method: 'POST', body: payload });
}

export async function forgotPassword(email: string): Promise<string> {
  return requestMessage('/user/forgotPassword', { method: 'POST', body: { email } });
}

export async function changePassword(oldPassword: string, newPassword: string): Promise<string> {
  return requestMessage('/user/changePassword', { method: 'POST', body: { oldPassword, newPassword } });
}

export async function checkToken(): Promise<string> {
  return request<string>('/user/checkToken');
}

// ------------------------------------------------------------------ users (admin)

export async function getAllUsers(): Promise<UserWrapper[]> {
  // non-admin: backend returns 401 + [] — surfaced as ApiError(401)
  return request<UserWrapper[]>('/user/get');
}

export async function updateUserStatus(id: number, status: 'true' | 'false'): Promise<string> {
  return requestMessage('/user/update', { method: 'POST', body: { id: String(id), status } });
}

// ------------------------------------------------------------------ categories

export async function getCategories(filterValue?: 'true'): Promise<Category[]> {
  const qs = filterValue ? `?filterValue=${filterValue}` : '';
  return request<Category[]>(`/category/get${qs}`);
}

export async function addCategory(name: string): Promise<string> {
  return requestMessage('/category/add', { method: 'POST', body: { name } });
}

export async function updateCategory(id: number, name: string): Promise<string> {
  return requestMessage('/category/update', { method: 'POST', body: { id: String(id), name } });
}

// ------------------------------------------------------------------ products

export async function getProducts(): Promise<ProductWrapper[]> {
  return request<ProductWrapper[]>('/product/get');
}

export async function addProduct(payload: {
  name: string;
  description: string;
  price: string;
  categoryId: string;
  status?: string;
}): Promise<string> {
  return requestMessage('/product/add', { method: 'POST', body: payload });
}

export async function updateProduct(payload: {
  id: string;
  name: string;
  description: string;
  price: string;
  categoryId: string;
  status: string;
}): Promise<string> {
  return requestMessage('/product/update', { method: 'POST', body: payload });
}

export async function deleteProduct(id: number): Promise<string> {
  return requestMessage(`/product/delete/${id}`, { method: 'POST' });
}

export async function updateProductStatus(id: number, status: 'true' | 'false'): Promise<string> {
  return requestMessage('/product/updateStatus', { method: 'POST', body: { id: String(id), status } });
}

// ------------------------------------------------------------------ bills

export async function getBills(): Promise<Bill[]> {
  return request<Bill[]>('/bill/getBills');
}

export async function generateBill(payload: {
  name: string;
  contactNumber: string;
  email: string;
  paymentMethod: string;
  productDetails: string; // JSON string
  totalAmount: number;
  isGenerate: boolean;
}): Promise<{ uuid: string }> {
  return request<{ uuid: string }>('/bill/generateReport', { method: 'POST', body: payload });
}

/** POST /bill/getPdf — browser-usable variant added by the backend fix (GET+body is unsendable from fetch). */
export async function downloadBillPdf(uuid: string): Promise<Blob> {
  const res = await fetch('/bill/getPdf', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
    },
    body: JSON.stringify({ uuid }),
  });
  if (!res.ok) {
    let message = `PDF download failed (HTTP ${res.status})`;
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      /* binary or empty */
    }
    throw new Error(message);
  }
  return res.blob();
}

export async function deleteBill(id: number): Promise<string> {
  return requestMessage(`/bill/delete/${id}`, { method: 'POST' });
}

// ------------------------------------------------------------------ dashboard

export async function getDashboardCounts(): Promise<DashboardCounts> {
  return request<DashboardCounts>('/dashboard/details');
}

// ------------------------------------------------------------------ payments

/** POST /api/payment/upi/verify — raw JSON string body ("id@bank" WITH quotes). */
export async function verifyUpiId(upiId: string): Promise<{ valid: boolean; message: string }> {
  try {
    const message = await requestMessage('/api/payment/upi/verify', {
      method: 'POST',
      rawBody: JSON.stringify(upiId),
    });
    return { valid: true, message: String(message) };
  } catch (err) {
    // 400 carries the invalid message
    const message = err instanceof Error ? err.message : 'Invalid UPI ID';
    return { valid: false, message };
  }
}

/** POST /api/payment/upi/pay — persists a UPI payment; duplicate (reused) UPI ids are valid. */
export async function payWithUpi(payload: {
  userId: string;
  upiId: string;
  amount: number;
  provider?: string;
}): Promise<string> {
  return requestMessage('/api/payment/upi/pay', {
    method: 'POST',
    body: { ...payload, status: true },
  });
}

/** POST /api/payment/upi/qr-pay — returns a self-contained data:image/png;base64 URI
 *  encoding the SERVER-CONFIGURED merchant payee (never a caller-chosen account). */
export async function getUpiQr(amount: number): Promise<string> {
  return request<string>('/api/payment/upi/qr-pay', {
    method: 'POST',
    body: { amount },
  });
}

/** POST /api/payment/wallet/pay — returns the wallet deep-link URL (redirect contract). */
export async function payWithWallet(walletType: string, amount: number): Promise<string> {
  return request<string>('/api/payment/wallet/pay', {
    method: 'POST',
    body: { walletType, amount },
  });
}

/** POST /api/payment/process — card payment; the server masks the card number. */
export async function payWithCard(payload: {
  userId: string;
  amount: number;
  provider?: string;
  cardNumber: string;
  cardHolderName: string;
  cardExpiry: string;
  cvv?: string;
}): Promise<PaymentRecord> {
  return request<PaymentRecord>('/api/payment/process', {
    method: 'POST',
    body: { ...payload, status: true, paymentType: 'CARD' },
  });
}

/** POST /api/payment/process with a plain Payment (no card/UPI fields) — the backend
 *  derives paymentType=WALLET and stores walletType. Server generates txn id + timestamp. */
export async function recordWalletPayment(payload: {
  userId: string;
  amount: number;
  walletType: string;
  provider?: string;
}): Promise<PaymentRecord> {
  return request<PaymentRecord>('/api/payment/process', {
    method: 'POST',
    body: { ...payload, status: true },
  });
}

export async function getAllPayments(): Promise<PaymentRecord[]> {
  return request<PaymentRecord[]>('/api/payment/all');
}

// ------------------------------------------------------------------ excel import

/** POST /product/uploadExcel (admin only) — structured result with per-row outcomes. */
export async function uploadExcel(file: File): Promise<ExcelImportResult> {
  const formData = new FormData();
  formData.append('file', file);
  return request<ExcelImportResult>('/product/uploadExcel', { method: 'POST', formData });
}
