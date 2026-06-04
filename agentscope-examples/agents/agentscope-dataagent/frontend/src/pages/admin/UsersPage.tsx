import React, { useEffect, useState } from 'react';
import AdminPageLayout from '../../components/admin/AdminPageLayout';
import {
  UserView,
  listUsers,
  createUser,
  updateUserRoles,
  deleteUser,
  adminResetPassword,
} from '../../api/users';

// ── styles ────────────────────────────────────────────────────────────
function badge(isAdmin: boolean): React.CSSProperties {
  return { display: 'inline-block', padding: '2px 10px', borderRadius: 999, fontSize: '0.76rem', marginRight: 6, fontWeight: 500,
    background: isAdmin ? '#e0e7ff' : '#f1f5f9', color: isAdmin ? '#4338ca' : '#64748b' };
}
function actionBtn(danger?: boolean): React.CSSProperties {
  return { padding: '5px 12px', fontSize: '0.82rem', marginRight: 6, fontWeight: 500,
    border: `1px solid ${danger ? '#fecaca' : '#d1d5db'}`,
    borderRadius: 6, background: danger ? '#ffffff' : '#ffffff',
    color: danger ? '#dc2626' : '#475569', cursor: 'pointer' };
}
const S: Record<string, React.CSSProperties> = {
  err:      { color: '#dc2626', fontSize: '0.9rem', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 10, padding: '12px 16px', marginBottom: 18 },
  info:     { color: '#15803d', fontSize: '0.9rem', background: '#dcfce7', border: '1px solid #86efac', borderRadius: 10, padding: '12px 16px', marginBottom: 18 },
  table:    { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.9rem', background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 12, overflow: 'hidden', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  th:       { textAlign: 'left' as const, padding: '12px 16px', background: '#f8fafc', color: '#64748b', borderBottom: '1px solid #e5e7eb', fontWeight: 600, fontSize: '0.78rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em' },
  td:       { padding: '14px 16px', borderBottom: '1px solid #f1f5f9', color: '#334155', verticalAlign: 'middle' as const },
  refreshBtn:{ background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '6px 14px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500 },
  // Add-user form
  formWrap: { maxWidth: 540 },
  formCard: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '2rem', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  label:    { display: 'block', fontSize: '0.85rem', color: '#475569', fontWeight: 500, marginBottom: 6 },
  input:    { width: '100%', boxSizing: 'border-box' as const, padding: '9px 12px', background: '#ffffff', border: '1px solid #d1d5db', borderRadius: 8, color: '#0f172a', fontSize: '0.92rem', outline: 'none', marginBottom: 18 },
  checkRow: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 18 },
  saveBtn:  { background: '#4f46e5', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 24px', cursor: 'pointer', fontSize: '0.92rem', fontWeight: 600, marginRight: 10, boxShadow: '0 1px 3px rgba(79,70,229,0.25)' },
  cancelBtn:{ background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '9px 18px', cursor: 'pointer', fontSize: '0.92rem', fontWeight: 500 },
  // Modals (for edit roles / reset password — kept as overlay since user-specific)
  modal:    { position: 'fixed' as const, inset: 0, background: 'rgba(15,23,42,0.35)', backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 },
  modalBox: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.75rem', minWidth: 380, maxWidth: 460, boxShadow: '0 24px 60px rgba(15,23,42,0.18), 0 4px 12px rgba(15,23,42,0.06)' } as React.CSSProperties,
  modalTitle:{ margin: '0 0 22px', fontSize: '1.1rem', fontWeight: 600, color: '#0f172a' } as React.CSSProperties,
  modalInput:{ width: '100%', boxSizing: 'border-box' as const, padding: '9px 12px', background: '#ffffff', border: '1px solid #d1d5db', borderRadius: 8, color: '#0f172a', fontSize: '0.92rem', outline: 'none', marginBottom: 18 },
  modalActions:{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 10 } as React.CSSProperties,
  primaryBtn:  { padding: '8px 20px', background: '#4f46e5', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer', fontSize: '0.9rem', fontWeight: 600, boxShadow: '0 1px 3px rgba(79,70,229,0.25)' } as React.CSSProperties,
  secBtn:      { padding: '8px 16px', background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, cursor: 'pointer', fontSize: '0.9rem', fontWeight: 500 } as React.CSSProperties,
};

type ModalMode = 'reset-password' | 'edit-roles' | null;
type Tab = 'list' | 'add';

export default function UsersPage() {
  const [users,    setUsers]    = useState<UserView[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [tab,      setTab]      = useState<Tab>('list');
  const [error,    setError]    = useState<string | null>(null);
  const [info,     setInfo]     = useState<string | null>(null);
  const [modalMode,    setModalMode]    = useState<ModalMode>(null);
  const [targetUser,   setTargetUser]   = useState<UserView | null>(null);
  const [formError,    setFormError]    = useState<string | null>(null);
  // Add-user form state
  const [newUsername,  setNewUsername]  = useState('');
  const [newPassword,  setNewPassword]  = useState('');
  const [newIsAdmin,   setNewIsAdmin]   = useState(false);
  const [adding,       setAdding]       = useState(false);
  // Modal state
  const [resetPassword, setResetPassword] = useState('');
  const [editAdmin,     setEditAdmin]     = useState(false);

  async function load() {
    setLoading(true); setError(null);
    try { setUsers(await listUsers()); }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, []);

  async function handleCreate() {
    setAdding(true); setFormError(null);
    try {
      await createUser({ username: newUsername, password: newPassword, roles: newIsAdmin ? ['user', 'admin'] : ['user'] });
      setInfo(`User "${newUsername}" created.`);
      setNewUsername(''); setNewPassword(''); setNewIsAdmin(false);
      setTab('list');
      await load();
    } catch (e: unknown) { setFormError(e instanceof Error ? e.message : 'Error'); }
    finally { setAdding(false); }
  }

  async function handleResetPassword() {
    if (!targetUser) return;
    setFormError(null);
    try { await adminResetPassword(targetUser.userId, resetPassword); setModalMode(null); setInfo(`Password reset for "${targetUser.username}".`); }
    catch (e: unknown) { setFormError(e instanceof Error ? e.message : 'Error'); }
  }

  async function handleEditRoles() {
    if (!targetUser) return;
    setFormError(null);
    try {
      await updateUserRoles(targetUser.userId, editAdmin ? ['user', 'admin'] : ['user']);
      setModalMode(null); await load();
    } catch (e: unknown) { setFormError(e instanceof Error ? e.message : 'Error'); }
  }

  async function handleDelete(u: UserView) {
    if (!confirm(`Delete user "${u.username}"? This cannot be undone.`)) return;
    try { await deleteUser(u.userId); await load(); }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Delete failed'); }
  }

  const tabs = [
    { key: 'list', label: 'Users', icon: '👥', badge: loading ? '…' : users.length },
    { key: 'add',  label: 'Add User', icon: '＋' },
  ];

  return (
    <>
      <AdminPageLayout tabs={tabs} activeTab={tab} onTabChange={k => setTab(k as Tab)}>
        {error && <div style={S.err}>{error}</div>}
        {info  && <div style={S.info}>{info}</div>}

        {/* ── Users list ───────────────────────────────────────────── */}
        {tab === 'list' && (
          <>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
              <button style={S.refreshBtn} onClick={load} disabled={loading}>{loading ? '…' : '↺ Refresh'}</button>
            </div>
            <table style={S.table}>
              <thead>
                <tr>
                  <th style={S.th}>User ID</th>
                  <th style={S.th}>Username</th>
                  <th style={S.th}>Roles</th>
                  <th style={S.th}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.map(u => (
                  <tr key={u.userId}>
                    <td style={{ ...S.td, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.82rem', color: '#64748b' }}>{u.userId}</td>
                    <td style={{ ...S.td, fontWeight: 600, color: '#0f172a' }}>{u.username}</td>
                    <td style={S.td}>
                      {u.roles.map(r => <span key={r} style={badge(r === 'admin')}>{r}</span>)}
                    </td>
                    <td style={S.td}>
                      <button style={actionBtn()} onClick={() => { setTargetUser(u); setEditAdmin(u.roles.includes('admin')); setFormError(null); setModalMode('edit-roles'); }}>Roles</button>
                      <button style={actionBtn()} onClick={() => { setTargetUser(u); setResetPassword(''); setFormError(null); setModalMode('reset-password'); }}>Reset pwd</button>
                      <button style={actionBtn(true)} onClick={() => handleDelete(u)}>Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!loading && users.length === 0 && <p style={{ color: '#94a3b8', fontSize: '0.92rem', marginTop: 16 }}>No users found.</p>}
          </>
        )}

        {/* ── Add user ─────────────────────────────────────────────── */}
        {tab === 'add' && (
          <div style={S.formWrap}>
            <div style={S.formCard}>
              {formError && <div style={S.err}>{formError}</div>}
              <label style={S.label}>Username</label>
              <input style={S.input} value={newUsername} onChange={e => setNewUsername(e.target.value)} placeholder="username" autoFocus />
              <label style={S.label}>Password</label>
              <input style={S.input} type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)} placeholder="at least 6 characters" />
              <div style={S.checkRow}>
                <input type="checkbox" id="isAdmin" checked={newIsAdmin} onChange={e => setNewIsAdmin(e.target.checked)} />
                <label htmlFor="isAdmin" style={{ ...S.label, marginBottom: 0 }}>Admin role</label>
              </div>
              <button style={S.saveBtn} onClick={handleCreate} disabled={adding || !newUsername || !newPassword}>
                {adding ? 'Creating…' : 'Create User'}
              </button>
              <button style={S.cancelBtn} onClick={() => setTab('list')}>Cancel</button>
            </div>
          </div>
        )}

        {/* ── Edit roles modal ─────────────────────────────────────── */}
        {modalMode === 'edit-roles' && targetUser && (
          <div style={S.modal} onClick={() => setModalMode(null)}>
            <div style={S.modalBox} onClick={e => e.stopPropagation()}>
              <h3 style={S.modalTitle}>Edit Roles — {targetUser.username}</h3>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 18 }}>
                <input type="checkbox" id="editAdmin" checked={editAdmin} onChange={e => setEditAdmin(e.target.checked)} />
                <label htmlFor="editAdmin" style={{ fontSize: '0.92rem', color: '#0f172a', fontWeight: 500 }}>Admin role</label>
              </div>
              {formError && <div style={S.err}>{formError}</div>}
              <div style={S.modalActions}>
                <button style={S.secBtn} onClick={() => setModalMode(null)}>Cancel</button>
                <button style={S.primaryBtn} onClick={handleEditRoles}>Save</button>
              </div>
            </div>
          </div>
        )}

        {/* ── Reset password modal ─────────────────────────────────── */}
        {modalMode === 'reset-password' && targetUser && (
          <div style={S.modal} onClick={() => setModalMode(null)}>
            <div style={S.modalBox} onClick={e => e.stopPropagation()}>
              <h3 style={S.modalTitle}>Reset Password — {targetUser.username}</h3>
              <label style={{ ...S.label, marginBottom: 6 }}>New Password</label>
              <input style={S.modalInput} type="password" value={resetPassword} onChange={e => setResetPassword(e.target.value)} placeholder="at least 6 characters" />
              {formError && <div style={S.err}>{formError}</div>}
              <div style={S.modalActions}>
                <button style={S.secBtn} onClick={() => setModalMode(null)}>Cancel</button>
                <button style={S.primaryBtn} onClick={handleResetPassword}>Reset</button>
              </div>
            </div>
          </div>
        )}
      </AdminPageLayout>
    </>
  );
}
