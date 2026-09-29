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

import { useEffect } from 'react'
import { getRoles } from '@/api/auth'

export function useCommandPaletteShortcut(open: () => void) {
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        open()
      }
    }
    window.addEventListener('keydown', listener)
    return () => window.removeEventListener('keydown', listener)
  }, [open])
}

export function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  if (!open) return null
  const roles = getRoles().map(role => role.toLowerCase())
  const admin = roles.includes('admin')
  const canOperate = admin || roles.includes('operator')
  const canAgentCenter = canOperate || roles.includes('agent_developer')
  const links = [
    ['Overview', '/work/overview'],
    ['Chat', '/work/chat'],
    ['Issues', '/work/issues'],
    ['Inbox', '/work/inbox'],
    ['Automations', '/work/automations'],
    ...(canAgentCenter ? [
      ['Agents', '/agent-center/agents'],
      ['Teams', '/agent-center/teams'],
      ['Workflows', '/agent-center/workflows'],
      ...(admin ? [['Channels', '/agent-center/entrypoints']] : []),
      ['Workspaces', '/agent-center/workspaces'],
      ['Environments', '/agent-center/environments'],
      ['Memory', '/agent-center/memory'],
      ['Vault', '/agent-center/vaults'],
    ] : []),
  ]
  return (
    <div className="fixed inset-0 z-50 bg-black/30 p-6" onClick={() => onOpenChange(false)}>
      <div className="mx-auto max-w-lg rounded-xl bg-white p-4 shadow-xl" onClick={(event) => event.stopPropagation()}>
        <div className="mb-3 font-semibold">Go to</div>
        {links.map(([label, to]) => (
          <a key={to} href={to} className="block rounded-lg px-3 py-2 hover:bg-muted">
            {label}
          </a>
        ))}
      </div>
    </div>
  )
}
