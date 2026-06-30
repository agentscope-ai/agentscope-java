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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that evicts oversized tool results to the {@link AbstractFilesystem}
 * immediately after each acting phase, before downstream reasoning sees the bloated
 * message list.
 *
 * <p>When the text content of a {@link ToolResultBlock} in the freshly-added tool-result
 * messages exceeds {@link ToolResultEvictionConfig#getMaxResultChars()}, this middleware:
 * <ol>
 *   <li>Writes the full result to
 *       {@code {evictionPath}/{agentName}/{sanitized-toolCallId}} in the filesystem.</li>
 *   <li>Replaces the in-context {@code ToolResultBlock} with a compact placeholder containing
 *       a head+tail preview and an instruction to use {@code readFile} for the full content.</li>
 *   <li>Mutates {@link AgentState#contextMutable()} in place so subsequent reasoning rounds
 *       see only the placeholder.</li>
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
        evictOversizedToolResults(agent, rc);
        return next.apply(input);
    }

    private void evictOversizedToolResults(Agent agent, RuntimeContext rc) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return;
        }
        List<Msg> ctx = state.contextMutable();
        String agentName = agent.getName();
        for (int i = 0; i < ctx.size(); i++) {
            Msg msg = ctx.get(i);
            if (msg == null || msg.getRole() != MsgRole.TOOL) {
                continue;
            }
            Msg rebuilt = evictMessage(msg, agentName, rc);
            if (rebuilt != msg) {
                ctx.set(i, rebuilt);
            }
        }
    }

    private Msg evictMessage(Msg msg, String agentName, RuntimeContext rc) {
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
        return Msg.builder()
                .id(msg.getId())
                .name(msg.getName())
                .role(msg.getRole())
                .content(rebuilt)
                .metadata(msg.getMetadata())
                .timestamp(msg.getTimestamp())
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
        String evictionPath = buildEvictionPath(agentName, toolCallId);
        try {
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
                    null);
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
        if (!base.startsWith("/")) {
            base = "/" + base;
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
}
