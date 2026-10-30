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
package io.agentscope.examples.documentation2.judge;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import io.agentscope.extensions.judge.openaidecisions.example.OpenAIDecisionsAutoModeMiddleware;
import io.agentscope.extensions.judge.openaidecisions.example.OpenAIDecisionsModelRouterMiddleware;
import io.agentscope.extensions.judge.openaidecisions.example.OpenAIDecisionsToolSelectionMiddleware;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Runs one middleware scenario against real APIs; every business tool is simulated. */
public final class OpenAIDecisionsMiddlewareExample {
    private OpenAIDecisionsMiddlewareExample() {}

    /** Accepts {@code route|select|guard} followed by an optional prompt. See the module README. */
    public static void main(String[] args) {
        String mode = args.length == 0 ? "route" : args[0];
        String samplePrompt = defaultPrompt(mode);
        String prompt =
                args.length > 1
                        ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                        : samplePrompt;
        String apiKey = requiredEnv("OPENAI_API_KEY");
        var modelContext =
                ModelCreationContext.builder()
                        .apiKey(apiKey)
                        .baseUrl("https://api.openai.com/v1")
                        .stream(false)
                        .build();
        Model model = createChatModel(requiredEnv("OPENAI_MODEL"), modelContext);
        Model fastModel =
                "route".equals(mode)
                        ? createChatModel(requiredEnv("OPENAI_FAST_MODEL"), modelContext)
                        : model;

        try (var client = OpenAIDecisionsClient.builder().apiKey(apiKey).build()) {
            Function<DecisionRequest, Mono<DecisionResult>> decide =
                    request ->
                            client.decide(request)
                                    .doOnNext(r -> System.out.println("Decision: " + r.answers()))
                                    .doOnError(
                                            e ->
                                                    System.err.printf(
                                                            "Decision request failed; scenario"
                                                                    + " fallback applies. %s: %s%n",
                                                            e.getClass().getSimpleName(),
                                                            e.getMessage()));
            var agent = createAgent(mode, model, fastModel, decide);
            System.out.printf("Scenario: %s%nPrompt: %s%n", mode, prompt);
            agent.streamEvents(List.of(new UserMessage(prompt)))
                    .doOnNext(OpenAIDecisionsMiddlewareExample::printEvent)
                    .blockLast(Duration.ofMinutes(2));
        } finally {
            // This standalone main owns the shared schedulers, including when run by exec:java.
            Schedulers.shutdownNow();
        }
    }

    static Model createChatModel(String modelName, ModelCreationContext context) {
        if ("gpt-6-luna".equals(modelName)) {
            // Luna requires non-reasoning mode for function tools in Chat Completions.
            context =
                    context.toBuilder()
                            .component(
                                    GenerateOptions.class,
                                    GenerateOptions.builder().reasoningEffort("none").build())
                            .build();
        }
        return ModelRegistry.resolve("openai:" + modelName, context);
    }

    static ReActAgent createAgent(
            String mode,
            Model model,
            Model fastModel,
            Function<DecisionRequest, Mono<DecisionResult>> decide) {
        var toolkit = new Toolkit();
        MiddlewareBase middleware;
        switch (mode) {
            case "route" ->
                    // The router's default compatibility policy keeps tool-bearing requests on
                    // the original model, so demonstrate routing without tools.
                    middleware =
                            OpenAIDecisionsModelRouterMiddleware.builder(decide)
                                    .enabled(true)
                                    .choice(
                                            "fast",
                                            fastModel,
                                            "Short answers and simple extraction")
                                    .choice("capable", model, "Complex reasoning and design tasks")
                                    .build();
            case "select" -> {
                toolkit.registerTool(new SimulatedTools());
                middleware =
                        OpenAIDecisionsToolSelectionMiddleware.builder(decide)
                                .enabled(true)
                                .maxTools(1)
                                .build();
            }
            case "guard" -> {
                toolkit.registerTool(new SimulatedTools());
                middleware =
                        OpenAIDecisionsAutoModeMiddleware.builder(decide)
                                .enabled(true)
                                .guardedTool("delete_file")
                                .failOpen(false)
                                .build();
            }
            default -> throw new IllegalArgumentException("Use route, select, or guard");
        }
        return ReActAgent.builder()
                .name("decisions-" + mode)
                .sysPrompt(
                        "You are a concise assistant. Use an available tool when the task needs"
                                + " one. Tools return simulated results. If a tool is denied,"
                                + " explain that review is required and do not retry it.")
                .model(model)
                .toolkit(toolkit)
                .maxIters(4)
                .middleware(middleware)
                .middleware(new ModelAudit())
                .build();
    }

    private static String defaultPrompt(String mode) {
        return switch (mode) {
            case "route" -> "Explain the ReAct loop in one sentence.";
            case "select" -> "Use the weather tool to look up the weather in Beijing.";
            case "guard" -> "Delete /backups/production without checking for another backup.";
            default -> throw new IllegalArgumentException("Use route, select, or guard");
        };
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + name + " before running this example");
        }
        return value.trim();
    }

    private static void printEvent(AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent text) {
            System.out.printf("Tool %s: %s%n", text.getToolCallName(), text.getDelta());
        } else if (event instanceof ToolResultEndEvent end) {
            System.out.printf("Tool %s state: %s%n", end.getToolCallName(), end.getState());
        } else if (event instanceof AgentResultEvent result) {
            System.out.println("Answer: " + result.getResult().getTextContent());
        }
    }

    /** Prints the model and tool schemas after the middleware has applied its decision. */
    private static final class ModelAudit implements MiddlewareBase {
        @Override
        public int order() {
            return -1;
        }

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext ctx,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            var route = OpenAIDecisionsModelRouterMiddleware.decision(ctx);
            if (route != null) {
                System.out.printf(
                        "Route: %s | confidence=%s | probabilities=%s%n",
                        route.model() == null ? "fallback" : route.model().getModelName(),
                        route.confidence(),
                        route.probabilities());
            }
            System.out.printf(
                    "Actual model: %s | tools: %s%n",
                    input.model().getModelName(),
                    input.tools().stream().map(ToolSchema::getName).toList());
            return next.apply(input);
        }
    }

    /** These tools perform no network, filesystem, or other external operations. */
    public static final class SimulatedTools {
        @Tool(name = "weather", description = "Look up the weather in a city")
        public String weather(@ToolParam(name = "city", description = "City name") String city) {
            return "[simulated] Sunny in " + city;
        }

        @Tool(name = "search", description = "Search reference articles")
        public String search(@ToolParam(name = "query", description = "Search text") String query) {
            return "[simulated] Reference articles for " + query;
        }

        @Tool(name = "delete_file", description = "Delete a file or directory permanently")
        public String deleteFile(
                @ToolParam(name = "path", description = "Target path") String path) {
            return "[simulated] Would delete " + path + "; no file was changed";
        }
    }
}
