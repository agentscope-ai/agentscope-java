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
package io.agentscope.examples.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Pod-local control interface. Only dataset business tools are exposed to the agent. */
public final class HarnessEvalServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<Object> modelTrace = Collections.synchronizedList(new ArrayList<>());
    private HarnessAgent agent;
    private String sessionId;

    public static void main(String[] args) throws IOException {
        var instance = new HarnessEvalServer();
        if (args.length == 1 && args[0].equals("--check-runtime")) {
            try {
                instance.start(
                        Map.of(
                                "case_id", "build-preflight",
                                "system", "Runtime initialization check.",
                                "tools", List.of()));
                System.out.println("Harness runtime initialization passed; no model called");
            } finally {
                instance.close();
            }
            return;
        }
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/", instance::handle);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        System.out.println("HarnessEvalServer ready; candidate=" + System.getenv("CANDIDATE_SHA"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            Map<String, Object> request =
                    exchange.getRequestMethod().equals("GET")
                            ? Map.of()
                            : JSON.readValue(exchange.getRequestBody(), MAP);
            Object result;
            switch (path) {
                case "/health" -> result = Map.of("ready", true);
                case "/start" -> result = start(request);
                case "/chat" -> {
                    if (agent == null) throw new IllegalStateException("No active task");
                    Msg answer =
                            agent.call(
                                            (String) request.get("message"),
                                            RuntimeContext.builder()
                                                    .userId("benchmark")
                                                    .sessionId(sessionId)
                                                    .build())
                                    .block(Duration.ofMinutes(15));
                    result = Map.of("text", answer.getTextContent(), "trace", drainTrace());
                }
                case "/close" -> {
                    close();
                    result = Map.of("closed", true, "trace", drainTrace());
                }
                case "/shutdown" -> {
                    close();
                    reply(exchange, 200, Map.of("closed", true));
                    System.exit(0);
                    return;
                }
                default -> {
                    reply(exchange, 404, Map.of("error", "Unknown control operation"));
                    return;
                }
            }
            reply(exchange, 200, result);
        } catch (Exception error) {
            // Do not return provider exception bodies that may contain credentials.
            reply(
                    exchange,
                    500,
                    Map.of("error_type", error.getClass().getSimpleName(), "trace", drainTrace()));
        }
    }

    private void close() {
        if (agent != null) agent.close();
        agent = null;
    }

    private List<Object> drainTrace() {
        synchronized (modelTrace) {
            var result = new ArrayList<>(modelTrace);
            modelTrace.clear();
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private Object start(Map<String, Object> request) throws IOException {
        close();
        modelTrace.clear();
        Toolkit toolkit = new Toolkit();
        for (var definition : (List<Map<String, Object>>) request.get("tools")) {
            toolkit.registerAgentTool(new BusinessTool(definition));
        }
        var config = new ToolsConfig();
        config.setDefaultToolsEnabled(false);
        config.setStrictAllow(true);
        config.setAllow(new ArrayList<>(toolkit.getToolNames()));
        Model delegate =
                OpenAIChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .baseUrl(System.getenv("MODEL_BASE_URL"))
                        .modelName(System.getenv("MODEL_ID"))
                        .stream(false)
                        .generateOptions(
                                GenerateOptions.builder().temperature(0.0).maxTokens(8192).build())
                        .build();
        Model observed =
                new Model() {
                    @Override
                    public String getModelName() {
                        return delegate.getModelName();
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () -> {
                                    modelTrace.add(
                                            Map.of(
                                                    "type",
                                                    "model_request",
                                                    "messages",
                                                    List.copyOf(messages),
                                                    "tools",
                                                    tools == null ? List.of() : tools));
                                    return delegate.stream(messages, tools, options)
                                            .doOnNext(
                                                    response ->
                                                            modelTrace.add(
                                                                    Map.of(
                                                                            "type",
                                                                            "model_response",
                                                                            "response",
                                                                            response)));
                                });
                    }
                };
        sessionId = (String) request.get("case_id");
        agent =
                HarnessAgent.builder()
                        .name("HarnessAgent")
                        .sysPrompt((String) request.get("system"))
                        .model(observed)
                        .toolkit(toolkit)
                        .toolsConfig(config)
                        .enableTaskList(false)
                        .enableMetaTool(false)
                        .disableSubagents()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .maxIters(40)
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.never())
                                        .build())
                        .workspace(Files.createTempDirectory("harness-eval-"))
                        .build();
        // Fail closed if the candidate builder added an unexpected platform tool.
        var expected = config.getAllow();
        for (String name : new ArrayList<>(agent.getToolkit().getToolNames())) {
            if (!expected.contains(name)) agent.getToolkit().removeTool(name);
        }
        return Map.of(
                "tools",
                agent.getToolkit().getToolNames(),
                "candidate_sha",
                System.getenv("CANDIDATE_SHA"),
                "candidate_source_sha256",
                System.getenv().getOrDefault("CANDIDATE_SOURCE_SHA256", ""),
                "harness_origin",
                HarnessAgent.class.getProtectionDomain().getCodeSource().getLocation().toString());
    }

    private static void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record BusinessTool(Map<String, Object> definition) implements AgentTool {
        @Override
        public String getName() {
            return (String) definition.get("name");
        }

        @Override
        public String getDescription() {
            return (String) definition.get("description");
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> getParameters() {
            return (Map<String, Object>) definition.get("parameters");
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.defer(
                    () -> {
                        try {
                            var body = new LinkedHashMap<String, Object>();
                            body.put("name", getName());
                            body.put("arguments", param.getInput());
                            var request =
                                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081/tool"))
                                            .timeout(Duration.ofMinutes(3))
                                            .header("Content-Type", "application/json")
                                            .POST(
                                                    HttpRequest.BodyPublishers.ofByteArray(
                                                            JSON.writeValueAsBytes(body)))
                                            .build();
                            return Mono.fromFuture(
                                            HTTP.sendAsync(
                                                    request, HttpResponse.BodyHandlers.ofString()))
                                    .map(
                                            response -> {
                                                if (response.statusCode() != 200)
                                                    throw new IllegalStateException(
                                                            "Business tool bridge failed");
                                                return ToolResultBlock.text(response.body());
                                            });
                        } catch (IOException error) {
                            return Mono.error(error);
                        }
                    });
        }
    }
}
