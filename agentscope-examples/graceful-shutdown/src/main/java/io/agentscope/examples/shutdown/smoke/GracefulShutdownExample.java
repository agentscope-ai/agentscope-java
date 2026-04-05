/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.shutdown.smoke;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Demonstrates graceful shutdown with session persistence and recovery.
 *
 * <p>This example runs two scenarios back-to-back:
 *
 * <ol>
 *   <li><b>Scenario 1 — Tool execution timeout</b>: The agent calls a slow tool (sleeps 15s).
 *       Graceful shutdown is triggered 3s into the call; the 5s timeout saves the session and
 *       interrupts the agent. After the tool finishes, the agent observes the interrupt flag
 *       and throws {@link AgentShuttingDownException}.
 *   <li><b>Scenario 1 Resume</b>: A fresh agent loads the saved session and continues from the
 *       previous context, proving that state was correctly persisted.
 *   <li><b>Scenario 2 — Reasoning timeout</b>: A complex prompt forces a long LLM reasoning
 *       phase. Shutdown is triggered early; if the model is still streaming when the timeout
 *       fires, the agent is interrupted mid-reasoning and the partial content is saved (or
 *       discarded, depending on {@link PartialReasoningPolicy}).
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 * export DASHSCOPE_API_KEY=sk-xxx
 * mvn -pl agentscope-examples/graceful-shutdown exec:java \
 *     -Dexec.mainClass=io.agentscope.examples.shutdown.GracefulShutdownExample
 * }</pre>
 */
public class GracefulShutdownExample {

    private static final String SESSION_ID = "graceful_shutdown_demo";
    private static final GracefulShutdownManager shutdownManager =
            GracefulShutdownManager.getInstance();

    public static void main(String[] args) throws Exception {
        String apiKey = getDashScopeApiKey();

        Session session = new InMemorySession();

        // ======== Scenario 1: Shutdown during tool execution ========
        scenario1_toolExecutionTimeout(apiKey, session);

        // ======== Scenario 1 Resume: Load session and continue ========
        scenario1_resume(apiKey, session);

        // ======== Scenario 2: Shutdown during reasoning ========
        scenario2_reasoningTimeout(apiKey, session);

        // ======== Scenario 2 Resume: Continue reasoning from partial state ========
        scenario2_resume(apiKey, session);

        // ======== Scenario 3: Tool execution exceeds timeout, force interrupted ========
        scenario3_toolExceedsTimeout(apiKey, session);

        // ======== Scenario 3 Resume: Continue after tool timeout ========
        scenario3_resume(apiKey, session);

        // ======== Scenario 4: Catch shutdown exception and do business operations ========
        scenario4_businessRecovery(apiKey, session);

        System.out.println("\n=== All scenarios completed ===");
        System.exit(0);
    }

    // ==================== Scenario 1: Tool Execution Timeout ====================

    private static final String SCENARIO1_SYS_PROMPT =
            "You are a data analysis assistant. "
                    + "When asked to analyze a dataset, follow these steps in order:\n"
                    + "1. Call analyze_dataset to get the raw statistics\n"
                    + "2. Based on the analysis results, write a detailed report including "
                    + "key findings, anomaly details, revenue trends, and your recommendations\n"
                    + "Do not skip any step. Do not ask clarifying questions.";

    private static void scenario1_toolExecutionTimeout(String apiKey, Session session)
            throws Exception {
        printBanner("Scenario 1: Graceful shutdown during tool execution");
        System.out.println(
                "The agent has a 2-step task: (1) call analyze_dataset (slow, 15s),\n"
                        + "then (2) write a detailed report based on the results.\n"
                        + "Shutdown triggers at t=5s with 5s timeout. The tool finishes at\n"
                        + "~17s, then the agent observes the interrupt and throws\n"
                        + "AgentShuttingDownException. Step 1 result is saved to session.\n"
                        + "On resume, the agent reads the analysis from memory and writes\n"
                        + "the report — no re-execution of the slow tool.\n");

        shutdownManager.resetForTesting();
        shutdownManager.setConfig(
                new GracefulShutdownConfig(Duration.ofSeconds(5), PartialReasoningPolicy.SAVE));

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DataAnalysisTool());

        ReActAgent agent = buildAgent(apiKey, toolkit, SCENARIO1_SYS_PROMPT);

        agent.loadIfExists(session, SESSION_ID);

        Msg userMsg = textMsg("Analyze the customer_orders dataset and provide a full report.");

        System.out.println("[t=0s] User> " + extractText(userMsg));
        System.out.println("[t=0s] Starting agent...\n");

        long startTime = System.currentTimeMillis();

        CompletableFuture<Msg> future =
                CompletableFuture.supplyAsync(() -> agent.call(userMsg).block());

        Thread.sleep(5000);
        long triggerElapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("\n[t=" + triggerElapsed + "s] >>> Triggering graceful shutdown <<<");
        System.out.println(
                "[t="
                        + triggerElapsed
                        + "s] Active requests: "
                        + shutdownManager.getActiveRequestCount()
                        + "\n");
        shutdownManager.performGracefulShutdown();

        try {
            Msg response = future.get(30, TimeUnit.SECONDS);
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println(
                    "[t="
                            + elapsed
                            + "s] [Result] Agent responded normally: "
                            + extractText(response));
        } catch (ExecutionException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            Throwable cause = e.getCause();
            if (cause instanceof AgentShuttingDownException) {
                System.out.println(
                        "[t="
                                + elapsed
                                + "s] [Result] AgentShuttingDownException caught: "
                                + cause.getMessage());
            } else {
                System.out.println(
                        "[t=" + elapsed + "s] [Result] Agent error: " + cause.getMessage());
            }
        } catch (TimeoutException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println(
                    "[t=" + elapsed + "s] [Result] Agent still stuck (cooperative interruption).");
            future.cancel(true);
        }

        printMemory("Saved memory", agent);
        agent.saveTo(session, SESSION_ID);
    }

    // ==================== Scenario 1 Resume ====================

    private static void scenario1_resume(String apiKey, Session session) throws Exception {
        printBanner("Scenario 1 Resume: Load saved session and continue");
        System.out.println(
                "The client sends 'continue' after loading the saved session.\n"
                        + "The agent sees the completed analysis (step 1) in memory and\n"
                        + "proceeds directly to step 2 (generate_summary), skipping the\n"
                        + "slow analysis entirely.\n");

        shutdownManager.resetForTesting();
        shutdownManager.setConfig(GracefulShutdownConfig.DEFAULT);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DataAnalysisTool());

        ReActAgent agent = buildAgent(apiKey, toolkit, SCENARIO1_SYS_PROMPT);

        boolean loaded = agent.loadIfExists(session, SESSION_ID);
        if (!loaded) {
            System.out.println("No saved session found. Skipping resume scenario.\n");
            return;
        }

        printMemory("Loaded memory from session", agent);

        Msg retryMsg = textMsg("continue");

        System.out.println("User> " + extractText(retryMsg));
        System.out.println("Agent> ");

        Msg response = agent.call(retryMsg).block();
        if (response != null) {
            System.out.println(extractText(response));
        }

        agent.saveTo(session, SESSION_ID);
        System.out.println("\n[OK] Session updated after resume.\n");
    }

    // ==================== Scenario 2: Reasoning Timeout ====================

    private static final String SCENARIO2_SYS_PROMPT =
            "You are a senior researcher. Always provide extremely detailed, "
                    + "thorough, step-by-step analysis. Never give short answers.";

    private static final String SCENARIO2_SESSION_ID = "graceful_shutdown_demo_reasoning";

    private static void scenario2_reasoningTimeout(String apiKey, Session session)
            throws Exception {
        printBanner("Scenario 2: Graceful shutdown during reasoning");
        System.out.println(
                "A complex prompt triggers a long reasoning phase. Shutdown is triggered\n"
                        + "almost immediately with a short timeout. If the model is still\n"
                        + "streaming when the timeout fires, the agent is interrupted\n"
                        + "mid-reasoning. Partial content is saved (policy=SAVE).\n"
                        + "On resume, the agent continues the analysis from where it stopped.\n");

        shutdownManager.resetForTesting();
        shutdownManager.setConfig(
                new GracefulShutdownConfig(Duration.ofSeconds(3), PartialReasoningPolicy.SAVE));

        ReActAgent agent = buildAgent(apiKey, new Toolkit(), SCENARIO2_SYS_PROMPT);

        agent.loadIfExists(session, SCENARIO2_SESSION_ID);

        Msg userMsg =
                textMsg(
                        "Please write a comprehensive analysis of how quantum computing "
                                + "will impact modern cryptography. Cover RSA, AES, ECC, "
                                + "post-quantum cryptography approaches, and timeline predictions. "
                                + "Be extremely detailed and thorough in each section.");

        System.out.println("[t=0s] User> " + extractText(userMsg));
        System.out.println("[t=0s] Starting agent...\n");

        long startTime = System.currentTimeMillis();

        CompletableFuture<Msg> future =
                CompletableFuture.supplyAsync(() -> agent.call(userMsg).block());

        Thread.sleep(1000);
        System.out.println("\n[t=1s] >>> Triggering graceful shutdown <<<\n");
        shutdownManager.performGracefulShutdown();

        try {
            Msg response = future.get(20, TimeUnit.SECONDS);
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            String text = extractText(response);
            System.out.println(
                    "\n[t="
                            + elapsed
                            + "s] [Result] Agent completed before timeout: "
                            + text.substring(0, Math.min(200, text.length()))
                            + "...");
            System.out.println(
                    "  (The model responded faster than the timeout. In production with complex"
                            + " reasoning, the timeout would interrupt mid-stream.)");
        } catch (ExecutionException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            Throwable cause = e.getCause();
            if (cause instanceof AgentShuttingDownException) {
                System.out.println(
                        "[t="
                                + elapsed
                                + "s] [Result] AgentShuttingDownException during"
                                + " reasoning: "
                                + cause.getMessage());
                System.out.println("  Partial reasoning was saved to session (policy=SAVE).");
            } else {
                System.out.println(
                        "[t=" + elapsed + "s] [Result] Agent error: " + cause.getMessage());
            }
        } catch (TimeoutException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println("[t=" + elapsed + "s] [Result] Timed out waiting for agent.");
            future.cancel(true);
        }

        printMemory("Memory after reasoning scenario", agent);
        agent.saveTo(session, SCENARIO2_SESSION_ID);
    }

    // ==================== Scenario 2 Resume ====================

    private static void scenario2_resume(String apiKey, Session session) throws Exception {
        printBanner("Scenario 2 Resume: Continue reasoning from partial state");
        System.out.println(
                "The client sends 'continue' after loading the saved session.\n"
                        + "The agent sees the partial reasoning in memory and continues\n"
                        + "the analysis from where it was interrupted.\n");

        shutdownManager.resetForTesting();
        shutdownManager.setConfig(GracefulShutdownConfig.DEFAULT);

        ReActAgent agent = buildAgent(apiKey, new Toolkit(), SCENARIO2_SYS_PROMPT);

        boolean loaded = agent.loadIfExists(session, SCENARIO2_SESSION_ID);
        if (!loaded) {
            System.out.println("No saved session found. Skipping resume scenario.\n");
            return;
        }

        printMemory("Loaded memory from session", agent);

        Msg retryMsg = textMsg("continue");

        System.out.println("User> " + extractText(retryMsg));
        System.out.println("Agent> ");

        Msg response = agent.call(retryMsg).block();
        if (response != null) {
            String text = extractText(response);
            System.out.println(text.substring(0, Math.min(500, text.length())));
            if (text.length() > 500) {
                System.out.println("...(truncated, total " + text.length() + " chars)");
            }
        }

        agent.saveTo(session, SCENARIO2_SESSION_ID);
        System.out.println("\n[OK] Session updated after resume.\n");
    }

    // ==================== Scenario 3: Tool Exceeds Timeout ====================

    private static final String SCENARIO3_SYS_PROMPT =
            "You are a data processing assistant. "
                    + "When the user asks to run a report, follow these steps:\n"
                    + "1. Call the generate_report tool to produce the raw report data\n"
                    + "2. Analyze the report results and provide key insights, trends, "
                    + "and actionable recommendations\n"
                    + "Do not skip any step. Do not ask clarifying questions.";

    private static final String SCENARIO3_SESSION_ID = "graceful_shutdown_demo_timeout";

    private static void scenario3_toolExceedsTimeout(String apiKey, Session session)
            throws Exception {
        printBanner("Scenario 3: Tool execution (30s) exceeds shutdown timeout (5s)");
        System.out.println(
                "The agent has a 2-step task: (1) call generate_report (slow, 30s),\n"
                        + "then (2) analyze the report and provide insights.\n"
                        + "Shutdown timeout is only 5s — it fires at ~t=7s (save + interrupt),\n"
                        + "but the tool keeps running (cooperative). Once done at ~t=32s, the\n"
                        + "agent observes the flag and throws AgentShuttingDownException.\n"
                        + "On resume, the agent reads the report from memory and provides\n"
                        + "the analysis — no re-execution of the slow tool.\n");

        shutdownManager.resetForTesting();
        shutdownManager.setConfig(
                new GracefulShutdownConfig(Duration.ofSeconds(5), PartialReasoningPolicy.SAVE));

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new VerySlowTools());

        ReActAgent agent = buildAgent(apiKey, toolkit, SCENARIO3_SYS_PROMPT);

        agent.loadIfExists(session, SCENARIO3_SESSION_ID);

        Msg userMsg = textMsg("Please run the monthly sales report and provide your analysis.");

        System.out.println("[t=0s] User> " + extractText(userMsg));
        System.out.println("[t=0s] Starting agent...\n");

        long startTime = System.currentTimeMillis();

        CompletableFuture<Msg> future =
                CompletableFuture.supplyAsync(() -> agent.call(userMsg).block());

        Thread.sleep(2000);
        System.out.println("[t=2s] >>> Triggering graceful shutdown (timeout=5s) <<<");
        System.out.println(
                "[t=2s] Active requests: " + shutdownManager.getActiveRequestCount() + "\n");
        shutdownManager.performGracefulShutdown();

        try {
            Msg response = future.get(60, TimeUnit.SECONDS);
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println(
                    "[t="
                            + elapsed
                            + "s] [Result] Agent responded normally: "
                            + extractText(response));
        } catch (ExecutionException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            Throwable cause = e.getCause();
            if (cause instanceof AgentShuttingDownException) {
                System.out.println(
                        "[t="
                                + elapsed
                                + "s] [Result] AgentShuttingDownException caught: "
                                + cause.getMessage());
                System.out.println("  Note: timeout fired at ~t=7s (save + interrupt flag set),");
                System.out.println(
                        "  but exception only propagated at t="
                                + elapsed
                                + "s after the tool finished.");
            } else {
                System.out.println(
                        "[t=" + elapsed + "s] [Result] Agent error: " + cause.getMessage());
            }
        } catch (TimeoutException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println("[t=" + elapsed + "s] [Result] Demo timed out waiting for tool.");
            future.cancel(true);
        }

        printMemory("Memory after timeout scenario", agent);
        agent.saveTo(session, SCENARIO3_SESSION_ID);
    }

    // ==================== Scenario 3 Resume ====================

    private static void scenario3_resume(String apiKey, Session session) throws Exception {
        printBanner("Scenario 3 Resume: Continue after tool-exceeds-timeout");
        System.out.println(
                "The client sends 'continue' after loading the saved session.\n"
                        + "The agent sees the completed report in memory and proceeds\n"
                        + "to provide insights and recommendations — no re-execution\n"
                        + "of the slow report tool.\n");

        shutdownManager.resetForTesting();
        shutdownManager.setConfig(GracefulShutdownConfig.DEFAULT);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new VerySlowTools());

        ReActAgent agent = buildAgent(apiKey, toolkit, SCENARIO3_SYS_PROMPT);

        boolean loaded = agent.loadIfExists(session, SCENARIO3_SESSION_ID);
        if (!loaded) {
            System.out.println("No saved session found. Skipping resume scenario.\n");
            return;
        }

        printMemory("Loaded memory from session", agent);

        Msg retryMsg = textMsg("continue");

        System.out.println("User> " + extractText(retryMsg));
        System.out.println("Agent> ");

        Msg response = agent.call(retryMsg).block();
        if (response != null) {
            String text = extractText(response);
            System.out.println(text.substring(0, Math.min(500, text.length())));
            if (text.length() > 500) {
                System.out.println("...(truncated, total " + text.length() + " chars)");
            }
        }

        agent.saveTo(session, SCENARIO3_SESSION_ID);
        System.out.println("\n[OK] Session updated after resume.\n");
    }

    // ==================== Scenario 4: Business Recovery ====================

    private static void scenario4_businessRecovery(String apiKey, Session session)
            throws Exception {
        printBanner("Scenario 4: Catching shutdown exception for business recovery");
        System.out.println(
                "Demonstrates how application code catches AgentShuttingDownException\n"
                        + "and performs business recovery: audit logging, state persistence,\n"
                        + "MQ notification, and returning a user-friendly error.\n");

        shutdownManager.resetForTesting();
        shutdownManager.setConfig(
                new GracefulShutdownConfig(Duration.ofSeconds(3), PartialReasoningPolicy.SAVE));

        String sessionId = "graceful_shutdown_demo_recovery";
        String orderId = "ORD-2026-88421";

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SlowTools());

        ReActAgent agent =
                buildAgent(
                        apiKey,
                        toolkit,
                        "You are an order processing assistant. "
                                + "When the user gives you an order ID to process, call the "
                                + "slow_data_processing tool with that order ID. "
                                + "Do not ask any clarifying questions.");

        agent.loadIfExists(session, sessionId);

        Msg userMsg = textMsg("Process order " + orderId);

        System.out.println("[t=0s] User> " + extractText(userMsg));
        System.out.println("[t=0s] Starting agent...\n");

        long startTime = System.currentTimeMillis();

        CompletableFuture<Msg> future =
                CompletableFuture.supplyAsync(() -> agent.call(userMsg).block());

        Thread.sleep(2000);
        System.out.println("\n[t=2s] >>> Triggering graceful shutdown <<<\n");
        shutdownManager.performGracefulShutdown();

        // --- Simulated application-level handler (e.g., Spring Controller / Service) ---
        try {
            Msg response = future.get(30, TimeUnit.SECONDS);
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println("[t=" + elapsed + "s] Agent> " + extractText(response));
        } catch (ExecutionException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (e.getCause() instanceof AgentShuttingDownException shutdownEx) {
                System.out.println("[t=" + elapsed + "s] Exception caught, starting recovery...");
                handleShutdownException(shutdownEx, orderId, agent, session, sessionId);
            } else {
                System.err.println(
                        "[t="
                                + elapsed
                                + "s] [ERROR] Unexpected error: "
                                + e.getCause().getMessage());
            }
        } catch (TimeoutException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println(
                    "[t="
                            + elapsed
                            + "s] [WARN] Timed out. Session was saved during shutdown"
                            + " timeout.");
            future.cancel(true);
        }
    }

    /**
     * Simulates application-level recovery after catching AgentShuttingDownException.
     * In a real application, this would be in a Controller / Service layer.
     */
    private static void handleShutdownException(
            AgentShuttingDownException ex,
            String orderId,
            ReActAgent agent,
            Session session,
            String sessionId) {

        System.out.println("[Recovery] Caught AgentShuttingDownException: " + ex.getMessage());
        System.out.println("[Recovery] Starting business recovery for order: " + orderId);

        // 1. Audit logging
        System.out.println(
                "[Recovery] [1/4] Writing audit log: order "
                        + orderId
                        + " interrupted by system shutdown");

        // 2. Save session (the framework already saved at interrupt, but you can save again
        //    to capture the most complete state)
        agent.saveTo(session, sessionId);
        System.out.println("[Recovery] [2/4] Session saved to persistent storage");

        // 3. Send retry message to MQ (simulate)
        System.out.println(
                "[Recovery] [3/4] Publishing retry message to MQ: "
                        + "{\"orderId\":\""
                        + orderId
                        + "\",\"action\":\"retry\","
                        + "\"sessionId\":\""
                        + sessionId
                        + "\"}");

        // 4. Return user-friendly error (simulate HTTP 503 response)
        System.out.println(
                "[Recovery] [4/4] Returning HTTP 503 to client: "
                        + "\"Service temporarily unavailable, please retry. "
                        + "Your request "
                        + orderId
                        + " has been saved and will resume automatically.\"");

        System.out.println("\n[Recovery] Business recovery completed.");

        printMemory("Agent memory at recovery time", agent);
    }

    // ==================== Helper Methods ====================

    private static ReActAgent buildAgent(String apiKey, Toolkit toolkit, String sysPrompt) {
        return ReActAgent.builder()
                .name("Assistant")
                .sysPrompt(sysPrompt)
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(3)
                .build();
    }

    private static void printBanner(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60) + "\n");
    }

    private static void printMemory(String label, ReActAgent agent) {
        List<Msg> messages = agent.getMemory().getMessages();
        System.out.println("\n--- " + label + " (" + messages.size() + " messages) ---");
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            String role = msg.getRole().name();
            String preview = summarizeMsg(msg);
            System.out.println("  " + (i + 1) + ". [" + role + "] " + preview);
        }
        System.out.println("---\n");
    }

    private static String summarizeMsg(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                String text = tb.getText();
                sb.append(text, 0, Math.min(text.length(), 8000));
                if (text.length() > 8000) sb.append("...");
            } else if (block instanceof ThinkingBlock tb) {
                String text = tb.getThinking();
                sb.append("[thinking] ").append(text, 0, Math.min(text.length(), 6000));
                if (text.length() > 6000) sb.append("...");
            } else if (block instanceof ToolUseBlock tu) {
                sb.append("[tool_call] ")
                        .append(tu.getName())
                        .append("(")
                        .append(tu.getInput())
                        .append(")");
            } else if (block instanceof ToolResultBlock trb) {
                sb.append("[tool_result] ");
                if (trb.getName() != null) {
                    sb.append(trb.getName()).append(": ");
                }
                String outputText =
                        trb.getOutput().stream()
                                .filter(o -> o instanceof TextBlock)
                                .map(o -> ((TextBlock) o).getText())
                                .collect(Collectors.joining());
                sb.append(outputText, 0, Math.min(outputText.length(), 6000));
                if (outputText.length() > 6000) sb.append("...");
            } else {
                sb.append("[").append(block.getClass().getSimpleName()).append("]");
            }
            sb.append(" ");
        }
        return sb.toString().trim();
    }

    private static Msg textMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String extractText(Msg msg) {
        return msg.getContent().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    private static String getDashScopeApiKey() throws IOException {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            System.out.println("Using API key from DASHSCOPE_API_KEY environment variable.\n");
            return apiKey;
        }
        System.err.println(
                "ERROR: DASHSCOPE_API_KEY environment variable is not set.\n"
                        + "Please set it before running this example:\n"
                        + "  export DASHSCOPE_API_KEY=sk-xxx");
        System.exit(1);
        return null;
    }

    // ==================== Data Analysis Tool (single slow tool for multi-step demo) ===========

    /**
     * Slow analysis tool (~15s). The multi-step flow comes from the agent needing to
     * (1) call the tool, then (2) reason about the results and write a report.
     * Shutdown interrupts between step 1 and step 2; resume continues from step 2.
     */
    public static class DataAnalysisTool {

        @Tool(
                name = "analyze_dataset",
                description =
                        "Analyze a dataset to extract raw statistics. "
                                + "This is a slow operation (takes about 15 seconds).")
        public String analyzeDataset(
                @ToolParam(name = "dataset_name", description = "Name of the dataset to analyze")
                        String datasetName) {
            System.out.println("[Tool] analyze_dataset started for: " + datasetName);
            for (int i = 0; i < 15; i++) {
                try {
                    Thread.sleep(1000);
                    System.out.println(
                            "[Tool] Analyzing "
                                    + datasetName
                                    + "... "
                                    + ((i + 1) * 100 / 15)
                                    + "%");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("[Tool] Interrupted at " + ((i + 1) * 100 / 15) + "%");
                    return "Analysis of '"
                            + datasetName
                            + "' was interrupted at "
                            + ((i + 1) * 100 / 15)
                            + "% completion.";
                }
            }
            System.out.println("[Tool] analyze_dataset finished");
            return "Analysis of '"
                    + datasetName
                    + "' complete. Total records: 12,847. "
                    + "Revenue: $1,234,567. Anomalies: 3 detected (IDs: A-0042, A-1337, A-9981). "
                    + "Top category: Electronics (42%). Average order value: $96.12.";
        }
    }

    // ==================== Slow Tools ====================

    /** A very slow tool (30s) to demonstrate timeout exceeding tool execution. */
    public static class VerySlowTools {

        @Tool(
                name = "generate_report",
                description = "Generate a detailed report. Takes about 30 seconds to complete.")
        public String generateReport(
                @ToolParam(name = "report_type", description = "Type of report to generate")
                        String reportType) {
            System.out.println("[Tool] generate_report started: " + reportType);
            for (int i = 0; i < 30; i++) {
                try {
                    Thread.sleep(1000);
                    if ((i + 1) % 5 == 0) {
                        System.out.println("[Tool] Report progress: " + ((i + 1) * 100 / 30) + "%");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println(
                            "[Tool] Report generation interrupted at "
                                    + ((i + 1) * 100 / 30)
                                    + "%");
                    return "Report '"
                            + reportType
                            + "' interrupted at "
                            + ((i + 1) * 100 / 30)
                            + "%.";
                }
            }
            return "Report '"
                    + reportType
                    + "' generated successfully. "
                    + "Total revenue: $1,234,567. Growth: +12.3%.";
        }
    }

    /** Tools that simulate long-running operations for demonstrating graceful shutdown. */
    public static class SlowTools {

        @Tool(
                name = "slow_data_processing",
                description =
                        "Process a dataset. This operation takes a long time to complete"
                                + " (simulates a real-world slow data processing task).")
        public String slowDataProcessing(
                @ToolParam(name = "dataset_name", description = "Name of the dataset to process")
                        String datasetName) {
            System.out.println("[Tool] slow_data_processing started for dataset: " + datasetName);
            for (int i = 0; i < 15; i++) {
                try {
                    Thread.sleep(1000);
                    System.out.println(
                            "[Tool] Processing "
                                    + datasetName
                                    + "... "
                                    + ((i + 1) * 100 / 15)
                                    + "%");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("[Tool] Interrupted at " + ((i + 1) * 100 / 15) + "%");
                    return "Processing of '"
                            + datasetName
                            + "' was interrupted at "
                            + ((i + 1) * 100 / 15)
                            + "% completion.";
                }
            }
            System.out.println("[Tool] Local SlowTool call finished");
            return "Successfully processed dataset '"
                    + datasetName
                    + "'. Found 12,847 records, 3 anomalies detected.";
        }
    }
}
