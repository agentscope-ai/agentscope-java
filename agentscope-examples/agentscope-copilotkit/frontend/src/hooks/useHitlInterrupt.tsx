import { useInterrupt } from "@copilotkit/react-core/v2";
import { useState } from "react";

import { CHAT_AGENT_ID } from "@/copilot/constants";
import type { DemoState } from "@/copilot/types";
import { Button } from "@/components/ui/button";
import { ToolCard } from "@/components/ToolCard";

type HitlInterrupt = {
  id?: string;
  toolCallId?: string;
  message?: string;
  metadata?: Record<string, unknown>;
};

type ApprovalResult = {
  approved: boolean;
  reason: string;
};

type ResolveInterrupt = (result: ApprovalResult) => void | Promise<unknown>;
type CancelInterrupt = () => void | Promise<unknown>;

/**
 * AG-UI interrupt HITL: backend registers schema-only requestHumanApproval.
 */
export function useHitlInterrupt(updateState: (patch: Partial<DemoState>) => void) {
  useInterrupt({
    agentId: CHAT_AGENT_ID,
    render: ({ interrupt, interrupts, resolve, cancel }) => {
      const items = interrupts.length > 0 ? interrupts : interrupt ? [interrupt] : [];
      const primary = items[0];
      return (
        <HitlInterruptCard
          primary={primary}
          resolve={resolve}
          cancel={cancel}
          updateState={updateState}
        />
      );
    },
  });
}

function HitlInterruptCard({
  primary,
  resolve,
  cancel,
  updateState,
}: {
  primary?: HitlInterrupt;
  resolve: ResolveInterrupt;
  cancel: CancelInterrupt;
  updateState: (patch: Partial<DemoState>) => void;
}) {
  const [rejectReason, setRejectReason] = useState("");
  const metadata = (primary?.metadata ?? {}) as Record<string, unknown>;
  const toolInput = (metadata.toolInput ?? {}) as Record<string, unknown>;
  const summary =
    String(toolInput.summary ?? "") ||
    primary?.message ||
    "请确认是否继续执行该操作。";
  const toolName = String(metadata.toolName ?? "requestHumanApproval");
  const normalizedRejectReason = rejectReason.trim();

  return (
    <ToolCard title="人机交互确认（useInterrupt）" status="awaiting">
      <div className="grid gap-3">
        <p className="text-sm leading-6 text-muted-foreground">{summary}</p>
        <pre className="max-h-44 overflow-auto rounded-xl border border-border bg-background/80 p-3 text-xs leading-5 text-aurora">
          {JSON.stringify(
            {
              interruptId: primary?.id,
              toolCallId: primary?.toolCallId,
              toolName,
              toolInput,
            },
            null,
            2,
          )}
        </pre>
        <label className="grid gap-2 text-sm font-semibold text-foreground">
          拒绝原因
          <textarea
            value={rejectReason}
            onChange={(event) => setRejectReason(event.target.value)}
            rows={3}
            placeholder="例如：信息不完整，需要补充订单编号后再执行。"
            className="min-h-24 resize-y rounded-xl border border-border bg-secondary/40 px-3 py-2 text-sm font-medium leading-6 text-foreground outline-none transition placeholder:text-muted-foreground focus:border-champagne/40 focus:bg-secondary/70 focus:ring-3 focus:ring-ring"
          />
        </label>
        <div className="mt-1 flex flex-wrap items-center gap-3">
          <Button
            type="button"
            className="min-w-24 rounded-full"
            onClick={() => {
              updateState({ approved: true, status: "用户已批准" });
              void resolve({
                approved: true,
                reason: "Approved in the CopilotKit HITL demo.",
              });
            }}
          >
            批准
          </Button>
          <Button
            type="button"
            variant="outline"
            className="min-w-32 rounded-full"
            disabled={!normalizedRejectReason}
            onClick={() => {
              updateState({
                approved: false,
                status: `用户已拒绝：${normalizedRejectReason}`,
              });
              void resolve({
                approved: false,
                reason: normalizedRejectReason,
              });
            }}
          >
            拒绝并提交原因
          </Button>
          <Button
            type="button"
            variant="ghost"
            className="rounded-full text-muted-foreground hover:!text-foreground"
            onClick={() => {
              updateState({ approved: false, status: "用户已取消 interrupt" });
              void cancel();
            }}
          >
            取消
          </Button>
        </div>
      </div>
    </ToolCard>
  );
}
