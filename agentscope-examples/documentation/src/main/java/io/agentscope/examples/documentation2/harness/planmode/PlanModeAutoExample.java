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
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PlanModeAutoExample — Model-autonomous plan mode: the LLM decides on its own to call
 * {@code plan_enter}, writes a plan, calls {@code plan_exit} to get approval, then enters
 * build mode and starts executing.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>No programmatic entry</b> — the application does not call {@code enterPlanMode(ctx)}.
 *       The agent sees {@code plan_enter} in its tool list and decides autonomously whether
 *       the task warrants planning.</li>
 *   <li><b>Full lifecycle</b> — the agent goes through: {@code plan_enter} (read-only) →
 *       {@code plan_write} (write plan) → {@code plan_exit} (HITL approval) → build mode
 *       (execute the plan).</li>
 *   <li><b>plan_exit → build mode</b> — after approval, the agent is back in build mode with
 *       full write access and starts executing its plan.</li>
 *   <li><b>HITL approval</b> — {@code plan_exit} always asks for confirmation. The example
 *       listens for {@link RequireUserConfirmEvent}, auto-approves it, and resumes the agent by
 *       sending a follow-up message carrying the {@link ConfirmResult}s. Without this step the
 *       agent would pause forever at {@code plan_exit} and never reach build mode.</li>
 * </ol>
 *
 * <p><b>State isolation:</b> this demo uses an {@link InMemoryAgentStateStore} so every run starts
 * from a clean slate. The default on-disk store is keyed by {@code (agentId, sessionId)} — not by
 * the workspace — so a run that paused on an unconfirmed {@code plan_exit} would persist that
 * dangling ASKING tool call and make the <em>next</em> run fail immediately when it reloads the
 * stale state without supplying confirmation.
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

//        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("architect")
                        .sysPrompt(
                                "You are a software architect. For complex, multi-step tasks, use"
                                    + " plan_enter to enter plan mode first. In plan mode,"
                                    + " investigate and write a plan with plan_write, then call"
                                    + " plan_exit to get approval. After approval you are in build"
                                    + " mode — start executing the plan step by step. If the"
                                    + " workspace is empty (a greenfield project), do not get stuck"
                                    + " investigating — plan the architecture directly and commit"
                                    + " it with plan_write.")
                        .model("dashscope:qwen3.7-plus")
                        .workspace(workspace)
                        // Project-writable: scaffolded code lands in the project directory,
                        // while workspace metadata (memory, sessions, plans) stays in workspace.
                        .filesystem(
                                new LocalFilesystemSpec().projectWritable(true).inheritEnv(true))
                        // Keep runs independent: state lives only for this JVM, so a paused
                        // plan_exit can never leak into the next run.
                        .enablePlanMode()
                        // Opt in: let the model run the shell read-only during plan mode so it can
                        // investigate (cat / ls / grep / git log) and produce a realistic plan.
                        // The plan banner instructs it to keep shell usage read-only; mutating
                        // file-edit tools remain denied until the plan is approved.
                        .allowShellInPlanMode()

                        .enableTaskList()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("auto-plan").build();

        System.out.println("Plan mode active before call: " + agent.isPlanModeActive(ctx));
        System.out.println("No programmatic enterPlanMode(ctx) — the model decides on its own.\n");

        // ── Send a complex task — expect: plan_enter → plan_write → plan_exit → build ──

        System.out.println("── Sending complex task ──\n");

        Msg next =
                new UserMessage(
                        "Design a new microservices-based e-commerce backend from scratch. The"
                            + " workspace is intentionally empty (greenfield) — there is NO"
                            + " existing code to investigate or migrate, so plan the architecture"
                            + " directly. This is a complex task that warrants planning: decide the"
                            + " service boundaries, data ownership, and communication style, write"
                            + " the plan with plan_write, get approval, then scaffold the project"
                            + " layout for the first service.");

        // HITL loop: run the agent, and whenever it pauses for confirmation (plan_exit), approve
        // and resume by sending the ConfirmResults back. The loop ends when a turn completes
        // without requesting any confirmation. A turn cap guards against runaway loops.
        // Track which plan-control tools the model actually invoked, so the final summary can tell
        // "never entered plan mode" apart from "entered and exited" — both end with
        // planActive=false
        // but mean very different things.
        java.util.Set<String> planToolsSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();

        int maxTurns = 8;
        for (int turn = 0; turn < maxTurns && next != null; turn++) {
            AtomicReference<RequireUserConfirmEvent> pending = new AtomicReference<>();

            agent.streamEvents(next, ctx)
                    .doOnNext(PlanModeAutoExample::handleEvent)
                    .doOnNext(
                            event -> {
                                if (event instanceof ToolCallStartEvent tc
                                        && tc.getToolCallName() != null
                                        && tc.getToolCallName().startsWith("plan_")) {
                                    planToolsSeen.add(tc.getToolCallName());
                                }
                                if (event instanceof RequireUserConfirmEvent confirm) {
                                    pending.set(confirm);
                                }
                            })
                    .blockLast();

            RequireUserConfirmEvent confirm = pending.get();
            next = (confirm == null) ? null : approveAll(confirm);
        }

        // ── Show results ────────────────────────────────────────────────────

        System.out.println("\n\n── Result ──\n");

        Path planFile = workspace.resolve("plans/PLAN.md");
        boolean planWritten = Files.exists(planFile);
        boolean planActive = agent.isPlanModeActive(ctx);

        if (planWritten) {
            String content = Files.readString(planFile);
            System.out.println("── plans/PLAN.md ──");
            if (content.length() > 600) {
                System.out.println(content.substring(0, 600) + "\n... (truncated)");
            } else {
                System.out.println(content);
            }
            System.out.println();
        }

        // Classify the terminal state honestly. Two traps to avoid:
        //  1. planActive=false conflates "never entered plan mode" with "entered then exited" —
        // only
        //     the latter is the success path; the former is the model autonomously declining to
        // plan.
        //  2. The model can end a turn with a confident-sounding message ("Let me write the
        //     comprehensive plan:") yet never call plan_write/plan_exit — a "narrate but don't act"
        //     failure that would otherwise read like success.
        boolean planningHappened =
                planToolsSeen.contains("plan_enter")
                        || planToolsSeen.contains("plan_write")
                        || planWritten;

        if (!planActive && !planningHappened) {
            System.out.println(
                    "[OUTCOME] NO PLAN MODE — the agent worked directly in build mode and never"
                        + " called plan_enter. Entering plan mode is autonomous (the model"
                        + " decides), so this is a valid choice; it just means the plan → approve →"
                        + " build lifecycle was not exercised this run.");
            System.out.println(
                    "          Likely cause: the model judged the task simple enough to handle"
                        + " directly without a separate planning phase. Entering plan mode is the"
                        + " model's call, so this can be legitimate.");
            System.out.println(
                    "          What to do: strengthen the system prompt to require plan_enter for"
                        + " this kind of task, or make the task explicitly multi-phase so the model"
                        + " chooses to plan first.");
        } else if (!planActive) {
            System.out.println(
                    "[OUTCOME] OK — the agent entered plan mode and exited via plan_exit into build"
                            + " mode.");
        } else if (planWritten) {
            System.out.println(
                    "[OUTCOME] PARTIAL — a plan was drafted (plans/PLAN.md) but the agent is still"
                        + " in PLAN mode: it never called plan_exit. Review the plan, then send a"
                        + " follow-up message on the same session to approve and continue into"
                        + " build mode.");
        } else {
            System.out.println(
                    "[OUTCOME] NO PLAN PRODUCED — the agent ended its turn still in PLAN mode"
                        + " without writing plans/PLAN.md or calling plan_exit. Its final message"
                        + " may *read* like a plan, but no plan artifact was actually created.");
            System.out.println(
                    "          Likely cause: the model kept deliberating (or returned an empty"
                        + " completion) instead of committing to plan_write — sometimes it tries to"
                        + " investigate the empty greenfield workspace and stalls when it finds"
                        + " nothing.");
            System.out.println(
                    "          What to do: make the task more concrete, or add a system-prompt hint"
                        + " that the workspace is empty by design and it should plan the"
                        + " architecture directly — then send a follow-up message to continue the"
                        + " same session.");
        }

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

    /**
     * Auto-approve every tool call the agent is waiting on, and build the resume message. A real
     * application would surface these to a human and let them approve / reject / edit; here we
     * approve unconditionally so the demo runs end-to-end.
     *
     * <p>The {@link ConfirmResult}s are attached under {@link Msg#METADATA_CONFIRM_RESULTS} so the
     * agent's next call can match them to the pending ASKING tool calls and proceed.
     */
    private static Msg approveAll(RequireUserConfirmEvent confirm) {
        System.out.printf(
                "%n[HITL] Approving %d tool call(s) awaiting confirmation:%n",
                confirm.getToolCalls().size());
        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock toolCall : confirm.getToolCalls()) {
            System.out.println("       - " + toolCall.getName());
            results.add(new ConfirmResult(/* confirmed= */ true, toolCall));
        }
        return UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }
}
