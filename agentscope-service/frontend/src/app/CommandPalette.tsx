import { useEffect } from 'react'

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
  const links = [
    ['Issues', '/work/issues'],
    ['Executions', '/agent-center/activity/executions'],
    ['Teams', '/agent-center/teams'],
    ['Workflows', '/agent-center/workflows'],
    ['Approvals', '/work/approvals'],
    ['Automations', '/work/automations'],
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
