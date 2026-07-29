export function JsonViewer({ value, className }: { value: unknown; className?: string }) {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  return (
    <pre className={`overflow-auto rounded-lg bg-slate-950 p-4 text-xs leading-relaxed text-slate-100 ${className || ''}`}>
      {text}
    </pre>
  );
}
