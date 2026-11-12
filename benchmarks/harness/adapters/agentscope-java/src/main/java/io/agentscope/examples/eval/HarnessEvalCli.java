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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

/** Runs the production Harness with its workspace tools; records native evidence for the adapter. */
public final class HarnessEvalCli {
    private static final ObjectMapper JSON = new ObjectMapper();

    private HarnessEvalCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("agentscope-harness-eval 1.2.0");
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: HarnessEvalCli REQUEST_JSON NATIVE_JSONL WORKSPACE");
        }
        JsonNode request = JSON.readTree(Files.readString(Path.of(args[0])));
        JsonNode events = request.path("events");
        if (!events.isArray() || events.isEmpty()) {
            throw new IllegalArgumentException("Expected a non-empty events array");
        }
        // Validate the entire request before executing any input.
        for (JsonNode event : events) {
            if (!event.path("type").asText().equals("user.message")
                    || !event.path("content").isArray()
                    || event.path("content").isEmpty()) {
                throw new IllegalArgumentException("This batch CLI accepts text user.message only");
            }
            for (JsonNode block : event.path("content")) {
                if (!block.path("type").asText().equals("text")
                        || !block.path("text").isTextual()) {
                    throw new IllegalArgumentException("Only text content is supported");
                }
            }
        }
        Path workspace = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        String sessionId =
                System.getenv().getOrDefault("EVAL_SESSION_ID", UUID.randomUUID().toString());
        Path state =
                Path.of(
                        System.getenv()
                                .getOrDefault(
                                        "EVAL_STATE_DIR",
                                        Path.of(args[1])
                                                .toAbsolutePath()
                                                .getParent()
                                                .resolve("workspace-state")
                                                .toString()));
        var model =
                OpenAIChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .baseUrl(System.getenv("MODEL_BASE_URL"))
                        .modelName(System.getenv("MODEL_ID"))
                        .stream(true)
                        .generateOptions(
                                GenerateOptions.builder()
                                        .temperature(0.0)
                                        .maxTokens(8192)
                                        .additionalBodyParams(Map.of("enable_thinking", false))
                                        .build())
                        .build();
        AtomicBoolean exceeded = new AtomicBoolean();
        boolean failed = false;
        try (BufferedWriter output =
                Files.newBufferedWriter(
                        Path.of(args[1]),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
            var builder =
                    HarnessAgent.builder()
                            .name("HarnessAgent")
                            .model(observe(model, output))
                            .workspace(state)
                            .stateStore(new JsonFileAgentStateStore(state.resolve("sessions")))
                            .contextPolicy(
                                    new ContextPolicy(
                                            0,
                                            0,
                                            0,
                                            ContextTokenEstimator.approximate(),
                                            manifest ->
                                                    write(
                                                            output,
                                                            Map.of(
                                                                    "type",
                                                                    "eval.context",
                                                                    "createdAt",
                                                                    Instant.now().toString(),
                                                                    "manifest",
                                                                    manifest))))
                            .filesystem(
                                    new LocalFilesystemSpec()
                                            .project(workspace)
                                            .projectWritable(true))
                            .maxIters(
                                    Integer.parseInt(
                                            System.getenv().getOrDefault("MAX_TURNS", "60")));
            String systemPrompt = System.getenv("EVAL_SYSTEM_PROMPT");
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                builder.sysPrompt(systemPrompt);
            }
            String skillsDir = System.getenv("EVAL_SKILLS_DIR");
            if (skillsDir != null && !skillsDir.isBlank()) {
                builder.projectGlobalSkillsDir(Path.of(skillsDir));
            }
            if (System.getenv("EVAL_COMPACTION_MESSAGES") != null) {
                builder.compaction(
                        CompactionConfig.builder()
                                .triggerMessages(
                                        Integer.parseInt(System.getenv("EVAL_COMPACTION_MESSAGES")))
                                .triggerTokens(
                                        Integer.parseInt(
                                                System.getenv()
                                                        .getOrDefault(
                                                                "EVAL_COMPACTION_TOKENS", "12000")))
                                .keepMessages(2)
                                .keepTokens(1000)
                                .flushBeforeCompact(
                                        Boolean.parseBoolean(
                                                System.getenv()
                                                        .getOrDefault(
                                                                "EVAL_FLUSH_BEFORE_COMPACT",
                                                                "true")))
                                .build());
            }
            if (System.getenv("EVAL_DENY_TOOL") != null) {
                ToolsConfig toolsConfig = new ToolsConfig();
                toolsConfig.setDeny(List.of(System.getenv("EVAL_DENY_TOOL")));
                builder.toolsConfig(toolsConfig);
            }
            if (Boolean.parseBoolean(System.getenv("EVAL_DISABLE_MEMORY_HOOKS"))) {
                builder.disableMemoryHooks();
            }
            HarnessAgent agent = builder.build();
            try {
                write(
                        output,
                        Map.of(
                                "type",
                                "eval.binding",
                                "createdAt",
                                Instant.now().toString(),
                                "session_id",
                                sessionId,
                                "candidate_sha",
                                System.getenv().getOrDefault("CANDIDATE_SHA", "unknown"),
                                "candidate_source_sha256",
                                System.getenv().getOrDefault("CANDIDATE_SOURCE_SHA256", "unknown"),
                                "tools",
                                agent.getToolkit().getToolNames(),
                                "profile",
                                "local-project-writable"));
                for (JsonNode input : events) {
                    StringBuilder text = new StringBuilder();
                    for (JsonNode block : input.path("content")) {
                        if (!text.isEmpty()) text.append('\n');
                        text.append(block.path("text").asText());
                    }
                    write(
                            output,
                            Map.of(
                                    "type",
                                    "eval.input",
                                    "id",
                                    UUID.randomUUID().toString(),
                                    "createdAt",
                                    Instant.now().toString(),
                                    "content",
                                    input.path("content")));
                    agent.streamEvents(
                                    text.toString(),
                                    RuntimeContext.builder()
                                            .sessionId(sessionId)
                                            .userId(
                                                    System.getenv()
                                                            .getOrDefault(
                                                                    "EVAL_USER_ID", sessionId))
                                            .build())
                            .doOnNext(event -> record(output, event, exceeded))
                            .blockLast(
                                    Duration.ofSeconds(
                                            Long.parseLong(
                                                    System.getenv()
                                                            .getOrDefault(
                                                                    "TURN_TIMEOUT_SECONDS",
                                                                    "600"))));
                    write(
                            output,
                            Map.of(
                                    "type",
                                    "eval.tools_after",
                                    "createdAt",
                                    Instant.now().toString(),
                                    "tools",
                                    agent.getToolkit().getToolNames()));
                    if (exceeded.get())
                        throw new IllegalStateException("Harness iteration limit reached");
                }
                write(
                        output,
                        Map.of("type", "eval.completed", "createdAt", Instant.now().toString()));
            } catch (Exception error) {
                // Provider exceptions can contain request details; retain only their class here.
                write(
                        output,
                        Map.of(
                                "type",
                                "eval.failed",
                                "createdAt",
                                Instant.now().toString(),
                                "error_type",
                                error.getClass().getSimpleName(),
                                "iteration_limit",
                                exceeded.get()));
                System.err.println(
                        "Harness evaluation failed: " + error.getClass().getSimpleName());
                failed = true;
            } finally {
                agent.close();
            }
        }
        if (failed) System.exit(1);
    }

    private static void record(BufferedWriter output, AgentEvent event, AtomicBoolean exceeded) {
        if (event.getType() == AgentEventType.EXCEED_MAX_ITERS) exceeded.set(true);
        write(output, event);
    }

    private static Model observe(Model delegate, BufferedWriter output) {
        return new Model() {
            @Override
            public String getModelName() {
                return delegate.getModelName();
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(
                        () -> {
                            String id = UUID.randomUUID().toString();
                            AtomicReference<ChatUsage> usage = new AtomicReference<>();
                            write(
                                    output,
                                    Map.of(
                                            "type",
                                            "eval.model_start",
                                            "id",
                                            id,
                                            "createdAt",
                                            Instant.now().toString(),
                                            "messages",
                                            messages,
                                            "tools",
                                            tools == null ? List.of() : tools));
                            return delegate.stream(messages, tools, options)
                                    .doOnNext(
                                            response -> {
                                                if (response.getUsage() != null)
                                                    usage.set(response.getUsage());
                                            })
                                    .doOnComplete(
                                            () ->
                                                    write(
                                                            output,
                                                            Map.of(
                                                                    "type",
                                                                    "eval.model_end",
                                                                    "id",
                                                                    id,
                                                                    "createdAt",
                                                                    Instant.now().toString(),
                                                                    "usage",
                                                                    usage.get() == null
                                                                            ? Map.of()
                                                                            : usage.get())))
                                    .doOnError(
                                            error ->
                                                    write(
                                                            output,
                                                            Map.of(
                                                                    "type",
                                                                    "eval.model_error",
                                                                    "id",
                                                                    id,
                                                                    "createdAt",
                                                                    Instant.now().toString(),
                                                                    "error_type",
                                                                    error.getClass()
                                                                            .getSimpleName())));
                        });
            }
        };
    }

    private static synchronized void write(BufferedWriter output, Object value) {
        try {
            output.write(JSON.writeValueAsString(value));
            output.newLine();
            output.flush();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
