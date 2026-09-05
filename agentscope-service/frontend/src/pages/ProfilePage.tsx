/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useEffect, useState } from 'react';
import { KeyRound, UserRound } from 'lucide-react';

import { changePassword, getProfile, type UserProfile } from '../api/auth';
import { Page, PageHeader } from '../components/Page';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Input } from '../components/ui/input';

export default function ProfilePage() {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [curPwd, setCurPwd] = useState('');
  const [newPwd, setNewPwd] = useState('');
  const [conPwd, setConPwd] = useState('');
  const [pwdErr, setPwdErr] = useState<string | null>(null);
  const [pwdOk, setPwdOk] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    getProfile().then(setProfile).catch((error) => setLoadErr(error.message));
  }, []);

  async function handleChangePwd() {
    setPwdErr(null);
    setPwdOk(false);
    if (newPwd.length < 6) { setPwdErr('Password must be at least 6 characters'); return; }
    if (newPwd !== conPwd) { setPwdErr('Passwords do not match'); return; }
    setSaving(true);
    try {
      await changePassword(curPwd, newPwd);
      setPwdOk(true);
      setCurPwd('');
      setNewPwd('');
      setConPwd('');
    } catch (error: unknown) {
      setPwdErr(error instanceof Error ? error.message : 'Password update failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Page className="max-w-[1000px]">
      <PageHeader title="Profile" description="Review your console identity and update account security." />
      {loadErr && <p className="rounded-xl border border-red-100 bg-red-50 px-4 py-3 text-sm text-red-700">{loadErr}</p>}
      <div className="grid gap-5 lg:grid-cols-[minmax(0,0.8fr)_minmax(0,1.2fr)]">
        <Card>
          <CardHeader>
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600"><UserRound className="h-4 w-4" /></span>
            <CardTitle className="pt-2">Account</CardTitle>
            <CardDescription>Your authenticated console identity and assigned roles.</CardDescription>
          </CardHeader>
          <CardContent>
            {profile ? (
              <dl className="divide-y divide-slate-100 text-sm">
                <div className="grid grid-cols-[7rem_minmax(0,1fr)] gap-3 py-3"><dt className="text-slate-500">Username</dt><dd className="font-medium text-slate-900">{profile.username}</dd></div>
                <div className="grid grid-cols-[7rem_minmax(0,1fr)] gap-3 py-3"><dt className="text-slate-500">User ID</dt><dd className="break-all font-mono text-xs text-slate-600">{profile.userId}</dd></div>
                <div className="grid grid-cols-[7rem_minmax(0,1fr)] gap-3 py-3"><dt className="text-slate-500">Roles</dt><dd className="flex flex-wrap gap-1.5">{profile.roles.map((role) => <Badge key={role} tone={role === 'admin' ? 'info' : 'default'}>{role}</Badge>)}</dd></div>
              </dl>
            ) : <div className="py-8 text-center text-sm text-slate-500">Loading profile…</div>}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-slate-600"><KeyRound className="h-4 w-4" /></span>
            <CardTitle className="pt-2">Change password</CardTitle>
            <CardDescription>Choose a password with at least six characters.</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={(event) => { event.preventDefault(); void handleChangePwd(); }} className="space-y-4">
              <label className="block space-y-2 text-sm font-medium text-slate-700">Current password<Input type="password" value={curPwd} onChange={(event) => setCurPwd(event.target.value)} autoComplete="current-password" /></label>
              <div className="grid gap-4 sm:grid-cols-2">
                <label className="block space-y-2 text-sm font-medium text-slate-700">New password<Input type="password" value={newPwd} onChange={(event) => setNewPwd(event.target.value)} autoComplete="new-password" /></label>
                <label className="block space-y-2 text-sm font-medium text-slate-700">Confirm password<Input type="password" value={conPwd} onChange={(event) => setConPwd(event.target.value)} autoComplete="new-password" /></label>
              </div>
              {pwdErr && <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{pwdErr}</p>}
              {pwdOk && <p className="rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-700">Password changed successfully.</p>}
              <Button type="submit" disabled={saving || !curPwd || !newPwd || !conPwd}>{saving ? 'Updating…' : 'Update password'}</Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </Page>
  );
}
