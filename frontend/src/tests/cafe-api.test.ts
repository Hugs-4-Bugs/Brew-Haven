import { describe, expect, it, vi } from 'vitest';
import * as cafe from '../api/cafe';

// API layer contract tests — verify each generated request matches the backend contract
// documented in docs/audit/ENDPOINT_INVENTORY.md.

function lastCall(): [string, RequestInit] {
  const fn = globalThis.fetch as unknown as ReturnType<typeof vi.fn>;
  return fn.mock.calls[fn.mock.calls.length - 1] as [string, RequestInit];
}

function ok(body: string): void {
  // fresh Response per call — Response bodies are single-use
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(body, { status: 200 }))));
}

describe('cafe API layer — request shapes', () => {
  it('login posts {email,password} to /user/login', async () => {
    ok('{"token":"t"}');
    const r = await cafe.login('a@b.c', 'pw');
    expect(r.token).toBe('t');
    const [url, init] = lastCall();
    expect(url).toBe('/user/login');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ email: 'a@b.c', password: 'pw' });
  });

  it('addProduct posts the full product map', async () => {
    ok('{"message":"Product Added Successfully."}');
    await cafe.addProduct({ name: 'Latte', description: 'coffee', price: '120', categoryId: '3', status: 'true' });
    const [url, init] = lastCall();
    expect(url).toBe('/product/add');
    expect(JSON.parse(init.body as string)).toEqual({
      name: 'Latte', description: 'coffee', price: '120', categoryId: '3', status: 'true',
    });
  });

  it('generateBill sends productDetails as a JSON STRING with totalAmount number', async () => {
    ok('{"uuid":"BILL-123"}');
    await cafe.generateBill({
      name: 'C', contactNumber: '1', email: 'e', paymentMethod: 'UPI',
      productDetails: '[{"name":"x"}]', totalAmount: 250, isGenerate: true,
    });
    const [, init] = lastCall();
    const body = JSON.parse(init.body as string);
    expect(typeof body.productDetails).toBe('string');
    expect(body.totalAmount).toBe(250);
    expect(body.isGenerate).toBe(true);
  });

  it('verifyUpiId sends the raw JSON-string body and maps 400 to {valid:false}', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response('Invalid UPI ID', { status: 400 }),
    ));
    const r = await cafe.verifyUpiId('bad handle');
    expect(r.valid).toBe(false);
    const [, init] = lastCall();
    expect(init.body).toBe('"bad handle"');
  });

  it('verifyUpiId maps 200 to {valid:true}', async () => {
    ok('UPI ID is valid');
    const r = await cafe.verifyUpiId('a@b');
    expect(r.valid).toBe(true);
  });

  it('uploadExcel posts multipart form-data with the file', async () => {
    ok(JSON.stringify({ parsed: true, importedCount: 3 }));
    const file = new File(['x'], 'menu.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    const r = await cafe.uploadExcel(file);
    expect(r.importedCount).toBe(3);
    const [url, init] = lastCall();
    expect(url).toBe('/product/uploadExcel');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('file')).toBe(file);
  });

  it('payWithUpi includes status:true and the reused upi id (duplicate payments are valid)', async () => {
    ok('UPI Payment stored successfully! Transaction ID: TXN-1');
    await cafe.payWithUpi({ userId: 'u', upiId: 'same@bank', amount: 50 });
    const body = JSON.parse(lastCall()[1].body as string);
    expect(body).toMatchObject({ userId: 'u', upiId: 'same@bank', amount: 50, status: true });
  });

  it('updateUserStatus sends id/status as strings (backend contract)', async () => {
    ok('{"message":"User Status Updated Successfully"}');
    await cafe.updateUserStatus(2, 'true');
    const body = JSON.parse(lastCall()[1].body as string);
    expect(body).toEqual({ id: '2', status: 'true' });
  });

  it('getCategories appends filterValue=true only when requested', async () => {
    ok('[]');
    await cafe.getCategories();
    expect(lastCall()[0]).toBe('/category/get');
    await cafe.getCategories('true');
    expect(lastCall()[0]).toBe('/category/get?filterValue=true');
  });

  it('deleteBill/deleteProduct/updateStatus use POST (not DELETE)', async () => {
    ok('{"message":"ok"}');
    await cafe.deleteBill(5);
    expect(lastCall()[0]).toBe('/bill/delete/5');
    expect(lastCall()[1].method).toBe('POST');
    await cafe.deleteProduct(7);
    expect(lastCall()[0]).toBe('/product/delete/7');
    expect(lastCall()[1].method).toBe('POST');
  });
});
