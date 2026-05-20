import React, { useEffect, useState } from 'react';
import AppShell from '../components/AppShell';
import AdminPageLayout from '../components/AdminPageLayout';
import {
  UserView,
  listUsers,
  createUser,
  updateUserRoles,
  deleteUser,
  adminResetPassword,
} from '../api/users';

// ── styles ────────────────────────────────────────────────────────────
function badge(isAdmin: boolean): React.CSSProperties {
  return { display: 'inline-block', padding: '1px 7px', borderRadius: 10, fontSize: '0.68rem', marginRight: 4,
    background: isAdmin ? '#312e81' : '#1e2235', color: isAdmin ? '#a5b4fc' : '#64748b' };
}
function actionBtn(danger?: boolean): React.CSSProperties {
  return { padding: '3px 10px', fontSize: '0.75rem', marginRight: 4,
    border: `1px solid ${danger ? '#5b2030' : '#2d3148'}`,
    borderRadius: 5, background: 'transparent',
    color: danger ? '#f87171' : '#7c8bad', cursor: 'pointer' };
}
const S: Record<string, React.CSSProperties> = {
  err:      { color: '#f87171', fontSize: '0.82rem', background: '#1f1520', border: '1px solid #5b2030', borderRadius: 8, padding: '8px 12px', marginBottom: 14 },
  info:     { color: '#34d399', fontSize: '0.82rem', background: '#0d1f14', border: '1px solid #166534', borderRadius: 8, padding: '8px 12px', marginBottom: 14 },
  table:    { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.83rem' },
  th:       { textAlign: 'left' as const, padding: '8px 10px', background: '#13151f', color: '#7c8bad', borderBottom: '1px solid #1e2235', fontWeight: 600 },
  td:       { padding: '9px 10px', borderBottom: '1px solid #1a1d2e', color: '#94a3b8', verticalAlign: 'middle' as const },
  refreshBtn:{ background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad', borderRadius: 6, padding: '4px 10px', cursor: 'pointer', fontSize: '0.78rem' },
  // Add-user form
  formWrap: { maxWidth: 480 },
  formCard: { background: '#13151f', border: '1px solid #1e2235', borderRadius: 10, padding: '1.5rem' },
  label:    { display: 'block', fontSize: '0.78rem', color: '#94a3b8', fontWeight: 500, marginBottom: 4 },
  input:    { width: '100%', boxSizing: 'border-box' as const, padding: '8px 10px', background: '#0f1117', border: '1px solid #2d3148', borderRadius: 6, color: '#e2e8f0', fontSize: '0.85rem', outline: 'none', marginBottom: 14 },
  checkRow: { display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 },
  saveBtn:  { background: '#6366f1', color: '#fff', border: 'none', borderRadius: 7, padding: '8px 22px', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 600, marginRight: 8 },
  cancelBtn:{ background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad', borderRadius: 7, padding: '8px 14px', cursor: 'pointer', fontSize: '0.85rem' },
  // Modals (for edit roles / reset password — kept as overlay since user-specific)
  modal:    { position: 'fixed' as const, inset: 0, background: 'rgba(0,0,0,0.55)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 },
  modalBox: { background: '#13151f', border: '1px solid #2d3148', borderRadius: 12, padding: '1.5rem', minWidth: 340, maxWidth: 420, boxShadow: '0 20px 60px rgba(0,0,0,0.5)' } as React.CSSProperties,
  modalTitle:{ margin: '0 0 18px', fontSize: '1rem', fontWeight: 600, color: '#e2e8f0' } as React.CSSProperties,
  modalInput:{ width: '100%', boxSizing: 'border-box' as const, padding: '8px 10px', background: '#0d0f18', border: '1px solid #2d3148', borderRadius: 6, color: '#e2e8f0', fontSize: '0.85rem', outline: 'none', marginBottom: 14 },
  modalActions:{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 8 } as React.CSSProperties,
  primaryBtn:  { padding: '7px 18px', background: '#6366f1', color: '#fff', border: 'none', borderRadius: 7, cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500 } as React.CSSProperties,
  secBtn:      { padding: '7px 14px', background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad', borderRadius: 7, cursor: 'pointer', fontSize: '0.85rem' } as React.CSSProperties,
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
    <AppShell>
      <AdminPageLayout tabs={tabs} activeTab={tab} onTabChange={k => setTab(k as Tab)}>
        {error && <div style={S.err}>{error}</div>}
        {info  && <div style={S.info}>{info}</div>}

        {/* ── Users list ───────────────────────────────────────────── */}
        {tab === 'list' && (
          <>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
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
                    <td style={{ ...S.td, fontFamily: 'monospace', fontSize: '0.75rem', color: '#4b5280' }}>{u.userId}</td>
                    <td style={{ ...S.td, fontWeight: 600, color: '#e2e8f0' }}>{u.username}</td>
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
            {!loading && users.length === 0 && <p style={{ color: '#4b5280', fontSize: '0.85rem', marginTop: 12 }}>No users found.</p>}
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
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
                <input type="checkbox" id="editAdmin" checked={editAdmin} onChange={e => setEditAdmin(e.target.checked)} />
                <label htmlFor="editAdmin" style={{ fontSize: '0.85rem', color: '#94a3b8' }}>Admin role</label>
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
    </AppShell>
  );
}
