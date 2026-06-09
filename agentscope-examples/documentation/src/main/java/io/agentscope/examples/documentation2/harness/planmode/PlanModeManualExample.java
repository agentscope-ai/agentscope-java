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
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PlanModeManualExample — Application-driven plan mode, run as an <b>interactive terminal session</b>
 * so you can experience the full plan → approve → build switch yourself.
 *
 * <p>The code calls {@code agent.enterPlanMode(ctx)} to force the agent into the read-only plan
 * phase before it sees the task. You then type a task, watch the agent investigate and write a
 * plan, and when it calls {@code plan_exit} the run pauses for <b>your</b> approval at the terminal:
 *
 * <ol>
 *   <li><b>Programmatic entry</b> — {@code agent.enterPlanMode(ctx)} puts the agent into read-only
 *       mode before it sees the task.</li>
 *   <li><b>Read-only restriction</b> — while in plan mode only read-only tools and the plan tools
 *       ({@code plan_write} / {@code plan_exit}) are allowed.</li>
 *   <li><b>HITL exit gate</b> — {@code plan_exit} emits a {@link RequireUserConfirmEvent}; the
 *       example shows you the drafted plan and asks you to approve (switch to BUILD mode) or reject
 *       (keep planning). Approval is sent back as a {@link ConfirmResult} to resume the agent.</li>
 *   <li><b>Build phase</b> — after you approve, the agent leaves plan mode and starts executing.</li>
 * </ol>
 *
 * <p>{@code agent.exitPlanMode(ctx)} is also available to leave plan mode programmatically without
 * HITL, but this example intentionally drives the exit through the human approval gate.
 *
 * <p><b>Run (reads from stdin — run in a real terminal):</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.planmode.PlanModeManualExample
 * </pre>
 */
public class PlanModeManualExample {

    private static final String DEFAULT_TASK =
            "Design the database schema for a blog platform with posts, comments, and tags. "
                    + "Write the plan with plan_write, then call plan_exit when it is ready.";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Plan Mode — Programmatic Entry, Interactive HITL Exit");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-planmode-manual");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("planner")
                        .sysPrompt(
                                "You are a software architect. You are currently in plan mode. "
                                        + "Investigate the task, write a plan with plan_write, "
                                        + "then call plan_exit when the plan is ready.")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .enablePlanMode()
                        .enableTaskList()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("manual-plan").build();
        Scanner in = new Scanner(System.in);

        // ── Programmatic entry: force plan mode before the agent sees the task ──
        agent.enterPlanMode(ctx);
        System.out.println("enterPlanMode(ctx) → active: " + agent.isPlanModeActive(ctx));
        System.out.println("The agent is read-only until you approve plan_exit.\n");

        System.out.println("Enter a task for the planner (or press Enter for the default):");
        System.out.print("> ");
        String typed = in.hasNextLine() ? in.nextLine().trim() : "";
        String task = typed.isEmpty() ? DEFAULT_TASK : typed;
        System.out.println();

        Msg next = new UserMessage(task);

        // Interactive loop: stream a turn; if the agent pauses for plan_exit confirmation, ask the
        // user; if it yields with text while still planning, let the user reply; stop once it
        // completes a turn in build mode. A turn cap guards against runaway loops.
        int maxTurns = 12;
        for (int turn = 0; turn < maxTurns && next != null; turn++) {
            AtomicReference<RequireUserConfirmEvent> pending = new AtomicReference<>();

            agent.streamEvents(next, ctx)
                    .doOnNext(PlanModeManualExample::handleEvent)
                    .doOnNext(
                            event -> {
                                if (event instanceof RequireUserConfirmEvent confirm) {
                                    pending.set(confirm);
                                }
                            })
                    .blockLast();

            RequireUserConfirmEvent confirm = pending.get();
            if (confirm != null) {
                next = confirmInteractively(confirm, in, workspace);
            } else if (agent.isPlanModeActive(ctx)) {
                // Still planning but no approval was requested — the agent yielded with text
                // (e.g. a clarifying question). Let the user reply or quit.
                System.out.println("\n\n(agent is still in PLAN mode and waiting for your input)");
                next = promptFollowUp(in);
            } else {
                // A build-phase turn finished with no further confirmation needed.
                next = null;
            }
        }

        // ── Result ──────────────────────────────────────────────────────────
        System.out.println("\n\n── Result ──\n");
        showPlanFile(workspace);
        boolean planActive = agent.isPlanModeActive(ctx);
        if (planActive) {
            System.out.println(
                    "[OUTCOME] Still in PLAN mode — plan_exit was not approved, so the agent never"
                            + " switched to build mode.");
        } else {
            System.out.println(
                    "[OUTCOME] Switched to BUILD mode — you approved plan_exit and the agent left"
                            + " the read-only plan phase.");
        }

        System.out.println("\nWorkspace: " + workspace);
        System.out.println("\n" + "=".repeat(60));
    }

    /**
     * Shows the drafted plan and asks the user to approve or reject each tool call awaiting
     * confirmation (typically {@code plan_exit}). Builds the resume message carrying the
     * {@link ConfirmResult}s under {@link Msg#METADATA_CONFIRM_RESULTS}.
     */
    private static Msg confirmInteractively(
            RequireUserConfirmEvent confirm, Scanner in, Path workspace) throws Exception {
        System.out.println("\n\n" + "─".repeat(60));
        System.out.println("[HITL] The agent wants to leave PLAN mode and start executing.");
        System.out.println("Review the drafted plan below, then approve or reject.\n");
        showPlanFile(workspace);

        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock call : confirm.getToolCalls()) {
            System.out.printf(
                    "%nApprove '%s'? [y = switch to BUILD mode / anything else = keep planning]: ",
                    call.getName());
            String ans = in.hasNextLine() ? in.nextLine().trim().toLowerCase() : "";
            boolean approved = ans.equals("y") || ans.equals("yes");
            results.add(new ConfirmResult(approved, call));
            System.out.println(
                    approved
                            ? "  → approved; switching to BUILD mode"
                            : "  → rejected; staying in PLAN mode");
        }
        return UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }

    /** Reads a free-form follow-up message from the terminal; {@code null} (quit) on blank / 'q'. */
    private static Msg promptFollowUp(Scanner in) {
        System.out.print("\nYour reply (or 'q' to quit): ");
        String line = in.hasNextLine() ? in.nextLine().trim() : "q";
        if (line.isEmpty() || line.equalsIgnoreCase("q")) {
            return null;
        }
        return new UserMessage(line);
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
            System.out.println("(no plans/PLAN.md written yet)");
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
