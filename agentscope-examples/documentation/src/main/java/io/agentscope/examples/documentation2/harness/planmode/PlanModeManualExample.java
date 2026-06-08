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
 * PlanModeManualExample — Application-driven plan mode: the code calls
 * {@code agent.enterPlanMode(ctx)} to force the agent into the read-only plan phase before sending
 * the user message.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>Programmatic entry</b> — {@code agent.enterPlanMode(ctx)} puts the agent into read-only
 *       mode before it sees the task.</li>
 *   <li><b>Read-only restriction</b> — while in plan mode, only read-only tools and the plan
 *       tools ({@code plan_write}, {@code plan_exit}) are allowed.</li>
 *   <li><b>Plan file</b> — the agent writes its plan to {@code plans/PLAN.md}.</li>
 *   <li><b>HITL exit gate</b> — {@code plan_exit} triggers a permission ASK; the user confirms
 *       before the agent enters build mode.</li>
 *   <li><b>Programmatic exit</b> — {@code agent.exitPlanMode(ctx)} can also be used to
 *       programmatically leave plan mode without HITL.</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.planmode.PlanModeManualExample
 * </pre>
 */
public class PlanModeManualExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Plan Mode — Programmatic Entry (app-driven)");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-planmode-manual");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("planner")
                        .sysPrompt(
                                "You are a software architect. You are currently in plan mode. "
                                        + "Investigate the task, write a plan with plan_write, "
                                        + "then call plan_exit when the plan is ready.")
                        .model("qwen-plus")
                        .workspace(workspace)
                        .enablePlanMode()
                        .enableTaskList()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("manual-plan").build();

        // ── Force agent into plan mode before sending the task ──────────────

        agent.enterPlanMode(ctx);
        System.out.println("enterPlanMode(ctx) → active: " + agent.isPlanModeActive(ctx));
        System.out.println("Agent is now read-only before it sees the task.\n");

        // ── Send the task — agent plans in read-only mode ───────────────────

        System.out.println("── Sending task (agent is already in plan mode) ──\n");

        agent.streamEvents(
                        new UserMessage(
                                "Design the database schema for a blog platform with posts, "
                                        + "comments, and tags. Write a plan, then call plan_exit."),
                        ctx)
                .doOnNext(PlanModeManualExample::handleEvent)
                .blockLast();

        // ── Show plan file and state ────────────────────────────────────────

        System.out.println("\n\n── Result ──\n");
        showPlanFile(workspace);
        System.out.println("Plan mode active after run: " + agent.isPlanModeActive(ctx));

        if (agent.isPlanModeActive(ctx)) {
            agent.exitPlanMode(ctx);
            System.out.println("exitPlanMode(ctx) called → active: " + agent.isPlanModeActive(ctx));
        }

        System.out.println("\nWorkspace: " + workspace);
        System.out.println("\n" + "=".repeat(60));
    }

    private static void showPlanFile(Path workspace) throws Exception {
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
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolCallStartEvent e) {
            System.out.printf("%n[TOOL] %s%n", e.getToolCallName());
        }
    }
}
