/** Task states mirrored from AgentScope's `Task.State` wire values. */
export type PlanTaskState = "pending" | "in_progress" | "completed";

export type PlanTask = {
  id: string;
  title: string;
  state: PlanTaskState;
  priority?: string;
};

export type PlanState = {
  goal: string | null;
  /** idle → planning → executing → completed */
  phase: string;
  tasks: PlanTask[];
  total: number;
  completed: number;
  /** Completion percentage, 0-100. */
  progress: number;
};

/** One decision made by the permission engine, newest first. */
export type PermissionAuditEntry = {
  tool: string;
  /** ASK while waiting on the user, then ALLOW or DENY once decided. */
  behavior: "ASK" | "ALLOW" | "DENY" | string;
  detail: string;
  granted: boolean;
  at: string;
};

export type A2uiSurfaceInfo = {
  surfaceId: string | null;
  catalogId: string | null;
  /** Which composer produced the surface: a model id, or `fallback`. */
  generatedBy: string | null;
  intent: string | null;
  componentCount: number;
};

/**
 * The shared state object, shaped exactly like `WorkbenchState#snapshot()` on the Java side.
 *
 * Writes from the browser (`agent.setState`) travel back with the next run and are merged by
 * `WorkbenchState#mergeFromClient`, so the two halves stay in sync in both directions.
 */
export type DemoState = {
  topic: string;
  priority: "低" | "中" | "高";
  status: string;
  approved: boolean;
  updatedAt: string;
  plan: PlanState;
  permissionAudit: PermissionAuditEntry[];
  metrics: Record<string, string | number>;
  a2ui: A2uiSurfaceInfo;
};

export type HookDemoStatus = {
  label: string;
  value: string;
};

export type ToolStatus = string;

export const emptyPlanState: PlanState = {
  goal: null,
  phase: "idle",
  tasks: [],
  total: 0,
  completed: 0,
  progress: 0,
};

export const initialDemoState: DemoState = {
  topic: "AgentScope 2.0 × CopilotKit 能力演示",
  priority: "中",
  status: "等待用户输入",
  approved: false,
  updatedAt: "-",
  plan: emptyPlanState,
  permissionAudit: [],
  metrics: {},
  a2ui: {
    surfaceId: null,
    catalogId: null,
    generatedBy: null,
    intent: null,
    componentCount: 0,
  },
};
