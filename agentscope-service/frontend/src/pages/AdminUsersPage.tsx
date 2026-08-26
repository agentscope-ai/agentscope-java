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

import React, { useCallback, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { resolveApiErrorMessage } from '@/api/errors';
import {
  AdminUserView,
  CreateUserResponse,
  createUser,
  deleteUser,
  listUsers,
  resetPassword,
  updateRoles,
} from '../api/admin';
import { isAdmin } from '../api/auth';
import { useT, type TranslationFunction } from '../i18n';

const S: Record<string, React.CSSProperties> = {
  page: { padding: '36px 40px', maxWidth: 1000 },
  title: { margin: 0, fontSize: '1.6rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  subtitle: { margin: '4px 0 28px', fontSize: '0.92rem', color: '#64748b' },
  headerBar: { display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', marginBottom: 24 },
  primaryBtn: {
    padding: '10px 18px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.92rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)', overflow: 'hidden',
  },
  th: {
    textAlign: 'left' as const, padding: '14px 20px', background: '#f8fafc',
    fontSize: '0.78rem', color: '#64748b', fontWeight: 700,
    textTransform: 'uppercase' as const, letterSpacing: '0.08em', borderBottom: '1px solid #e2e8f0',
  },
  td: { padding: '16px 20px', fontSize: '0.92rem', color: '#0f172a', borderBottom: '1px solid #f1f5f9' },
  rowAction: {
    padding: '6px 12px', borderRadius: 7, border: '1px solid #e2e8f0', background: '#ffffff',
    color: '#475569', fontSize: '0.82rem', fontWeight: 500, cursor: 'pointer',
    marginRight: 8,
  },
  rowActionDanger: {
    padding: '6px 12px', borderRadius: 7, border: '1px solid #fecaca', background: '#ffffff',
    color: '#dc2626', fontSize: '0.82rem', fontWeight: 500, cursor: 'pointer',
  },
  badge: {
    display: 'inline-block', padding: '3px 10px', borderRadius: 999,
    fontSize: '0.76rem', fontWeight: 600, marginRight: 6,
  },
  modalBackdrop: {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
  },
  modal: {
    background: '#ffffff', borderRadius: 14, padding: '28px 32px',
    width: 'min(480px, 92vw)', boxShadow: '0 24px 60px rgba(15,23,42,0.32)',
  },
  modalTitle: { margin: '0 0 18px', fontSize: '1.15rem', fontWeight: 700, color: '#0f172a' },
  fieldLabel: { display: 'block', fontSize: '0.88rem', fontWeight: 500, color: '#475569', marginBottom: 6 },
  input: {
    width: '100%', boxSizing: 'border-box' as const, padding: '10px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.92rem', marginBottom: 14,
  },
  secondaryBtn: {
    padding: '9px 16px', background: '#ffffff',
    color: '#475569', border: '1px solid #cbd5e1', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.9rem', fontWeight: 500,
  },
  error: { color: '#dc2626', fontSize: '0.88rem', marginTop: 6, marginBottom: 6 },
  callout: {
    background: '#ecfdf5', border: '1px solid #a7f3d0', borderRadius: 10,
    padding: '14px 16px', marginTop: 14,
  },
  codeBlock: {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.95rem', color: '#065f46', fontWeight: 700, letterSpacing: '0.03em',
  },
};

function roleBadge(role: string, t: TranslationFunction) {
  const isA = role === 'admin';
  return (
    <span
      key={role}
      style={{
        ...S.badge,
        background: isA ? '#eef2ff' : '#f1f5f9',
        color: isA ? '#4338ca' : '#64748b',
        border: isA ? '1px solid #c7d2fe' : '1px solid #e2e8f0',
      }}
    >
      {role === 'admin' ? t('teams.role.admin') : role === 'user' ? t('teams.role.user') : role}
    </span>
  );
}

function InviteModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (res: CreateUserResponse) => void;
}) {
  const t = useT();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [adminRole, setAdminRole] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    setErr(null);
    const u = username.trim();
    if (!u) { setErr(t('teams.admin.usernameRequired')); return; }
    setSubmitting(true);
    try {
      const roles = adminRole ? ['user', 'admin'] : ['user'];
      const res = await createUser({
        username: u,
        initialPassword: password.trim() ? password : undefined,
        roles,
      });
      onCreated(res);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('teams.admin.failed')));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={S.modalBackdrop} onClick={onClose}>
      <div style={S.modal} onClick={e => e.stopPropagation()}>
        <h3 style={S.modalTitle}>{t('teams.admin.inviteTitle')}</h3>
        <label style={S.fieldLabel}>{t('teams.admin.username')}</label>
        <input style={S.input} value={username} onChange={e => setUsername(e.target.value)} autoFocus />
        <label style={S.fieldLabel}>{t('teams.admin.initialPassword')} <span style={{ color: '#94a3b8', fontWeight: 400 }}>{t('teams.admin.initialPasswordHint')}</span></label>
        <input style={S.input} type="password" value={password} onChange={e => setPassword(e.target.value)} />
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.92rem', color: '#475569', marginBottom: 18 }}>
          <input type="checkbox" checked={adminRole} onChange={e => setAdminRole(e.target.checked)} />
          {t('teams.admin.grantAdmin')}
        </label>
        {err && <p style={S.error}>{err}</p>}
        <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
          <button style={S.secondaryBtn} onClick={onClose} disabled={submitting}>{t('common.cancel')}</button>
          <button style={S.primaryBtn} onClick={submit} disabled={submitting}>
            {submitting ? t('teams.creating') : t('teams.admin.createUser')}
          </button>
        </div>
      </div>
    </div>
  );
}

function GeneratedPasswordModal({
  username,
  password,
  onClose,
}: {
  username: string;
  password: string;
  onClose: () => void;
}) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(password);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch { /* ignore */ }
  }
  return (
    <div style={S.modalBackdrop}>
      <div style={S.modal}>
        <h3 style={S.modalTitle}>{t('teams.admin.userCreated', { username })}</h3>
        <p style={{ fontSize: '0.92rem', color: '#475569', margin: '0 0 10px' }}>
          {t('teams.admin.temporaryPassword')}
        </p>
        <div style={S.callout}>
          <div style={S.codeBlock}>{password}</div>
          <button style={{ ...S.secondaryBtn, marginTop: 10 }} onClick={copy}>
            {copied ? t('teams.admin.copied') : t('teams.admin.copy')}
          </button>
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 18 }}>
          <button style={S.primaryBtn} onClick={onClose}>{t('teams.admin.done')}</button>
        </div>
      </div>
    </div>
  );
}

function ResetPasswordModal({
  user,
  onClose,
  onDone,
}: {
  user: AdminUserView;
  onClose: () => void;
  onDone: () => void;
}) {
  const t = useT();
  const [pwd, setPwd] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  async function submit() {
    setErr(null);
    if (pwd.length < 6) { setErr(t('teams.admin.passwordMin')); return; }
    setSubmitting(true);
    try {
      await resetPassword(user.userId, pwd);
      onDone();
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('teams.admin.failed')));
    } finally {
      setSubmitting(false);
    }
  }
  return (
    <div style={S.modalBackdrop} onClick={onClose}>
      <div style={S.modal} onClick={e => e.stopPropagation()}>
        <h3 style={S.modalTitle}>{t('teams.admin.resetPasswordTitle', { username: user.username })}</h3>
        <label style={S.fieldLabel}>{t('teams.admin.newPassword')}</label>
        <input style={S.input} type="password" value={pwd} onChange={e => setPwd(e.target.value)} autoFocus />
        {err && <p style={S.error}>{err}</p>}
        <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
          <button style={S.secondaryBtn} onClick={onClose} disabled={submitting}>{t('common.cancel')}</button>
          <button style={S.primaryBtn} onClick={submit} disabled={submitting}>
            {submitting ? t('common.saving') : t('teams.admin.resetPassword')}
          </button>
        </div>
      </div>
    </div>
  );
}

export default function AdminUsersPage() {
  const t = useT();
  const admin = isAdmin();
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [loadErr, setLoadErr] = useState<unknown>(null);
  const [showInvite, setShowInvite] = useState(false);
  const [generated, setGenerated] = useState<{ username: string; password: string } | null>(null);
  const [resetTarget, setResetTarget] = useState<AdminUserView | null>(null);

  const refresh = useCallback(async () => {
    try {
      setUsers(await listUsers());
    } catch (e: unknown) {
      setLoadErr(e);
    }
  }, []);
  useEffect(() => { if (admin) void refresh(); }, [admin, refresh]);

  if (!admin) {
    return <Navigate to="/agents" replace />;
  }

  function toggleAdmin(u: AdminUserView) {
    const hasAdmin = u.roles.includes('admin');
    const next = hasAdmin ? u.roles.filter(r => r !== 'admin') : [...u.roles, 'admin'];
    if (!next.includes('user')) next.push('user');
    updateRoles(u.userId, next)
      .then(refresh)
      .catch((e: unknown) => alert(resolveApiErrorMessage(e, t('teams.admin.failed'))));
  }

  function doDelete(u: AdminUserView) {
    if (!confirm(t('teams.admin.deleteConfirm', { username: u.username }))) return;
    deleteUser(u.userId)
      .then(refresh)
      .catch((e: unknown) => alert(resolveApiErrorMessage(e, t('teams.admin.failed'))));
  }

  return (
    <div style={S.page}>
      <div style={S.headerBar}>
        <div>
          <h2 style={S.title}>{t('teams.admin.title')}</h2>
          <p style={S.subtitle}>{t('teams.admin.description')}</p>
        </div>
        <button style={S.primaryBtn} onClick={() => setShowInvite(true)}>+ {t('teams.admin.invite')}</button>
      </div>

      {loadErr != null && (
        <p style={{ color: '#dc2626' }}>
          {resolveApiErrorMessage(loadErr, t('teams.admin.loadFailed'))}
        </p>
      )}

      <div style={S.card}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              <th style={S.th}>{t('teams.admin.username')}</th>
              <th style={S.th}>{t('teams.admin.userId')}</th>
              <th style={S.th}>{t('teams.admin.roles')}</th>
              <th style={{ ...S.th, textAlign: 'right' }}>{t('teams.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {users.map(u => (
              <tr key={u.userId}>
                <td style={{ ...S.td, fontWeight: 600 }}>{u.username}</td>
                <td style={{ ...S.td, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.85rem', color: '#64748b' }}>
                  {u.userId}
                </td>
                <td style={S.td}>{u.roles.map(role => roleBadge(role, t))}</td>
                <td style={{ ...S.td, textAlign: 'right' }}>
                  <button style={S.rowAction} onClick={() => toggleAdmin(u)}>
                    {u.roles.includes('admin') ? t('teams.admin.demote') : t('teams.admin.promote')}
                  </button>
                  <button style={S.rowAction} onClick={() => setResetTarget(u)}>{t('teams.admin.resetPassword')}</button>
                  <button style={S.rowActionDanger} onClick={() => doDelete(u)}>{t('common.delete')}</button>
                </td>
              </tr>
            ))}
            {users.length === 0 && !loadErr && (
              <tr>
                <td style={{ ...S.td, textAlign: 'center', color: '#94a3b8' }} colSpan={4}>{t('teams.admin.noUsers')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {showInvite && (
        <InviteModal
          onClose={() => setShowInvite(false)}
          onCreated={res => {
            setShowInvite(false);
            refresh();
            if (res.generatedPassword) {
              setGenerated({ username: res.user.username, password: res.generatedPassword });
            }
          }}
        />
      )}
      {generated && (
        <GeneratedPasswordModal
          username={generated.username}
          password={generated.password}
          onClose={() => setGenerated(null)}
        />
      )}
      {resetTarget && (
        <ResetPasswordModal
          user={resetTarget}
          onClose={() => setResetTarget(null)}
          onDone={() => setResetTarget(null)}
        />
      )}
    </div>
  );
}
