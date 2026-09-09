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

import { useState } from "react";
import { Check, ListChecks, Pencil, Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { parseCriteria, type AcceptanceCriteria } from "./issueProperties";

export function IssueAcceptanceEditor({ value, busy, onSave }: {
  value: unknown;
  busy: boolean;
  onSave: (criteria: AcceptanceCriteria) => Promise<unknown>;
}) {
  const criteria = parseCriteria(value);
  const checklist = criteria.checklist || [];
  const [draft, setDraft] = useState("");
  const [editing, setEditing] = useState<number>();
  const [editText, setEditText] = useState("");
  const [rules, setRules] = useState<AcceptanceCriteria>();
  const save = async (next: AcceptanceCriteria, complete?: () => void) => {
    if (busy) return;
    try { await onSave(next); complete?.(); } catch { /* The page displays the mutation error. */ }
  };
  const add = () => {
    if (draft.trim()) void save({ ...criteria, checklist: [...checklist, { id: crypto.randomUUID(), text: draft.trim(), required: true, satisfied: false }] }, () => setDraft(""));
  };

  return <section className="border-t border-slate-200 pt-5" aria-label="Acceptance criteria">
    <h2 className="mb-3 flex items-center justify-between text-sm font-semibold text-slate-900">
      <span className="flex items-center gap-2"><ListChecks className="h-4 w-4" /> Acceptance</span>
      {!!checklist.length && <span className="text-xs font-normal text-muted-foreground">{checklist.filter(entry => entry.satisfied).length}/{checklist.length}</span>}
    </h2>
    <p className="mb-2 text-xs leading-5 text-muted-foreground">Required items must be satisfied before marking this issue Done.</p>
    <div className="space-y-2">
      {checklist.map((criterion, index) => {
        const label = criterion.text || criterion.id || `Criterion ${index + 1}`;
        return <div key={criterion.id || index} className="rounded-lg bg-white p-2 text-sm">
          {editing === index ? <form className="space-y-2" onSubmit={event => {
            event.preventDefault();
            if (editText.trim()) void save({ ...criteria, checklist: checklist.map((entry, i) => i === index ? { ...entry, text: editText.trim() } : entry) }, () => setEditing(undefined));
          }}>
            <Input aria-label="Criterion text" value={editText} onChange={event => setEditText(event.target.value)} autoFocus />
            <div className="flex gap-1"><Button size="sm" disabled={busy || !editText.trim()}>Save criterion</Button><Button type="button" size="sm" variant="ghost" onClick={() => setEditing(undefined)}>Cancel</Button></div>
          </form> : <div className="flex items-start gap-1">
            <label className="flex min-w-0 flex-1 cursor-pointer items-start gap-2">
              <input type="checkbox" className="mt-1" checked={!!criterion.satisfied} disabled={busy} onChange={() => void save({ ...criteria, checklist: checklist.map((entry, i) => i === index ? { ...entry, satisfied: !entry.satisfied } : entry) })} />
              <span className={cn("break-words", criterion.satisfied && "text-muted-foreground line-through")}>{label}{criterion.required === false && <span className="block text-xs text-muted-foreground">Optional</span>}</span>
            </label>
            <Button variant="ghost" size="icon" className="h-6 w-6 shrink-0" aria-label={`Edit criterion: ${label}`} disabled={busy} onClick={() => { setEditText(label); setEditing(index); }}><Pencil className="h-3 w-3" /></Button>
            <Button variant="ghost" size="icon" className="h-6 w-6 shrink-0" aria-label={`Remove criterion: ${label}`} disabled={busy} onClick={() => void save({ ...criteria, checklist: checklist.filter((_, i) => i !== index) })}><X className="h-3 w-3" /></Button>
          </div>}
        </div>;
      })}
      <form className="flex gap-1" onSubmit={event => { event.preventDefault(); add(); }}>
        <Input aria-label="New acceptance criterion" className="h-8 bg-white text-xs" value={draft} onChange={event => setDraft(event.target.value)} placeholder="Add acceptance criterion" />
        <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0" disabled={!draft.trim() || busy} aria-label="Add acceptance criterion"><Plus className="h-4 w-4" /></Button>
      </form>
      {rules ? <form className="space-y-3 rounded-lg border bg-white p-3 text-xs" onSubmit={event => {
        event.preventDefault();
        void save({ ...criteria, requiredResult: !!rules.requiredResult, minimumArtifacts: rules.minimumArtifacts || 0, minimumApprovals: rules.minimumApprovals || 0 }, () => setRules(undefined));
      }}>
        <label className="flex items-center gap-2"><input type="checkbox" checked={!!rules.requiredResult} onChange={event => setRules({ ...rules, requiredResult: event.target.checked })} /> Require result comment</label>
        <label className="block space-y-1"><span>Minimum artifacts</span><Input type="number" min="0" step="1" required value={rules.minimumArtifacts ?? 0} onChange={event => setRules({ ...rules, minimumArtifacts: Number(event.target.value) })} /></label>
        <label className="block space-y-1"><span>Minimum approvals</span><Input type="number" min="0" step="1" required value={rules.minimumApprovals ?? 0} onChange={event => setRules({ ...rules, minimumApprovals: Number(event.target.value) })} /></label>
        <div className="flex gap-1"><Button size="sm" disabled={busy}><Check className="h-3 w-3" /> Save rules</Button><Button type="button" variant="ghost" size="sm" onClick={() => setRules(undefined)}>Cancel</Button></div>
      </form> : <div className="text-xs leading-5 text-muted-foreground">
        {criteria.requiredResult && <div>Result comment required</div>}
        {!!criteria.minimumArtifacts && <div>{criteria.minimumArtifacts} artifact(s) required</div>}
        {!!criteria.minimumApprovals && <div>{criteria.minimumApprovals} approval(s) required</div>}
        <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" disabled={busy} onClick={() => setRules({ ...criteria })}>Edit acceptance rules</Button>
      </div>}
    </div>
  </section>;
}
