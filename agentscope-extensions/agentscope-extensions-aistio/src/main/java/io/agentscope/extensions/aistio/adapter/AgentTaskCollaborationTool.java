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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runtime-context-bound proxy for one canonical control-plane collaboration MCP tool.
 *
 * <p>OpenAI-compatible APIs require {@code function.name =~ ^[a-zA-Z0-9_-]{1,64}$}, but the control
 * plane publishes dotted wire names ({@code task.get}, {@code issue.comment.add}, …) and
 * dispatches {@code tools/call} on that exact spelling. This tool therefore keeps two names: the
 * dotted {@linkplain #getWireName() wire name} for MCP dispatch / read-only / terminal checks, and
 * an OpenAI-safe {@linkplain #getName() model name} from {@link #toModelName(String)} exposed to the
 * LLM.
 */
final class AgentTaskCollaborationTool implements AgentTool {

    /** Control-plane wire name for the adapter-owned physical completion action (not registered). */
    static final String WIRE_TASK_COMPLETE = "task.complete";

    /** Control-plane wire name for the adapter-owned physical failure action (not registered). */
    static final String WIRE_TASK_FAIL = "task.fail";

    /** Adapter-owned outcome tool wire spelling before model-safe normalization. */
    static final String WIRE_TASK_SUBMIT_RESULT = "task.submit_result";

    private static final Set<String> READ_ONLY =
            Set.of(
                    "issue.get",
                    "issue.comment.list",
                    "artifact.download",
                    "task.get",
                    "team.get",
                    "run.get",
                    "run.graph",
                    "run.artifacts");

    private static final Set<String> TERMINAL_WIRE_NAMES =
            Set.of("run.node.complete", "run.node.fail");

    private static final Pattern OPENAI_SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final CollaborationClient collaboration;
    private final String wireName;
    private final String modelName;
    private final String description;
    private final Map<String, Object> parameters;

    AgentTaskCollaborationTool(CollaborationClient collaboration, JsonNode definition) {
        this.collaboration = collaboration;
        this.wireName = definition.path("name").asText();
        this.modelName = toModelName(wireName);
        this.description = definition.path("description").asText();
        this.parameters =
                ControlPlaneHttpClient.mapper()
                        .convertValue(
                                definition.path("inputSchema"),
                                new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Maps a control-plane wire name to an OpenAI-safe model-facing name matching {@code
     * ^[a-zA-Z0-9_-]{1,64}$}.
     *
     * <p>Dotted control-plane names take the fast path {@code '.' → '_'}. Names with any other
     * illegal character (or length over 64) are fully sanitized like core {@code
     * SubAgentTool.resolveToolName}: illegal runs become {@code '_'}, consecutive underscores are
     * collapsed, and a deterministic 8-hex hash suffix is appended when information was lost or the
     * name still exceeds 64 characters. Keep this as the single mapping so registration, prompts,
     * and tests cannot drift apart.
     */
    static String toModelName(String wireName) {
        if (wireName == null) {
            return null;
        }
        String dottedMapped = wireName.replace('.', '_');
        if (OPENAI_SAFE_NAME.matcher(dottedMapped).matches()) {
            return dottedMapped;
        }
        return sanitizeModelName(wireName);
    }

    /**
     * Total sanitizer for wire names outside the dotted-ASCII case. Equivalent intent to core
     * {@code SubAgentTool}'s private {@code sanitizeName}, without the {@code call_} prefix.
     */
    private static String sanitizeModelName(String originalName) {
        String safePart =
                originalName
                        .replaceAll("[^a-zA-Z0-9_-]+", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_+|_+$", "");
        if (safePart.isEmpty()) {
            safePart = "tool";
        }

        // Dot is intentionally mapped; any other char outside the OpenAI class is information loss.
        boolean informationLost = !originalName.matches("^[a-zA-Z0-9_.-]+$");
        boolean needsHash = informationLost || safePart.length() > 64;

        if (!needsHash) {
            return safePart;
        }

        UUID uuid = UUID.nameUUIDFromBytes(originalName.getBytes(StandardCharsets.UTF_8));
        String shortHash = uuid.toString().replace("-", "").substring(0, 8);
        String suffix = "_" + shortHash;
        String resolvedName = safePart + suffix;
        if (resolvedName.length() > 64) {
            int allowed = 64 - suffix.length();
            if (allowed > 0) {
                safePart = safePart.substring(0, allowed).replaceAll("_+$", "");
                resolvedName = safePart + suffix;
            } else {
                resolvedName = shortHash.substring(0, Math.min(64, shortHash.length()));
            }
        }
        return resolvedName;
    }

    /** Whether {@code wireName} is a terminal run-node action that leaders must be able to call. */
    static boolean isTerminalWireName(String wireName) {
        return TERMINAL_WIRE_NAMES.contains(wireName);
    }

    /** Control-plane / MCP wire name (dotted). Used for {@code tools/call} and classification. */
    String getWireName() {
        return wireName;
    }

    @Override
    public String getName() {
        return modelName;
    }

    @Override
    public String getDescription() {
        return description + " This tool is available only while processing an aistio AgentTask.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public boolean isReadOnly() {
        return READ_ONLY.contains(wireName);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(
                        () -> {
                            RuntimeContext runtimeContext = param.getRuntimeContext();
                            AgentTaskToolContext taskContext =
                                    runtimeContext == null
                                            ? null
                                            : runtimeContext.get(AgentTaskToolContext.class);
                            if (taskContext == null) {
                                return ToolResultBlock.text(
                                        "Error: "
                                                + modelName
                                                + " requires an active aistio AgentTask context.");
                            }
                            JsonNode result =
                                    collaboration.callTool(
                                            taskContext.taskId(),
                                            taskContext.taskToken(),
                                            wireName,
                                            param.getInput(),
                                            param.getToolUseBlock() == null
                                                    ? null
                                                    : param.getToolUseBlock().getId());
                            if (TERMINAL_WIRE_NAMES.contains(wireName)) {
                                AgentTaskOutcome.State state =
                                        runtimeContext.get(AgentTaskOutcome.State.class);
                                if (state != null) state.markTerminalCommitted();
                            }
                            return ToolResultBlock.text(result.toString());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
