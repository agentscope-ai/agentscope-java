/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React from 'react';
import { ChannelFieldSpec, ChannelTypeSpec } from '../api/channels';

const S: Record<string, React.CSSProperties> = {
  field: { display: 'block', fontSize: '0.85rem', color: '#475569', marginBottom: 6, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
  hint: { fontSize: '0.78rem', color: '#94a3b8', marginTop: 4 },
  req: { color: '#dc2626' },
  grid: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 },
  advancedToggle: {
    background: 'none', border: 'none', color: '#4f46e5', cursor: 'pointer',
    padding: 0, fontSize: '0.85rem', marginTop: 8,
  },
};

interface Props {
  spec: ChannelTypeSpec | undefined;
  values: Record<string, string>;
  onChange: (values: Record<string, string>) => void;
  showAdvanced?: boolean;
  onToggleAdvanced?: () => void;
}

export default function PlatformCredentialsForm({
  spec,
  values,
  onChange,
  showAdvanced,
  onToggleAdvanced,
}: Props) {
  if (!spec) {
    return <div style={{ color: '#94a3b8', fontSize: '0.9rem' }}>Select a platform to configure credentials.</div>;
  }

  const primary = spec.fields.filter((f) => !f.advanced);
  const advanced = spec.fields.filter((f) => f.advanced);
  const hasAdvanced = advanced.length > 0;

  function renderField(f: ChannelFieldSpec) {
    const inputType = f.secret || f.inputType === 'password' ? 'password' : f.inputType === 'number' ? 'number' : 'text';
    return (
      <div key={f.key} style={f.key === 'encodingAesKey' || f.key === 'robotCode' ? { gridColumn: '1 / span 2' } : undefined}>
        <label style={S.field}>
          {f.label}{f.required ? <span style={S.req}> *</span> : null}
        </label>
        <input
          style={S.input}
          type={inputType}
          autoComplete="off"
          value={values[f.key] ?? ''}
          placeholder={f.secret ? '••••••••' : undefined}
          onChange={(e) => onChange({ ...values, [f.key]: e.target.value })}
        />
        {f.hint ? <div style={S.hint}>{f.hint}</div> : null}
      </div>
    );
  }

  return (
    <div>
      {spec.hint ? <div style={{ ...S.hint, marginBottom: 12 }}>{spec.hint}</div> : null}
      <div style={S.grid}>{primary.map(renderField)}</div>
      {hasAdvanced ? (
        <>
          <button type="button" style={S.advancedToggle} onClick={onToggleAdvanced}>
            {showAdvanced ? 'Hide advanced' : 'Show advanced'}
          </button>
          {showAdvanced ? <div style={{ ...S.grid, marginTop: 12 }}>{advanced.map(renderField)}</div> : null}
        </>
      ) : null}
    </div>
  );
}

/** Build initial credential values from a type spec + existing properties (masked secrets kept as empty for edit). */
export function credentialsFromProperties(
  spec: ChannelTypeSpec | undefined,
  properties: Record<string, unknown> | undefined,
): Record<string, string> {
  const out: Record<string, string> = {};
  if (!spec) return out;
  for (const f of spec.fields) {
    const v = properties?.[f.key];
    if (v == null) {
      out[f.key] = '';
    } else if (f.secret && String(v) === '********') {
      out[f.key] = '';
    } else {
      out[f.key] = String(v);
    }
  }
  return out;
}

/** Merge form values into a properties payload; omit blank secrets so CP keeps existing. */
export function propertiesFromCredentials(
  spec: ChannelTypeSpec | undefined,
  values: Record<string, string>,
  isEdit: boolean,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (!spec) return out;
  for (const f of spec.fields) {
    const raw = values[f.key];
    if (raw == null || raw === '') {
      if (!isEdit || !f.secret) {
        // omit
      }
      continue;
    }
    if (f.inputType === 'number') {
      const n = Number(raw);
      out[f.key] = Number.isFinite(n) ? n : raw;
    } else {
      out[f.key] = raw;
    }
  }
  return out;
}
