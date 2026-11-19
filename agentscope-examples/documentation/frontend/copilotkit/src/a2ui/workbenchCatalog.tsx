import { createCatalog, type CatalogDefinitions } from "@copilotkit/a2ui-renderer";
import { ArrowDownRight, ArrowUpRight, Minus } from "lucide-react";
// The renderer's catalog API is typed against Zod 3, while the app itself runs Zod 4
// (CopilotKit's frontend-tool hooks need v4). The `zod3` alias keeps both on their own version.
import { z } from "zod3";

import { cn } from "@/lib/utils";

/**
 * Catalog id shared with the backend (`WorkbenchCatalog.CATALOG_ID` in Java).
 *
 * <p>Every `createSurface` the agent emits carries this id, which is how the renderer knows the
 * custom components below are available.
 */
export const WORKBENCH_CATALOG_ID = "agentscope.io:workbench";

/**
 * Platform-agnostic contract: what the agent may render, and what each prop means.
 *
 * The descriptions are not decoration — they are what the composing model reads, so they are
 * written as instructions rather than as documentation.
 */
export const workbenchDefinitions = {
  MetricTile: {
    description:
      "单个关键指标，突出显示数值。用于 QPS、错误率、延迟这类可量化数据，横向排列在 Row 中效果最好。",
    props: z.object({
      label: z.string().describe("指标名称"),
      value: z.string().describe("已格式化的指标数值"),
      delta: z.string().optional().describe("环比变化，例如 +12.4%"),
      trend: z.enum(["up", "down", "flat"]).optional().describe("变化方向"),
      accent: z
        .enum(["champagne", "aurora", "danger"])
        .optional()
        .describe("强调色；danger 用于越过阈值的指标"),
    }),
  },
  RiskGauge: {
    description: "0-100 的风险评分条，超过阈值时自动转为警示色。用于稳定性、容量、安全风险等评估。",
    props: z.object({
      label: z.string().describe("风险维度名称"),
      score: z.number().describe("0-100 的风险分"),
      threshold: z.number().optional().describe("告警阈值，默认 70"),
      caption: z.string().optional().describe("补充说明"),
    }),
  },
  TimelineStep: {
    description: "计划中的一个步骤，带序号与状态点。多个 TimelineStep 放进同一个 Column 即构成时间线。",
    props: z.object({
      index: z.number().describe("步骤序号，从 1 开始"),
      title: z.string().describe("步骤标题"),
      state: z.enum(["pending", "in_progress", "completed"]).describe("步骤状态"),
      note: z.string().optional().describe("步骤备注"),
    }),
  },
  ApprovalCallout: {
    description: "结论式提示块，用于表达授权结果、风险警告或需要用户注意的结论。",
    props: z.object({
      title: z.string().describe("提示标题"),
      body: z.string().describe("提示正文"),
      tone: z.enum(["info", "warn", "danger"]).optional().describe("语气，默认 info"),
    }),
  },
  StatBadge: {
    description: "短徽标，用于状态词、环境名、版本号等一两个词的信息。",
    props: z.object({
      text: z.string().describe("徽标文案"),
      tone: z.enum(["neutral", "success", "warn", "danger"]).optional().describe("语气"),
    }),
  },
} satisfies CatalogDefinitions;

/** Component names the browser can paint; reported to the agent as run context. */
export const WORKBENCH_COMPONENT_NAMES = Object.keys(workbenchDefinitions);

const ACCENT_RING: Record<string, string> = {
  champagne: "border-champagne/25 bg-champagne/[0.07]",
  aurora: "border-aurora/25 bg-aurora/[0.07]",
  danger: "border-destructive/30 bg-destructive/[0.08]",
};

const ACCENT_TEXT: Record<string, string> = {
  champagne: "text-champagne",
  aurora: "text-aurora",
  danger: "text-destructive",
};

const TONE_RING: Record<string, string> = {
  info: "border-aurora/25 bg-aurora/[0.07] text-aurora",
  warn: "border-champagne/30 bg-champagne/[0.08] text-champagne",
  danger: "border-destructive/30 bg-destructive/[0.08] text-destructive",
};

const BADGE_TONE: Record<string, string> = {
  neutral: "border-border bg-secondary/60 text-muted-foreground",
  success: "border-aurora/30 bg-aurora/10 text-aurora",
  warn: "border-champagne/30 bg-champagne/10 text-champagne",
  danger: "border-destructive/30 bg-destructive/10 text-destructive",
};

const STEP_DOT: Record<string, string> = {
  pending: "border-border bg-transparent",
  in_progress: "border-champagne bg-champagne/30 shadow-[0_0_0_4px_oklch(0.84_0.065_88/0.12)]",
  completed: "border-aurora bg-aurora",
};

const STEP_LABEL: Record<string, string> = {
  pending: "待开始",
  in_progress: "进行中",
  completed: "已完成",
};

/**
 * The catalog handed to `<CopilotKit a2ui={{ catalog }}>`.
 *
 * `includeBasicCatalog` merges A2UI's built-in Text / Card / Row / Column / Button set in, so the
 * agent can mix layout primitives with the workbench components above.
 */
export const workbenchCatalog = createCatalog(
  workbenchDefinitions,
  {
    MetricTile: ({ props }) => {
      const accent = props.accent ?? "champagne";
      const TrendIcon =
        props.trend === "up" ? ArrowUpRight : props.trend === "down" ? ArrowDownRight : Minus;
      return (
        <div
          className={cn(
            "grid min-w-40 gap-1 rounded-2xl border px-4 py-3 transition-shadow duration-300 hover:shadow-[0_12px_32px_oklch(0_0_0/0.28)]",
            ACCENT_RING[accent],
          )}
        >
          <span className="text-[0.7rem] font-semibold tracking-wide text-muted-foreground uppercase">
            {props.label}
          </span>
          <span className="font-display text-2xl leading-tight font-semibold text-foreground">
            {props.value}
          </span>
          {props.delta ? (
            <span
              className={cn("flex items-center gap-1 text-xs font-semibold", ACCENT_TEXT[accent])}
            >
              <TrendIcon className="size-3.5" />
              {props.delta}
            </span>
          ) : null}
        </div>
      );
    },

    RiskGauge: ({ props }) => {
      const threshold = props.threshold ?? 70;
      const score = Math.max(0, Math.min(100, props.score));
      const breached = score >= threshold;
      return (
        <div className="grid gap-2 rounded-2xl border border-border bg-secondary/30 px-4 py-3">
          <div className="flex items-baseline justify-between gap-3">
            <span className="text-sm font-semibold text-foreground">{props.label}</span>
            <span
              className={cn(
                "font-display text-lg font-semibold",
                breached ? "text-destructive" : "text-aurora",
              )}
            >
              {score}
            </span>
          </div>
          <div className="relative h-1.5 overflow-hidden rounded-full bg-border/60">
            <div
              className={cn(
                "absolute inset-y-0 left-0 rounded-full transition-[width] duration-700",
                breached ? "bg-destructive" : "bg-aurora",
              )}
              style={{ width: `${score}%` }}
            />
            <div
              className="absolute inset-y-0 w-px bg-champagne/70"
              style={{ left: `${threshold}%` }}
              aria-hidden
            />
          </div>
          <span className="text-xs text-muted-foreground">
            {props.caption ?? `告警阈值 ${threshold}`}
          </span>
        </div>
      );
    },

    TimelineStep: ({ props }) => (
      <div className="grid grid-cols-[auto_1fr] items-start gap-3 rounded-xl px-2 py-1.5">
        <div className="grid justify-items-center gap-1 pt-1">
          <span className={cn("size-3 rounded-full border-2 transition-all", STEP_DOT[props.state])} />
          <span className="text-[0.65rem] font-semibold text-muted-foreground">{props.index}</span>
        </div>
        <div className="grid gap-0.5">
          <span
            className={cn(
              "text-sm font-semibold",
              props.state === "completed" ? "text-muted-foreground" : "text-foreground",
            )}
          >
            {props.title}
          </span>
          <span className="text-xs text-muted-foreground">
            {props.note ?? STEP_LABEL[props.state]}
          </span>
        </div>
      </div>
    ),

    ApprovalCallout: ({ props }) => (
      <div className={cn("grid gap-1 rounded-2xl border px-4 py-3", TONE_RING[props.tone ?? "info"])}>
        <span className="text-sm font-semibold">{props.title}</span>
        <span className="text-xs leading-5 text-muted-foreground">{props.body}</span>
      </div>
    ),

    StatBadge: ({ props }) => (
      <span
        className={cn(
          "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold",
          BADGE_TONE[props.tone ?? "neutral"],
        )}
      >
        {props.text}
      </span>
    ),
  },
  { catalogId: WORKBENCH_CATALOG_ID, includeBasicCatalog: true },
);

/** Theme forwarded to the A2UI provider; the basic catalog derives highlights from it. */
export const workbenchA2uiTheme = {
  primaryColor: "#e3c48f",
  agentDisplayName: "AgentScope Workbench",
};
