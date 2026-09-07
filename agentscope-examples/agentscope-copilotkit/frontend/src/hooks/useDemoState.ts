import { useAgent, useAgentContext } from "@copilotkit/react-core/v2";
import { useCallback, useEffect, useMemo } from "react";

import {
  WORKBENCH_CATALOG_ID,
  WORKBENCH_COMPONENT_NAMES,
} from "@/a2ui/workbenchCatalog";
import { CHAT_AGENT_ID } from "@/copilot/constants";
import { type DemoState, emptyPlanState, initialDemoState } from "@/copilot/types";

/** Matches `WorkbenchEventMiddleware.CONTEXT_INLINE_CATALOG` on the Java side. */
const CATALOG_CONTEXT_DESCRIPTION = "a2ui-inline-catalog";

/**
 * Bridges the agent's shared state into React.
 *
 * The nested objects are defaulted individually rather than with a single spread: an incoming
 * snapshot that omits `plan` or `a2ui` (an older run, or a thread that never used them) would
 * otherwise leave those fields undefined and crash the panels reading them.
 */
export function useDemoState() {
  const { agent } = useAgent({ agentId: CHAT_AGENT_ID });

  const state = useMemo<DemoState>(() => {
    const incoming = (agent.state ?? {}) as Partial<DemoState>;
    return {
      ...initialDemoState,
      ...incoming,
      plan: { ...emptyPlanState, ...(incoming.plan ?? {}) },
      permissionAudit: incoming.permissionAudit ?? [],
      metrics: incoming.metrics ?? {},
      a2ui: { ...initialDemoState.a2ui, ...(incoming.a2ui ?? {}) },
    };
  }, [agent.state]);

  const updateState = useCallback(
    (patch: Partial<DemoState>) => {
      agent.setState({
        ...state,
        ...patch,
        updatedAt: new Date().toLocaleTimeString(),
      });
    },
    [agent, state],
  );

  useEffect(() => {
    if (!agent.state || Object.keys(agent.state).length === 0) {
      agent.setState(initialDemoState);
    }
  }, [agent]);

  useAgentContext({
    description: "当前 AgentScope 工作台的共享状态",
    value: state,
  });

  // Capability handshake: the agent composes A2UI against exactly the components this build can
  // paint, so adding or removing a renderer never produces a surface the browser drops.
  useAgentContext({
    description: CATALOG_CONTEXT_DESCRIPTION,
    value: {
      catalogId: WORKBENCH_CATALOG_ID,
      components: WORKBENCH_COMPONENT_NAMES,
    },
  });

  return { state, updateState };
}
