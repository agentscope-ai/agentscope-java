/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.harness.planmode;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PlanModeAutoExample — Model-autonomous plan mode: the LLM decides on its own to call
 * {@code plan_enter}, writes a plan, calls {@code plan_exit} to get approval, then enters
 * build mode and starts executing.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>No programmatic entry</b> — the application does not call {@code enterPlanMode()}.
 *       The agent sees {@code plan_enter} in its tool list and decides autonomously whether
 *       the task warrants planning.</li>
 *   <li><b>Full lifecycle</b> — the agent goes through: {@code plan_enter} (read-only) →
 *       {@code plan_write} (write plan) → {@code plan_exit} (HITL approval) → build mode
 *       (execute the plan).</li>
 *   <li><b>plan_exit → build mode</b> — after approval, the agent is back in build mode with
 *       full write access and starts executing its plan.</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.planmode.PlanModeAutoExample
 * </pre>
 */
public class PlanModeAutoExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Plan Mode — Model-Autonomous Entry (plan → approve → build)");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-planmode-auto");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("architect")
                        .sysPrompt(
                                "You are a software architect. For complex, multi-step tasks, "
                                        + "use plan_enter to enter plan mode first. In plan mode, "
                                        + "investigate and write a plan with plan_write, then call "
                                        + "plan_exit to get approval. After approval you are in "
                                        + "build mode — start executing the plan step by step.")
                        .model("qwen-plus")
                        .workspace(workspace)
                        .enablePlanMode()
                        .enableTaskList()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("auto-plan").build();

        System.out.println("Plan mode active before call: " + agent.isPlanModeActive());
        System.out.println("No programmatic enterPlanMode() — the model decides on its own.\n");

        // ── Send a complex task — expect: plan_enter → plan_write → plan_exit → build ──

        System.out.println("── Sending complex task ──\n");

        agent.streamEvents(
                        new UserMessage(
                                "Refactor a monolithic e-commerce application into microservices. "
                                        + "This is a complex task that requires careful planning. "
                                        + "Plan the migration strategy first, get approval, then "
                                        + "outline the implementation for the first service."),
                        ctx)
                .doOnNext(PlanModeAutoExample::handleEvent)
                .blockLast();

        // ── Show results ────────────────────────────────────────────────────

        System.out.println("\n\n── Result ──\n");

        Path planFile = workspace.resolve("plans/PLAN.md");
        if (Files.exists(planFile)) {
            String content = Files.readString(planFile);
            System.out.println("── plans/PLAN.md ──");
            if (content.length() > 600) {
                System.out.println(content.substring(0, 600) + "\n... (truncated)");
            } else {
                System.out.println(content);
            }
        } else {
            Path plansDir = workspace.resolve("plans");
            if (Files.isDirectory(plansDir)) {
                System.out.println("── plans/ directory ──");
                Files.list(plansDir).forEach(p -> System.out.println("  " + p.getFileName()));
            }
        }

        System.out.println("\nPlan mode active after run: " + agent.isPlanModeActive());
        System.out.println(
                "(Expected: false — agent should have exited plan mode and entered build mode)");

        System.out.println("\nWorkspace: " + workspace);
        System.out.println("\n" + "=".repeat(60));
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolCallStartEvent e) {
            System.out.printf("%n[TOOL] %s%n", e.getToolCallName());
        }
    }
}
