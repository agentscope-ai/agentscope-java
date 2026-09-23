import { useAgent, useCopilotKit } from "@copilotkit/react-core/v2";
import { useCallback, useState } from "react";

import { CHAT_AGENT_ID } from "@/copilot/constants";

/**
 * Sends a canned prompt to the workbench agent, the same way the chat input would.
 *
 * `addMessage` then `runAgent` is CopilotKit v2's programmatic equivalent of typing and pressing
 * enter: the message lands in the shared thread, so the demo buttons and the chat box stay one
 * conversation rather than two.
 */
export function useCapabilityRunner() {
  const { copilotkit } = useCopilotKit();
  const { agent } = useAgent({ agentId: CHAT_AGENT_ID });
  const [pendingId, setPendingId] = useState<string | null>(null);

  const run = useCallback(
    async (id: string, prompt: string) => {
      if (pendingId || agent.isRunning) return;
      setPendingId(id);
      try {
        agent.addMessage({ id: crypto.randomUUID(), role: "user", content: prompt });
        await copilotkit.runAgent({ agent });
      } catch (error) {
        console.error("capability prompt failed", error);
      } finally {
        setPendingId(null);
      }
    },
    [agent, copilotkit, pendingId],
  );

  return { run, pendingId, isRunning: agent.isRunning };
}
