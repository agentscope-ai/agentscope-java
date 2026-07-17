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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that evicts oversized tool results to the {@link AbstractFilesystem} before
 * downstream reasoning sees the bloated message list.
 *
 * <p>When the text content of a {@link ToolResultBlock} in canonical agent state or the current
 * reasoning view exceeds {@link ToolResultEvictionConfig#getMaxResultChars()}, this middleware:
 * <ol>
 *   <li>Writes the full result to
 *       {@code {evictionPath}/{agentName}/{sanitized-toolCallId}-{contentHash}} in the
 *       filesystem.</li>
 *   <li>Compacts {@link AgentState#contextMutable()} first, so legacy pre-reasoning hooks cannot
 *       hide an original result from persistence cleanup.</li>
 *   <li>Rebuilds the current {@link ReasoningInput}, preserving hook deletions and rewrites while
 *       replacing results that remain visible to the immediate model call.</li>
 *   <li>Marks compacted results in metadata so they are not recursively evicted on later turns.</li>
 * </ol>
 *
 * <p>Tools listed in {@link ToolResultEvictionConfig#getExcludedToolNames()} are never evicted
 * (e.g. {@code readFile} — evicting would cause re-read loops).
 */
public class ToolResultEvictionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ToolResultEvictionMiddleware.class);
    static final String EVICTED_METADATA_KEY = "agentscope.tool_result_evicted";

    private final AbstractFilesystem filesystem;
    private final ToolResultEvictionConfig config;

    public ToolResultEvictionMiddleware(
            AbstractFilesystem filesystem, ToolResultEvictionConfig config) {
        this.filesystem = filesystem;
        this.config = config;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        Map<ToolResultFingerprint, String> replacements = new HashMap<>();

        // PreReasoning hooks run before middleware and may remove or rewrite messages. Evict the
        // canonical state first so hook behavior cannot prevent the original result from being
        // compacted before persistence.
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state != null) {
            EvictionResult canonical =
                    evictMessages(state.contextMutable(), agent.getName(), rc, replacements);
            if (canonical.changed()) {
                List<Msg> context = state.contextMutable();
                for (int i = 0; i < context.size(); i++) {
                    context.set(i, canonical.messages().get(i));
                }
            }
        }

        // Preserve the hook-produced model view, compacting only results that remain (or were
        // newly added) in that view. Reuse canonical replacements to avoid duplicate writes.
        EvictionResult result = evictMessages(input.messages(), agent.getName(), rc, replacements);
        if (!result.changed()) {
            return next.apply(input);
        }

        return next.apply(new ReasoningInput(result.messages(), input.tools(), input.options()));
    }

    private EvictionResult evictMessages(
            List<Msg> messages,
            String agentName,
            RuntimeContext rc,
            Map<ToolResultFingerprint, String> replacements) {
        if (messages == null || messages.isEmpty()) {
            return new EvictionResult(messages, false);
        }

        List<Msg> rebuiltMessages = new ArrayList<>(messages.size());
        boolean changed = false;
        for (Msg msg : messages) {
            Msg rebuilt = evictMessage(msg, agentName, rc, replacements);
            rebuiltMessages.add(rebuilt);
            if (rebuilt != msg) {
                changed = true;
            }
        }

        return new EvictionResult(changed ? rebuiltMessages : messages, changed);
    }

    private Msg evictMessage(
            Msg msg,
            String agentName,
            RuntimeContext rc,
            Map<ToolResultFingerprint, String> replacements) {
        if (msg == null) {
            return null;
        }
        List<ContentBlock> contentBlocks = msg.getContent();
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return msg;
        }
        boolean changed = false;
        List<ContentBlock> rebuilt = new ArrayList<>(contentBlocks.size());
        for (ContentBlock block : contentBlocks) {
            if (block instanceof ToolResultBlock tr) {
                if (isEvicted(tr)) {
                    rebuilt.add(block);
                    continue;
                }
                ToolResultFingerprint original = fingerprint(tr);
                String placeholder = replacements.get(original);
                if (placeholder == null) {
                    placeholder = maybeEvict(tr, agentName, rc);
                    if (placeholder != null) {
                        replacements.put(original, placeholder);
                    }
                }
                if (placeholder != null) {
                    changed = true;
                    rebuilt.add(withPlaceholder(tr, placeholder));
                    continue;
                }
            }
            rebuilt.add(block);
        }
        if (!changed) {
            return msg;
        }
        return rebuildMessage(msg, rebuilt);
    }

    private Msg rebuildMessage(Msg msg, List<ContentBlock> content) {
        return Msg.builderForRole(msg.getRole())
                .id(msg.getId())
                .name(msg.getName())
                .content(content)
                .metadata(msg.getMetadata())
                .timestamp(msg.getTimestamp())
                .usage(msg.getUsage())
                .build();
    }

    private String maybeEvict(ToolResultBlock toolResult, String agentName, RuntimeContext rc) {
        String toolName = toolResult.getName();
        if (toolName != null && config.getExcludedToolNames().contains(toolName)) {
            return null;
        }
        String fullText = extractText(toolResult);
        if (fullText.length() <= config.getMaxResultChars()) {
            return null;
        }
        String toolCallId = toolResult.getId();
        try {
            String evictionPath = buildEvictionPath(agentName, toolCallId, fullText);
            WriteResult writeResult = filesystem.write(rc, evictionPath, fullText);
            if (!writeResult.isSuccess()) {
                log.warn(
                        "[{}] Failed to evict tool result [tool={}, id={}]: {}",
                        agentName,
                        toolName,
                        toolCallId,
                        writeResult.error());
                return null;
            }
            String placeholder = buildPlaceholder(fullText, evictionPath);
            log.info(
                    "[{}] Evicted large tool result [tool={}, id={}, chars={} -> {}]",
                    agentName,
                    toolName,
                    toolCallId,
                    fullText.length(),
                    evictionPath);
            return placeholder;
        } catch (Exception e) {
            log.warn(
                    "[{}] Exception evicting tool result [tool={}, id={}]: {}",
                    agentName,
                    toolName,
                    toolCallId,
                    e.getMessage());
            return null;
        }
    }

    private boolean isEvicted(ToolResultBlock toolResult) {
        return Boolean.TRUE.equals(toolResult.getMetadata().get(EVICTED_METADATA_KEY));
    }

    private ToolResultBlock withPlaceholder(ToolResultBlock toolResult, String placeholder) {
        Map<String, Object> metadata = new LinkedHashMap<>(toolResult.getMetadata());
        metadata.put(EVICTED_METADATA_KEY, true);
        return new ToolResultBlock(
                toolResult.getId(),
                toolResult.getName(),
                List.of(TextBlock.builder().text(placeholder).build()),
                metadata,
                toolResult.getState());
    }

    private String extractText(ToolResultBlock toolResult) {
        if (toolResult.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : toolResult.getOutput()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    private String buildEvictionPath(String agentName, String toolCallId, String fullText) {
        String base = config.getEvictionPath();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String safeAgent = agentName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String safeId = toolCallId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return base + "/" + safeAgent + "/" + safeId + "-" + contentHash(fullText);
    }

    private String contentHash(String content) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String buildPlaceholder(String fullText, String evictionPath) {
        int len = fullText.length();
        int pLen = Math.min(config.getPreviewChars(), len / 2);

        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        "Tool output was too large (%,d chars) and has been saved to `%s`.%n"
                                + "To read the full output, use `read_file` with path `%s`.%n%n",
                        len, evictionPath, evictionPath));

        if (pLen > 0) {
            sb.append(String.format("Preview (first %,d chars):%n", pLen));
            sb.append(fullText, 0, pLen);
            sb.append(String.format("%n%n... and last %,d chars:%n", pLen));
            sb.append(fullText, len - pLen, len);
        }

        return sb.toString();
    }

    private ToolResultFingerprint fingerprint(ToolResultBlock block) {
        return new ToolResultFingerprint(block.getId(), block.getName(), extractText(block));
    }

    private record ToolResultFingerprint(String id, String name, String text) {

        private ToolResultFingerprint {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    private record EvictionResult(List<Msg> messages, boolean changed) {}
}
