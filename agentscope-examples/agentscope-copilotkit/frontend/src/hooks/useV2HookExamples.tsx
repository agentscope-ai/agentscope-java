import {
  useAgent,
  useCapabilities,
  useComponent,
  useConfigureSuggestions,
  useDefaultRenderTool,
  useFrontendTool,
  useHumanInTheLoop,
  useLearnFromUserAction,
  useLearnFromUserActionInCurrentThread,
  useLearningContainers,
  useLearningContainersInCurrentThread,
  useMemories,
  useRenderActivityMessage,
  useRenderCustomMessages,
  useRenderTool,
  useRenderToolCall,
  useSuggestions,
} from "@copilotkit/react-core/v2";
import { useCallback, useMemo, useState } from "react";
import { z } from "zod";

import { CHAT_AGENT_ID } from "@/copilot/constants";
import type { DemoState, HookDemoStatus } from "@/copilot/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ToolCard } from "@/components/ToolCard";

const updateDemoStateSchema = z.object({
  topic: z.string().optional().describe("当前演示主题"),
  priority: z.enum(["低", "中", "高"]).optional().describe("优先级"),
  status: z.string().optional().describe("当前状态描述"),
});

const orderCardSchema = z.object({
  orderNo: z.string().describe("订单号"),
  amount: z.number().describe("订单金额"),
  status: z.string().describe("订单状态"),
});

const hookBadgeSchema = z.object({
  title: z.string().describe("Hook 名称或卡片标题"),
  status: z.string().describe("当前状态"),
  detail: z.string().optional().describe("补充说明"),
});

const humanApprovalSchema = z.object({
  summary: z.string().describe("需要用户确认的操作摘要"),
});

type HookBadgeProps = z.infer<typeof hookBadgeSchema>;

type AnnotationStatus = {
  label: string;
  value: string;
};

export function useV2HookExamples({
  state,
  updateState,
}: {
  state: DemoState;
  updateState: (patch: Partial<DemoState>) => void;
}) {
  const { agent } = useAgent({ agentId: CHAT_AGENT_ID });
  const capabilities = useCapabilities(CHAT_AGENT_ID);
  const suggestions = useSuggestions({ agentId: CHAT_AGENT_ID });
  const memories = useMemories();
  const learnFromUserAction = useLearnFromUserAction();
  const learnFromCurrentThread = useLearnFromUserActionInCurrentThread();
  const renderToolCall = useRenderToolCall();
  const renderCustomMessage = useRenderCustomMessages();
  const { findRenderer: findActivityRenderer } = useRenderActivityMessage();
  const [annotationStatus, setAnnotationStatus] = useState<AnnotationStatus>({
    label: "annotate",
    value: "未调用",
  });

  useConfigureSuggestions(
    {
      available: "always",
      suggestions: [
        {
          title: "更新状态",
          message: "把演示主题改成“订单审核”，优先级设为高。",
        },
        {
          title: "渲染订单",
          message: "渲染订单卡片：订单 A1001，金额 199.9，状态待支付。",
        },
        {
          title: "HITL",
          message: "调用 confirmDemoAction，摘要为“确认继续执行示例操作”。",
        },
      ],
    },
    [],
  );

  useLearningContainers({
    threadId: agent.threadId,
    learningContainers: ["project"],
  });
  useLearningContainersInCurrentThread({
    learningContainers: ["project"],
  });

  useFrontendTool(
    {
      name: "updateDemoState",
      description: "更新右侧状态管理面板，展示 useFrontendTool 和共享状态。",
      parameters: updateDemoStateSchema,
      handler: async ({ topic, priority, status }) => {
        updateState({
          topic: topic || state.topic,
          priority: priority || state.priority,
          status: status || state.status,
        });
        return { ok: true, updatedAt: new Date().toISOString() };
      },
      render: ({ args, status, result }) => (
        <ToolCard title="useFrontendTool：状态管理" status={status}>
          <p className="mb-3 text-sm leading-6 text-muted-foreground">
            Copilot 正在更新前端共享状态。
          </p>
          <pre className="max-h-56 overflow-auto rounded-xl border border-border bg-background/80 p-3 text-sm text-aurora">
            {JSON.stringify({ args, result }, null, 2)}
          </pre>
        </ToolCard>
      ),
    },
    [state],
  );

  useFrontendTool(
    {
      name: "renderOrderCard",
      description: "返回订单数据，交给 useRenderTool 渲染订单卡片。",
      parameters: orderCardSchema,
      handler: async ({ orderNo, amount, status }) => ({
        orderNo,
        amount,
        status,
        checkedAt: new Date().toLocaleString(),
      }),
    },
    [],
  );

  useRenderTool(
    {
      name: "renderOrderCard",
      parameters: orderCardSchema,
      render: ({ parameters, status, result }) => (
        <ToolCard title="useRenderTool：订单卡片" status={status}>
          <div className="grid gap-2 rounded-2xl border border-champagne/15 bg-linear-to-br from-champagne/10 via-secondary/40 to-aurora/10 p-4">
            <Badge variant="success">{parameters.status ?? "处理中"}</Badge>
            <strong className="font-display text-lg text-foreground">
              {parameters.orderNo ?? "ORDER-1001"}
            </strong>
            <p className="m-0 text-sm text-muted-foreground">
              金额：¥{String(parameters.amount ?? "0")}
            </p>
            {result ? (
              <small className="text-xs text-muted-foreground">
                工具返回：{String(result)}
              </small>
            ) : null}
          </div>
        </ToolCard>
      ),
    },
    [],
  );

  useComponent(
    {
      name: "showHookBadge",
      description: "展示一个 v2 hook 状态徽章。",
      parameters: hookBadgeSchema,
      render: HookBadge,
      followUp: false,
    },
    [],
  );

  useHumanInTheLoop(
    {
      name: "confirmDemoAction",
      description: "请求用户确认后再继续，展示 useHumanInTheLoop。",
      parameters: humanApprovalSchema,
      render: ({ args, status, respond }) => (
        <ToolCard title="useHumanInTheLoop：确认操作" status={status}>
          <p className="mb-3 text-sm leading-6 text-muted-foreground">
            {String(args.summary ?? "请确认是否继续执行该操作。")}
          </p>
          {respond ? (
            <div className="mt-1 flex flex-wrap items-center gap-3">
              <Button
                type="button"
                className="min-w-24 rounded-full"
                onClick={() => void respond({ approved: true, source: "useHumanInTheLoop" })}
              >
                同意
              </Button>
              <Button
                type="button"
                variant="outline"
                className="min-w-24 rounded-full"
                onClick={() => void respond({ approved: false, source: "useHumanInTheLoop" })}
              >
                拒绝
              </Button>
            </div>
          ) : null}
        </ToolCard>
      ),
    },
    [],
  );

  useDefaultRenderTool();

  const renderToolPreview = useMemo(
    () =>
      renderToolCall({
        toolCall: {
          id: "preview-render-order-card",
          type: "function",
          function: {
            name: "renderOrderCard",
            arguments: JSON.stringify({
              orderNo: "PREVIEW-1001",
              amount: 88.8,
              status: "预览",
            }),
          },
        } as never,
      }),
    [renderToolCall],
  );

  const customMessageRendererReady = Boolean(renderCustomMessage);
  const activityRendererReady = Boolean(findActivityRenderer("*"));

  const recordAction = useCallback(async () => {
    setAnnotationStatus({ label: "useLearnFromUserAction", value: "提交中..." });
    try {
      const result = await learnFromUserAction({
        threadId: agent.threadId,
        title: "用户点击了 v2 hooks 示例按钮",
        data: { topic: state.topic, priority: state.priority },
      });
      setAnnotationStatus({
        label: "useLearnFromUserAction",
        value: `成功：${result.id}`,
      });
    } catch (error) {
      setAnnotationStatus({
        label: "useLearnFromUserAction",
        value: error instanceof Error ? error.message : String(error),
      });
    }
  }, [agent.threadId, learnFromUserAction, state.priority, state.topic]);

  const recordCurrentThreadAction = useCallback(async () => {
    setAnnotationStatus({
      label: "useLearnFromUserActionInCurrentThread",
      value: "提交中...",
    });
    try {
      const result = await learnFromCurrentThread({
        title: "当前线程记录示例动作",
        data: { updatedAt: state.updatedAt },
      });
      setAnnotationStatus({
        label: "useLearnFromUserActionInCurrentThread",
        value: `成功：${result.id}`,
      });
    } catch (error) {
      setAnnotationStatus({
        label: "useLearnFromUserActionInCurrentThread",
        value: error instanceof Error ? error.message : String(error),
      });
    }
  }, [learnFromCurrentThread, state.updatedAt]);

  const addDemoMemory = useCallback(async () => {
    setAnnotationStatus({ label: "useMemories.addMemory", value: "提交中..." });
    try {
      await memories.addMemory({
        kind: "operational",
        content: "Prefer concise AgentScope CopilotKit demo responses.",
      });
      setAnnotationStatus({ label: "useMemories.addMemory", value: "成功" });
    } catch (error) {
      setAnnotationStatus({
        label: "useMemories.addMemory",
        value: error instanceof Error ? error.message : String(error),
      });
    }
  }, [memories]);

  const statuses: HookDemoStatus[] = [
    { label: "useAgent", value: agent.isRunning ? "running" : "idle" },
    { label: "messages", value: String(agent.messages.length) },
    { label: "threadId", value: agent.threadId },
    { label: "useCapabilities", value: capabilities ? "ready" : "pending" },
    { label: "useSuggestions", value: `${suggestions.suggestions.length} 条` },
    { label: "useMemories", value: memories.isAvailable ? "available" : "unavailable" },
    { label: "memory realtime", value: memories.realtimeStatus },
    { label: "useRenderCustomMessages", value: customMessageRendererReady ? "ready" : "no renderer" },
    { label: "useRenderActivityMessage", value: activityRendererReady ? "ready" : "no renderer" },
  ];

  return {
    agent,
    capabilities,
    suggestions,
    memories,
    annotationStatus,
    statuses,
    renderToolPreview,
    recordAction,
    recordCurrentThreadAction,
    addDemoMemory,
  };
}

function HookBadge({ title, status, detail }: HookBadgeProps) {
  return (
    <div className="grid gap-2 rounded-2xl border border-champagne/20 bg-linear-to-br from-champagne/10 to-aurora/10 p-4">
      <Badge variant="success" className="w-fit">
        {status}
      </Badge>
      <strong className="font-display text-lg text-foreground">{title}</strong>
      {detail ? <p className="text-sm text-muted-foreground">{detail}</p> : null}
    </div>
  );
}
