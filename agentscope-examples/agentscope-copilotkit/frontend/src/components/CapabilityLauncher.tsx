import { Loader2, Play } from "lucide-react";

import { CAPABILITY_PROMPTS } from "@/copilot/constants";
import { useCapabilityRunner } from "@/hooks/useCapabilityRunner";
import { cn } from "@/lib/utils";

/**
 * One button per AgentScope capability, each sending a prompt engineered to trigger exactly that
 * path. Clicking is equivalent to typing the prompt, so the run shows up in the chat transcript.
 */
export function CapabilityLauncher() {
  const { run, pendingId, isRunning } = useCapabilityRunner();

  return (
    <div className="mb-3 grid gap-2 rounded-2xl border border-champagne/20 bg-linear-to-r from-champagne/[0.07] via-secondary/30 to-aurora/[0.07] p-3">
      <div className="flex items-baseline justify-between gap-3">
        <p className="text-sm font-semibold text-foreground">能力速跑</p>
        <p className="text-[0.68rem] text-muted-foreground">点击即发起一次真实运行</p>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {CAPABILITY_PROMPTS.map((item) => {
          const pending = pendingId === item.id;
          return (
            <button
              key={item.id}
              type="button"
              title={item.detail}
              disabled={isRunning || pendingId !== null}
              onClick={() => void run(item.id, item.prompt)}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-semibold transition-all duration-300",
                "border-border bg-card/70 text-muted-foreground",
                "hover:-translate-y-px hover:border-champagne/35 hover:text-foreground hover:shadow-[0_10px_24px_oklch(0_0_0/0.3)]",
                "disabled:pointer-events-none disabled:opacity-45",
                pending && "border-champagne/40 text-champagne",
              )}
            >
              {pending ? (
                <Loader2 className="size-3.5 animate-spin" />
              ) : (
                <Play className="size-3" />
              )}
              {item.title}
            </button>
          );
        })}
      </div>
    </div>
  );
}
