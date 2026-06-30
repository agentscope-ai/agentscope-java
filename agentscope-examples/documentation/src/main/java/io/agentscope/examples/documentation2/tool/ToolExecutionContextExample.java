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
package io.agentscope.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.List;

/**
 * ToolExecutionContextExample - Demonstrates passing per-call context to tools via
 * {@link RuntimeContext}.
 *
 * <p>{@code RuntimeContext} allows the caller to pass arbitrary, type-safe objects to tools for
 * a single agent call. Common use-cases: tenant/user metadata, database connections, audit loggers.
 *
 * <p><b>How automatic POJO injection works:</b>
 * <ol>
 *   <li>Build a {@code RuntimeContext} with one or more typed values:
 *       {@code RuntimeContext.builder().put(UserContext.class, ctx).build()}</li>
 *   <li>Pass it to {@code agent.call(msgs, runtimeContext)} (or {@code agent.stream}).</li>
 *   <li>In a {@code @Tool}-annotated method, declare a parameter that is NOT annotated with
 *       {@code @ToolParam} and whose type was registered in the context — the framework injects
 *       the value automatically without the model needing to supply it.</li>
 * </ol>
 *
 * <p><b>Note on deprecation:</b> The legacy {@code ToolExecutionContext} class is
 * {@code @Deprecated}. Use {@link RuntimeContext} for all new code.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.tool.ToolExecutionContextExample
 * </pre>
 */
public class ToolExecutionContextExample {

    /**
     * Runs the RuntimeContext injection example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RuntimeContext (Tool Execution Context) Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates per-call context injection via RuntimeContext.\n"
                        + "The UserContext POJO is injected automatically into @Tool methods\n"
                        + "without the model supplying it as a parameter.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PersonalizedTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("PersonalizedAgent")
                        .sysPrompt(
                                "You are a personalized assistant. "
                                        + "Use the greet and preferences tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        // ── Scenario A: Alice calls with full preferences ─────────────────────────────
        UserContext aliceCtx = new UserContext("alice", "en", List.of("dark mode", "compact view"));
        RuntimeContext aliceRunCtx =
                RuntimeContext.builder()
                        .put(UserContext.class, aliceCtx) // type-safe singleton slot
                        .userId("alice")
                        .sessionId("session-alice")
                        .build();

        System.out.println("--- Alice's call ---");
        Msg aliceResponse =
                agent.call(
                                List.of(
                                        new UserMessage(
                                                "user", "Greet me and list my preferences.")),
                                aliceRunCtx)
                        .block();
        System.out.println(
                "Agent: " + (aliceResponse != null ? aliceResponse.getTextContent() : "(null)"));

        // ── Scenario B: Bob calls with different preferences ──────────────────────────
        UserContext bobCtx = new UserContext("bob", "zh", List.of("large fonts", "high contrast"));
        RuntimeContext bobRunCtx =
                RuntimeContext.builder()
                        .put(UserContext.class, bobCtx)
                        .userId("bob")
                        .sessionId("session-bob")
                        .build();

        System.out.println("\n--- Bob's call ---");
        Msg bobResponse =
                agent.call(List.of(new UserMessage("user", "What are my preferences?")), bobRunCtx)
                        .block();
        System.out.println(
                "Agent: " + (bobResponse != null ? bobResponse.getTextContent() : "(null)"));
    }

    /**
     * Per-call user context passed via RuntimeContext.
     *
     * <p>Instances of this class are injected automatically into {@code @Tool} methods
     * that declare it as a non-{@code @ToolParam} parameter.
     */
    public record UserContext(String username, String locale, List<String> preferences) {}

    /** Tools that receive {@link UserContext} via automatic POJO injection. */
    public static class PersonalizedTools {

        /**
         * Greets the user by name using the injected {@link UserContext}.
         *
         * <p>The {@code userCtx} parameter has no {@code @ToolParam} annotation — the framework
         * resolves it from the {@link RuntimeContext} registered with
         * {@code put(UserContext.class, value)}.
         *
         * @param greeting  greeting word supplied by the model
         * @param userCtx   injected from RuntimeContext — NOT passed by the model
         * @return personalised greeting string
         */
        @Tool(name = "greet", description = "Greet the user with a custom greeting")
        public String greet(
                @ToolParam(name = "greeting", description = "Greeting word, e.g. 'Hello'")
                        String greeting,
                UserContext userCtx) {
            String name = userCtx != null ? userCtx.username() : "unknown";
            return greeting + ", " + name + "!";
        }

        /**
         * Returns the current user's display preferences from the injected {@link UserContext}.
         *
         * @param userCtx injected from RuntimeContext
         * @return comma-separated preference list
         */
        @Tool(name = "get_preferences", description = "Get the current user's display preferences")
        public String getPreferences(UserContext userCtx) {
            if (userCtx == null) {
                return "No user context available";
            }
            return "User: "
                    + userCtx.username()
                    + " | Locale: "
                    + userCtx.locale()
                    + " | Preferences: "
                    + String.join(", ", userCtx.preferences());
        }
    }
}
