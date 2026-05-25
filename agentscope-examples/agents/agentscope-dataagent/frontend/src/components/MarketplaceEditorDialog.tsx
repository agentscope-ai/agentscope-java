import React, { useMemo, useState } from 'react';
import {
  MarketplaceSummary,
  MarketplaceType,
  MarketplaceWriteRequest,
  TestConnectionResult,
  testMarketplaceTransient,
} from '../api/marketplaces';

interface Props {
  mode: 'create' | 'edit';
  initial?: MarketplaceSummary;
  onSave: (req: MarketplaceWriteRequest) => Promise<void>;
  onCancel: () => void;
}

interface FieldDef {
  key: string;
  label: string;
  placeholder?: string;
  required?: boolean;
  secret?: boolean;
  hint?: string;
}

const TYPE_FIELDS: Record<MarketplaceType, FieldDef[]> = {
  git: [
    {
      key: 'remoteUrl',
      label: 'Remote URL',
      placeholder: 'https://github.com/org/skills.git',
      required: true,
      hint: 'HTTPS or SSH; auth uses your system git credentials.',
    },
    { key: 'branch', label: 'Branch', placeholder: 'main (optional)' },
  ],
  nacos: [
    { key: 'serverAddr', label: 'Server', placeholder: 'nacos.example.com:8848', required: true },
    {
      key: 'namespaceId',
      label: 'Namespace',
      placeholder: 'public (optional)',
      hint: 'Defaults to "public" if omitted.',
    },
    { key: 'username', label: 'Username', placeholder: 'optional' },
    { key: 'password', label: 'Password', placeholder: 'optional', secret: true },
    { key: 'accessKey', label: 'Access Key', placeholder: 'optional' },
    { key: 'secretKey', label: 'Secret Key', placeholder: 'optional', secret: true },
  ],
};

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.55)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 60,
};

const dialogStyle: React.CSSProperties = {
  background: '#ffffff',
  borderRadius: 12,
  padding: 24,
  width: '90%',
  maxWidth: 560,
  maxHeight: '85vh',
  overflowY: 'auto',
  boxShadow: '0 20px 60px rgba(15,23,42,0.25)',
};

const labelStyle: React.CSSProperties = {
  fontSize: '0.78rem',
  fontWeight: 600,
  color: '#334155',
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  display: 'block',
  marginBottom: 4,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  border: '1px solid #cbd5e1',
  borderRadius: 8,
  fontSize: '0.9rem',
  boxSizing: 'border-box',
  fontFamily: 'inherit',
};

const hintStyle: React.CSSProperties = {
  fontSize: '0.75rem',
  color: '#64748b',
  marginTop: 4,
};

const primaryButton: React.CSSProperties = {
  padding: '8px 16px',
  borderRadius: 8,
  border: '1px solid #6366f1',
  background: '#6366f1',
  color: '#ffffff',
  fontSize: '0.88rem',
  fontWeight: 600,
  cursor: 'pointer',
};

const secondaryButton: React.CSSProperties = {
  padding: '8px 16px',
  borderRadius: 8,
  border: '1px solid #cbd5e1',
  background: '#ffffff',
  color: '#334155',
  fontSize: '0.88rem',
  fontWeight: 500,
  cursor: 'pointer',
};

function stringOrEmpty(v: unknown): string {
  return typeof v === 'string' ? v : v == null ? '' : String(v);
}

export default function MarketplaceEditorDialog({ mode, initial, onSave, onCancel }: Props) {
  const isEdit = mode === 'edit';
  const [id, setId] = useState(initial?.id ?? '');
  const [type, setType] = useState<MarketplaceType>(
    ((initial?.type as MarketplaceType) ?? 'git') as MarketplaceType,
  );
  const initialProps = useMemo<Record<string, string>>(() => {
    const out: Record<string, string> = {};
    if (initial?.properties) {
      for (const [k, v] of Object.entries(initial.properties)) {
        out[k] = stringOrEmpty(v);
      }
    }
    return out;
  }, [initial]);
  const [props, setProps] = useState<Record<string, string>>(initialProps);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<TestConnectionResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fields = TYPE_FIELDS[type];

  const buildRequest = (): MarketplaceWriteRequest => {
    const trimmedProps: Record<string, unknown> = {};
    for (const f of fields) {
      const raw = props[f.key];
      if (raw == null) continue;
      const trimmed = raw.trim();
      if (trimmed !== '') trimmedProps[f.key] = trimmed;
    }
    return { id: id.trim(), type, properties: trimmedProps };
  };

  const validate = (req: MarketplaceWriteRequest): string | null => {
    if (!req.id) return 'ID is required.';
    if (!/^[A-Za-z0-9._-]+$/.test(req.id)) {
      return 'ID may only contain letters, digits, dots, underscores, and hyphens.';
    }
    for (const f of fields) {
      if (f.required && !req.properties[f.key]) {
        return `${f.label} is required.`;
      }
    }
    return null;
  };

  const handleTest = async () => {
    setError(null);
    setTestResult(null);
    const req = buildRequest();
    const v = validate(req);
    if (v) {
      setError(v);
      return;
    }
    setTesting(true);
    try {
      const r = await testMarketplaceTransient(req);
      setTestResult(r);
    } catch (e) {
      setTestResult({ ok: false, message: (e as Error).message });
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async () => {
    setError(null);
    const req = buildRequest();
    const v = validate(req);
    if (v) {
      setError(v);
      return;
    }
    setSaving(true);
    try {
      await onSave(req);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={overlayStyle} onClick={onCancel}>
      <div style={dialogStyle} onClick={e => e.stopPropagation()}>
        <h3 style={{ margin: '0 0 16px', fontSize: '1.05rem' }}>
          {isEdit ? `Edit marketplace` : 'Add marketplace'}
        </h3>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div>
            <label style={labelStyle}>ID</label>
            <input
              type="text"
              value={id}
              disabled={isEdit}
              onChange={e => setId(e.target.value)}
              placeholder="team-skills-git"
              style={{
                ...inputStyle,
                background: isEdit ? '#f1f5f9' : '#ffffff',
                color: isEdit ? '#64748b' : '#0f172a',
              }}
            />
            {!isEdit && (
              <div style={hintStyle}>Stable identifier. Cannot be changed after creation.</div>
            )}
          </div>
          <div>
            <label style={labelStyle}>Type</label>
            <select
              value={type}
              disabled={isEdit}
              onChange={e => {
                setType(e.target.value as MarketplaceType);
                setTestResult(null);
              }}
              style={{
                ...inputStyle,
                background: isEdit ? '#f1f5f9' : '#ffffff',
                color: isEdit ? '#64748b' : '#0f172a',
              }}
            >
              <option value="git">git</option>
              <option value="nacos">nacos</option>
            </select>
          </div>
          {fields.map(f => (
            <div key={f.key}>
              <label style={labelStyle}>
                {f.label}
                {f.required && <span style={{ color: '#dc2626', marginLeft: 4 }}>*</span>}
              </label>
              <input
                type={f.secret ? 'password' : 'text'}
                value={props[f.key] ?? ''}
                onChange={e => setProps(p => ({ ...p, [f.key]: e.target.value }))}
                placeholder={f.placeholder}
                style={inputStyle}
                autoComplete="off"
              />
              {f.hint && <div style={hintStyle}>{f.hint}</div>}
              {f.secret && isEdit && (
                <div style={hintStyle}>
                  Leave blank to keep the previously saved value (current value is never shown).
                </div>
              )}
            </div>
          ))}
          {testResult && (
            <div
              style={{
                padding: 10,
                borderRadius: 8,
                background: testResult.ok ? '#dcfce7' : '#fef2f2',
                border: `1px solid ${testResult.ok ? '#bbf7d0' : '#fecaca'}`,
                color: testResult.ok ? '#15803d' : '#dc2626',
                fontSize: '0.85rem',
              }}
            >
              {testResult.ok ? '✓' : '✗'} {testResult.message ?? (testResult.ok ? 'OK' : 'Failed')}
              {testResult.ok && typeof testResult.skillCount === 'number' && (
                <span style={{ marginLeft: 6, color: '#15803d' }}>
                  ({testResult.skillCount} skills)
                </span>
              )}
            </div>
          )}
          {error && (
            <div
              style={{
                padding: 10,
                borderRadius: 8,
                background: '#fef2f2',
                border: '1px solid #fecaca',
                color: '#dc2626',
                fontSize: '0.85rem',
              }}
            >
              {error}
            </div>
          )}
        </div>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            marginTop: 22,
            gap: 8,
          }}
        >
          <button onClick={handleTest} disabled={testing || saving} style={secondaryButton}>
            {testing ? 'Testing…' : 'Test connection'}
          </button>
          <div style={{ display: 'flex', gap: 8 }}>
            <button onClick={onCancel} disabled={saving} style={secondaryButton}>
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={saving || testing}
              style={{ ...primaryButton, opacity: saving ? 0.6 : 1 }}
            >
              {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Add'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
