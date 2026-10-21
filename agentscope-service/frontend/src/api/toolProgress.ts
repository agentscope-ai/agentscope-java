type Block = Record<string, unknown>;

/** A snapshot contains a bounded preview; the durable event cursor retains the complete log. */
export function toolProgress(previous: Block[], incoming: Block[], truncated = false) {
  let remaining = 64 * 1024;
  const progress: Block[] = [];
  for (const value of [...previous, ...incoming]) {
    if (progress.length >= 256 || remaining <= 0) { truncated = true; break; }
    const block = { ...value };
    if (typeof block.text === 'string') {
      const text = block.text;
      const fragment = text.slice(0, remaining);
      if (fragment.length < text.length) truncated = true;
      block.text = fragment;
      remaining -= fragment.length;
      const last = progress[progress.length - 1];
      if (last && Object.keys(block).length === 2 && block.type === 'text' && Object.keys(last).length === 2 && last.type === 'text') {
        last.text = String(last.text) + fragment;
        continue;
      }
    } else {
      const size = String(JSON.stringify(block)).length;
      if (size > remaining) { truncated = true; break; }
      remaining -= size;
    }
    progress.push(block);
  }
  return { progress, progress_truncated: truncated };
}
