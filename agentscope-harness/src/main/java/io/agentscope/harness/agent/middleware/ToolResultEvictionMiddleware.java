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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that evicts oversized tool results to the {@link AbstractFilesystem} before
 * downstream reasoning sees the bloated message list.
 *
 * <p>When the text content of a {@link ToolResultBlock} in the freshly-added tool-result
 * messages exceeds {@link ToolResultEvictionConfig#getMaxResultChars()}, this middleware:
 * <ol>
 *   <li>Writes the full result to
 *       {@code {evictionPath}/{agentName}/{sanitized-toolCallId}} in the filesystem.</li>
 *   <li>Replaces the in-context {@code ToolResultBlock} with a compact placeholder containing
 *       a head+tail preview and an instruction to use {@code readFile} for the full content.</li>
 *   <li>Rebuilds the current {@link ReasoningInput} so the immediate model call sees only the
 *       placeholder.</li>
 *   <li>Applies the same replacements to {@link AgentState#contextMutable()} so subsequent
 *       reasoning rounds and persisted state also see only the placeholder.</li>
 * </ol>
 *
 * <p>Tools listed in {@link ToolResultEvictionConfig#getExcludedToolNames()} are never evicted
 * (e.g. {@code readFile} — evicting would cause re-read loops).
 */
public class ToolResultEvictionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ToolResultEvictionMiddleware.class);

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
        EvictionResult result = evictMessages(input.messages(), agent.getName(), rc);
        if (!result.changed()) {
            return next.apply(input);
        }

        applyReplacementsToAgentState(agent, rc, result.replacements());
        return next.apply(new ReasoningInput(result.messages(), input.tools(), input.options()));
    }

    private EvictionResult evictMessages(List<Msg> messages, String agentName, RuntimeContext rc) {
        if (messages == null || messages.isEmpty()) {
            return new EvictionResult(messages, Map.of(), false);
        }

        List<Msg> rebuiltMessages = new ArrayList<>(messages.size());
        Map<ToolResultKey, ToolResultBlock> replacements = new HashMap<>();
        boolean changed = false;
        for (Msg msg : messages) {
            Msg rebuilt = evictMessage(msg, agentName, rc, replacements);
            rebuiltMessages.add(rebuilt);
            if (rebuilt != msg) {
                changed = true;
            }
        }

        return new EvictionResult(changed ? rebuiltMessages : messages, replacements, changed);
    }

    private Msg evictMessage(
            Msg msg,
            String agentName,
            RuntimeContext rc,
            Map<ToolResultKey, ToolResultBlock> replacements) {
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
                ToolResultBlock maybeEvicted = maybeEvict(tr, agentName, rc);
                if (maybeEvicted != tr) {
                    replacements.put(ToolResultKey.from(tr), maybeEvicted);
                    changed = true;
                    rebuilt.add(maybeEvicted);
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

    private void applyReplacementsToAgentState(
            Agent agent, RuntimeContext rc, Map<ToolResultKey, ToolResultBlock> replacements) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null || replacements.isEmpty()) {
            return;
        }

        List<Msg> context = state.contextMutable();
        for (int i = 0; i < context.size(); i++) {
            Msg msg = context.get(i);
            Msg rebuilt = replaceToolResults(msg, replacements);
            if (rebuilt != msg) {
                context.set(i, rebuilt);
            }
        }
    }

    private Msg replaceToolResults(Msg msg, Map<ToolResultKey, ToolResultBlock> replacements) {
        if (msg == null || msg.getContent() == null || msg.getContent().isEmpty()) {
            return msg;
        }

        List<ContentBlock> rebuilt = new ArrayList<>(msg.getContent().size());
        boolean changed = false;
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock toolResult) {
                ToolResultBlock replacement = replacements.get(ToolResultKey.from(toolResult));
                if (replacement != null && replacement != toolResult) {
                    rebuilt.add(replacement);
                    changed = true;
                    continue;
                }
            }
            rebuilt.add(block);
        }

        return changed ? rebuildMessage(msg, rebuilt) : msg;
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

    private ToolResultBlock maybeEvict(
            ToolResultBlock toolResult, String agentName, RuntimeContext rc) {
        String toolName = toolResult.getName();
        if (toolName != null && config.getExcludedToolNames().contains(toolName)) {
            return toolResult;
        }
        String fullText = extractText(toolResult);
        if (fullText.length() <= config.getMaxResultChars()) {
            return toolResult;
        }
        String toolCallId = toolResult.getId();
        try {
            String evictionPath = buildEvictionPath(agentName, toolCallId);
            WriteResult writeResult = filesystem.write(rc, evictionPath, fullText);
            if (!writeResult.isSuccess()) {
                log.warn(
                        "[{}] Failed to evict tool result [tool={}, id={}]: {}",
                        agentName,
                        toolName,
                        toolCallId,
                        writeResult.error());
                return toolResult;
            }
            String placeholder = buildPlaceholder(fullText, evictionPath);
            log.info(
                    "[{}] Evicted large tool result [tool={}, id={}, chars={} -> {}]",
                    agentName,
                    toolName,
                    toolCallId,
                    fullText.length(),
                    evictionPath);
            return new ToolResultBlock(
                    toolResult.getId(),
                    toolResult.getName(),
                    List.of(TextBlock.builder().text(placeholder).build()),
                    toolResult.getMetadata(),
                    toolResult.getState());
        } catch (Exception e) {
            log.warn(
                    "[{}] Exception evicting tool result [tool={}, id={}]: {}",
                    agentName,
                    toolName,
                    toolCallId,
                    e.getMessage());
            return toolResult;
        }
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

    private String buildEvictionPath(String agentName, String toolCallId) {
        String base = config.getEvictionPath();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String safeAgent = agentName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String safeId = toolCallId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return base + "/" + safeAgent + "/" + safeId;
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

    private record ToolResultKey(String id, String name) {

        private static ToolResultKey from(ToolResultBlock block) {
            return new ToolResultKey(block.getId(), block.getName());
        }
    }

    private record EvictionResult(
            List<Msg> messages,
            Map<ToolResultKey, ToolResultBlock> replacements,
            boolean changed) {}
}
