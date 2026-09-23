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
package io.agentscope.examples.documentation2.jev;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.example.JevToolSelectionMiddleware;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Manual runner for {@link JevToolSelectionMiddleware} against the real Jev and DashScope APIs.
 *
 * <p>Before running this manual test, add a model-provider dependency such as agentscope-extensions-model-openai.
 */
public final class JevToolSelectionMiddlewareTest {

    private static final String DASHSCOPE_COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final Duration MODEL_CALL_TIMEOUT = Duration.ofSeconds(60);

    private JevToolSelectionMiddlewareTest() {}

    public static void main(String[] args) {
        String jevApiKey = firstNonBlankEnv("TYPESAFE_API_KEY", "JEV_API_KEY");
        String dashscopeApiKey = firstNonBlankEnv("DASHSCOPE_API_KEY");
        if (jevApiKey == null || dashscopeApiKey == null) {
            System.err.println(
                    "Set TYPESAFE_API_KEY (or JEV_API_KEY) and DASHSCOPE_API_KEY before running"
                            + " this manual test.");
            System.exit(1);
        }

        JevClient jevClient =
                JevClient.builder()
                        .apiKey(jevApiKey)
                        .timeout(Duration.ofSeconds(8))
                        .retryPolicy(new JevRetryPolicy(1, Duration.ofMillis(500)))
                        .build();
        ModelCreationContext modelContext =
                ModelCreationContext.builder()
                        .apiKey(dashscopeApiKey)
                        .baseUrl(DASHSCOPE_COMPATIBLE_BASE_URL)
                        .stream(false)
                        .build();

        Model balanced = resolve("openai:qwen-plus", modelContext);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimulatedTools());

        JevToolSelectionMiddleware selector =
                JevToolSelectionMiddleware.builder(loggingJevCall(jevClient))
                        .maxTools(2)
                        .confidenceThreshold(0.2)
                        .failOpen(false)
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("jev-tool-selection-dashscope")
                        .sysPrompt(
                                "You are a concise technical assistant. Answer in Chinese. Use"
                                        + " the available tools when they help answer the"
                                        + " request.")
                        .model(balanced)
                        .toolkit(toolkit)
                        .generateOptions(
                                GenerateOptions.builder().temperature(0.2).maxTokens(400).build())
                        .middleware(selector)
                        .middleware(new BeforeFilterAuditMiddleware())
                        .middleware(new SelectionAuditMiddleware())
                        .build();

        runPrompt(agent, "帮我搜索 2026 年最重要的 AI 新闻。");
        runPrompt(agent, "帮我计算 (123 + 456) * 789。");
        runPrompt(agent, "帮我查一下北京明天的天气。");
    }

    private static Model resolve(String modelId, ModelCreationContext context) {
        try {
            return ModelRegistry.resolve(modelId, context);
        } catch (RuntimeException e) {
            System.err.println("Failed to resolve " + modelId + ": " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    private static void runPrompt(ReActAgent agent, String prompt) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("Prompt: " + prompt);
        long startedAt = System.currentTimeMillis();
        try {
            Msg reply = agent.call(prompt, RuntimeContext.empty()).block(MODEL_CALL_TIMEOUT);
            System.out.println("Answer: " + (reply == null ? "" : reply.getTextContent()));
            System.out.println("Elapsed: " + (System.currentTimeMillis() - startedAt) + " ms");
        } catch (RuntimeException e) {
            System.err.println("Failed after " + (System.currentTimeMillis() - startedAt) + " ms");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String firstNonBlankEnv(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Wraps the real Jev call with logging so the manual test shows what Jev was asked and what
     * probabilities it returned. This is the primary evidence that Jev is making the decision.
     */
    private static Function<SystemOneRequest, Mono<SystemOneResult>> loggingJevCall(
            JevClient client) {
        return request -> {
            System.out.println();
            System.out.println("--- Jev request ---");
            System.out.printf("questions: %s%n", request.questions().keySet());
            for (var entry : request.questions().entrySet()) {
                System.out.printf(
                        "  %s: %s%n", entry.getKey(), entry.getValue().getClass().getSimpleName());
            }
            return client.systemOne(request)
                    .doOnNext(
                            result -> {
                                System.out.println("--- Jev response ---");
                                for (var entry : result.answers().entrySet()) {
                                    System.out.printf(
                                            "  %s: %s%n", entry.getKey(), entry.getValue());
                                }
                            });
        };
    }

    /** Eight simulated tools with clearly separated responsibilities. */
    public static class SimulatedTools {

        @Tool(
                name = "search",
                description = "Search the web for news, articles, and reference information.")
        public String search(
                @ToolParam(name = "query", description = "The search query") String query) {
            return "[simulated] search results for: " + query;
        }

        @Tool(name = "calculator", description = "Evaluate arithmetic expressions.")
        public String calculator(
                @ToolParam(name = "expression", description = "The expression to evaluate")
                        String expression) {
            return "[simulated] calculated: " + expression;
        }

        @Tool(name = "weather", description = "Query current or forecast weather for a city.")
        public String weather(
                @ToolParam(name = "city", description = "The city name") String city) {
            return "[simulated] weather for: " + city;
        }

        @Tool(name = "translator", description = "Translate text between languages.")
        public String translator(
                @ToolParam(name = "text", description = "The text to translate") String text,
                @ToolParam(name = "target_language", description = "The target language")
                        String targetLanguage) {
            return "[simulated] translated to " + targetLanguage + ": " + text;
        }

        @Tool(
                name = "summarizer",
                description = "Summarize a long document or article into key points.")
        public String summarizer(
                @ToolParam(name = "text", description = "The text to summarize") String text) {
            return "[simulated] summary: " + text;
        }

        @Tool(
                name = "read_file",
                description = "Read the content of a file from the local filesystem.")
        public String readFile(
                @ToolParam(name = "path", description = "The file path") String path) {
            return "[simulated] content of: " + path;
        }

        @Tool(
                name = "code_analyzer",
                description = "Analyze source code for structure, quality, and potential issues.")
        public String codeAnalyzer(
                @ToolParam(name = "code", description = "The source code to analyze") String code) {
            return "[simulated] analysis for code";
        }

        @Tool(
                name = "database_query",
                description = "Run a read-only SQL query against a configured database.")
        public String databaseQuery(
                @ToolParam(name = "sql", description = "The SQL query to execute") String sql) {
            return "[simulated] rows for: " + sql;
        }
    }

    /**
     * Runs above {@code JevToolSelectionMiddleware} (order 0), so the tools it sees are the
     * full, unfiltered list. Shows what the model would see without Jev.
     */
    private static final class BeforeFilterAuditMiddleware implements MiddlewareBase {

        @Override
        public int order() {
            return 1;
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            List<ToolSchema> tools = input.tools() == null ? List.of() : input.tools();
            System.out.println();
            System.out.printf("--- Before Jev filtering (%d tools) ---%n", tools.size());
            tools.forEach(tool -> System.out.printf("  %s%n", tool.getName()));
            return next.apply(input);
        }
    }

    /**
     * Runs below {@code JevToolSelectionMiddleware} (order 0), so the tools it sees are already
     * filtered. Shows what the model actually receives after Jev selection.
     */
    private static final class SelectionAuditMiddleware implements MiddlewareBase {

        @Override
        public int order() {
            return -1;
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            List<ToolSchema> tools = input.tools() == null ? List.of() : input.tools();
            System.out.printf("--- After Jev filtering (%d tools) ---%n", tools.size());
            tools.forEach(
                    tool -> System.out.printf("  %s: %s%n", tool.getName(), tool.getDescription()));
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            System.out.printf(
                    "Acting tool calls (%d):%n",
                    input.toolCalls() == null ? 0 : input.toolCalls().size());
            if (input.toolCalls() != null) {
                input.toolCalls()
                        .forEach(
                                call ->
                                        System.out.printf(
                                                "  -> tool=%s input=%s%n",
                                                call.getName(), call.getInput()));
            }
            return next.apply(input);
        }
    }
}
