import { useRef, useState } from 'react';
import * as api from '../api/cafe';
import { Button, Card, ToastStack, useToasts } from '../components/ui';

/**
 * Excel import (admin-only route).
 * The backend returns a structured ExcelImportResult: every row is reported as
 * imported / duplicate / failed with row-level reasons. Import is ID-independent —
 * any 'id' column in the sheet is ignored (database identity only).
 */
const MAX_BYTES = 5 * 1024 * 1024;

export default function ExcelImportPage(): JSX.Element {
  const { toasts, push, dismiss } = useToasts();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [result, setResult] = useState<api.ExcelImportResult | null>(null);

  const pickFile = (f: File | null): void => {
    setResult(null);
    setProgress(0);
    if (!f) return;
    const name = f.name.toLowerCase();
    if (!name.endsWith('.xlsx') && !name.endsWith('.xls')) {
      push('err', 'Only .xls and .xlsx files are accepted.');
      return;
    }
    if (f.size > MAX_BYTES) {
      push('err', 'File is larger than the 5 MB limit.');
      return;
    }
    setFile(f);
  };

  const upload = async (): Promise<void> => {
    if (!file) return;
    setUploading(true);
    setProgress(15);
    // Indeterminate-feel progress: the fetch API gives no upload progress events,
    // so we animate towards 90% and complete on response (honest UI, no fake numbers).
    const timer = setInterval(() => setProgress((p) => Math.min(p + 7, 90)), 180);
    try {
      const res = await api.uploadExcel(file);
      setProgress(100);
      setResult(res);
      if (res.parsed) {
        push('ok', res.message);
      } else {
        push('err', res.message);
      }
    } catch (err) {
      setProgress(0);
      push('err', err instanceof Error ? err.message : 'Upload failed.');
    } finally {
      clearInterval(timer);
      setUploading(false);
    }
  };

  const retry = (): void => {
    setFile(null);
    setResult(null);
    setProgress(0);
    inputRef.current?.click();
  };

  return (
    <>
      <Card title="Import products from Excel">
        <p className="muted" style={{ marginTop: 0 }}>
          Required columns: <span className="kbd">name</span>, <span className="kbd">price</span>,{' '}
          <span className="kbd">category_name</span>. Optional: <span className="kbd">description</span>,{' '}
          <span className="kbd">status</span> (<code>available</code>/<code>unavailable</code> — normalized to the
          database contract). Any <span className="kbd">id</span> column is <strong>ignored</strong>: products always
          receive database-generated ids. Missing categories are created automatically. Max 5 MB / 5000 rows.
        </p>

        <div
          className="card"
          style={{ borderStyle: 'dashed', textAlign: 'center', padding: 34, cursor: 'pointer' }}
          role="button"
          tabIndex={0}
          aria-label="Choose Excel file"
          onClick={() => inputRef.current?.click()}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click();
          }}
          onDragOver={(e) => e.preventDefault()}
          onDrop={(e) => {
            e.preventDefault();
            pickFile(e.dataTransfer.files?.[0] ?? null);
          }}
        >
          <div style={{ fontSize: 34, marginBottom: 8 }} aria-hidden="true">📥</div>
          {file ? (
            <>
              <strong>{file.name}</strong>
              <div className="muted">{(file.size / 1024).toFixed(0)} KB — click to choose a different file</div>
            </>
          ) : (
            <>
              <strong>Drop an .xls / .xlsx file here</strong>
              <div className="muted">or click to browse</div>
            </>
          )}
          <input
            ref={inputRef}
            type="file"
            accept=".xls,.xlsx"
            style={{ display: 'none' }}
            aria-hidden="true"
            tabIndex={-1}
            onChange={(e) => pickFile(e.target.files?.[0] ?? null)}
          />
        </div>

        {(uploading || progress > 0) && (
          <div className="mt-2" role="status" aria-live="polite">
            <div className="progressbar" aria-hidden="true">
              <div style={{ width: `${progress}%` }} />
            </div>
            <p className="muted mt-1">{uploading ? 'Uploading and processing…' : progress === 100 ? 'Done' : ''}</p>
          </div>
        )}

        <div className="row mt-2">
          <Button variant="primary" onClick={() => void upload()} loading={uploading} disabled={!file}>
            Upload &amp; import
          </Button>
          {result && (
            <Button onClick={retry}>Choose another file</Button>
          )}
        </div>
      </Card>

      {result && (
        <div className="card mt-2">
          <h2 className="card-title">Import result — {result.fileName}</h2>
          {result.parsed ? (
            <>
              <div className="stat-grid" style={{ marginBottom: 12 }}>
                <div className="card stat-card"><div className="stat-value" style={{ color: 'var(--success)' }}>{result.importedCount}</div><div className="stat-label">Imported</div></div>
                <div className="card stat-card"><div className="stat-value" style={{ color: 'var(--accent-strong)' }}>{result.duplicateCount}</div><div className="stat-label">Duplicates skipped</div></div>
                <div className="card stat-card"><div className="stat-value" style={{ color: 'var(--danger-strong)' }}>{result.failedCount}</div><div className="stat-label">Failed rows</div></div>
                <div className="card stat-card"><div className="stat-value">{result.categoriesCreated}</div><div className="stat-label">Categories created</div></div>
              </div>
              <p className="muted">
                {result.totalRows} data row{result.totalRows === 1 ? '' : 's'} processed
                {result.idColumnIgnored ? ' · sheet id column ignored (database ids assigned)' : ''}
              </p>
              {result.errors.length > 0 && (
                <div className="table-wrap mt-1">
                  <table className="data">
                    <thead>
                      <tr><th>Row</th><th>Field</th><th>Problem</th></tr>
                    </thead>
                    <tbody>
                      {result.errors.map((e, i) => (
                        <tr key={i}>
                          <td>{e.row}</td>
                          <td><span className="badge badge-danger">{e.field}</span></td>
                          <td>{e.reason}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          ) : (
            <p style={{ color: 'var(--danger-strong)', fontWeight: 650 }}>{result.message}</p>
          )}
        </div>
      )}

      <ToastStack toasts={toasts} dismiss={dismiss} />
    </>
  );
}
