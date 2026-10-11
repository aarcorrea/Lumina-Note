import { useState } from 'react';

import type { LicensePayload } from '@/services/luminaCloud';
import { useLicenseStore } from '@/stores/useLicenseStore';

/**
 * License paste / view / remove panel. Standalone — no dependency on
 * AISettingsModal. Mounted by C10 (Account tab).
 */
export function LicenseSettings(): JSX.Element {
  const status = useLicenseStore((s) => s.status);
  const payload = useLicenseStore((s) => s.payload);
  const setLicense = useLicenseStore((s) => s.setLicense);
  const clearLicense = useLicenseStore((s) => s.clearLicense);

  const [draft, setDraft] = useState('');
  const [confirmingRemove, setConfirmingRemove] = useState(false);

  async function handleVerify() {
    const trimmed = draft.trim();
    if (!trimmed) return;
    await setLicense(trimmed);
  }

  async function handleRemove() {
    await clearLicense();
    setConfirmingRemove(false);
    setDraft('');
  }

  if (status === 'valid' && payload) {
    return (
      <section className="space-y-4" aria-labelledby="license-heading">
        <header>
          <h2 id="license-heading" className="text-base font-medium">
            Metis Cloud license
          </h2>
        </header>

        <ValidLicenseSummary payload={payload} />

        {confirmingRemove ? (
          <div className="flex items-center gap-2 text-sm" role="alertdialog" aria-label="Confirm remove">
            <span className="text-muted-foreground">
              Remove this license? Cloud features will stop working until you paste it again.
            </span>
            <button
              type="button"
              onClick={handleRemove}
              className="rounded border border-destructive/40 px-2 py-1 text-destructive hover:bg-destructive/10"
            >
              Remove
            </button>
            <button
              type="button"
              onClick={() => setConfirmingRemove(false)}
              className="rounded border border-border px-2 py-1 hover:bg-muted"
            >
              Cancel
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setConfirmingRemove(true)}
            className="text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
          >
            Remove license
          </button>
        )}
      </section>
    );
  }

  return (
    <section className="space-y-4" aria-labelledby="license-heading">
      <header className="space-y-1">
        <h2 id="license-heading" className="text-base font-medium">
          Metis Cloud license
        </h2>
        <p className="text-sm text-muted-foreground">
          Paste the license you received by email. Verification happens locally — no network call required.
        </p>
      </header>

      <label className="block space-y-2">
        <span className="text-sm font-medium">License token</span>
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="eyJ…(payload).…(signature)"
          rows={3}
          spellCheck={false}
          autoComplete="off"
          aria-label="License token"
          className="ui-input min-h-20 font-mono text-xs"
        />
      </label>

      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleVerify}
          disabled={status === 'loading' || draft.trim().length === 0}
          className="rounded bg-primary px-3 py-1.5 text-sm text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {status === 'loading' ? 'Verifying…' : 'Verify'}
        </button>

        <StatusLine status={status} />
      </div>
    </section>
  );
}

function StatusLine({ status }: { status: ReturnType<typeof useLicenseStore.getState>['status'] }): JSX.Element | null {
  if (status === 'loading') {
    return (
      <span role="status" className="text-sm text-muted-foreground">
        Verifying…
      </span>
    );
  }
  if (status === 'invalid') {
    return (
      <span role="alert" className="text-sm text-destructive">
        Could not verify this license. Check the token and try again.
      </span>
    );
  }
  return null;
}

function ValidLicenseSummary({ payload }: { payload: LicensePayload }): JSX.Element {
  return (
    <dl className="space-y-2 text-sm">
      <Row label="Email" value={payload.email} />
      <Row label="SKU" value={payload.sku} />
      <Row label="Expires" value={formatExpiry(payload.expires_at)} />
      {payload.features.length > 0 && (
        <div className="flex items-baseline gap-3">
          <dt className="w-20 shrink-0 text-muted-foreground">Features</dt>
          <dd className="flex flex-wrap gap-1.5">
            {payload.features.map((flag) => (
              <span
                key={flag}
                className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-foreground"
              >
                {flag}
              </span>
            ))}
          </dd>
        </div>
      )}
    </dl>
  );
}

function Row({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <div className="flex items-baseline gap-3">
      <dt className="w-20 shrink-0 text-muted-foreground">{label}</dt>
      <dd className="font-mono text-xs">{value}</dd>
    </div>
  );
}

function formatExpiry(expiresAt: string | null): string {
  if (expiresAt === null) return 'Lifetime';
  const ms = Date.parse(expiresAt);
  if (!Number.isFinite(ms)) return expiresAt;
  return new Date(ms).toISOString().slice(0, 10);
}
