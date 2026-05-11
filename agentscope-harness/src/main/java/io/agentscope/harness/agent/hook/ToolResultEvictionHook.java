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
package io.agentscope.harness.agent.hook;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that evicts oversized tool results to the {@link AbstractFilesystem} immediately after
 * each tool call, before the result is stored in the agent's memory.
 *
 * <p>When the text content of a {@link ToolResultBlock} exceeds
 * {@link ToolResultEvictionConfig#getMaxResultChars()}, this hook:
 * <ol>
 *   <li>Writes the full result to
 *       {@code {evictionPath}/{agentName}/{sanitized-toolCallId}} in the filesystem.</li>
 *   <li>Replaces the in-context {@code ToolResultBlock} with a compact placeholder containing
 *       a head+tail preview and an instruction to use {@code readFile} for the full content.</li>
 *   <li>Calls {@link PostActingEvent#setToolResult} so downstream hooks and memory see only
 *       the placeholder.</li>
 * </ol>
 *
 * <p><b>Independence from other context-management mechanisms:</b>
 * <ul>
 *   <li><b>This hook</b> fires on {@link PostActingEvent} — once per tool call, triggered by
 *       individual result <em>size</em> (context width).</li>
 *   <li><b>Argument truncation</b> runs inside {@link CompactionHook}
 *       at {@code PreReasoningEvent} — triggered by accumulated message count/tokens.</li>
 *   <li><b>Conversation compaction</b> runs inside {@code CompactionHook} at
 *       {@code PreReasoningEvent} — triggered by overall conversation length (context depth).</li>
 * </ul>
 * Each mechanism evaluates its own independent condition; none depends on the others having run.
 *
 * <p>Runs at priority 50, <em>after</em> {@link AgentTraceHook} (priority 0) so the original
 * result size is logged before the placeholder replaces it.
 *
 * <p>Tools listed in {@link ToolResultEvictionConfig#getExcludedToolNames()} are never evicted
 * (e.g. {@code readFile} — evicting would cause re-read loops).
 */
public class ToolResultEvictionHook implements Hook {

    private static final RuntimeContext DEFAULT_FS_RUNTIME = RuntimeContext.empty();

    private static final Logger log = LoggerFactory.getLogger(ToolResultEvictionHook.class);

    private final AbstractFilesystem filesystem;
    private final ToolResultEvictionConfig config;

    public ToolResultEvictionHook(AbstractFilesystem filesystem, ToolResultEvictionConfig config) {
        this.filesystem = filesystem;
        this.config = config;
    }

    @Override
    public int priority() {
        // After AgentTraceHook (0) — original result size is logged first, then replaced
        return 50;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent postActing)) {
            return Mono.just(event);
        }

        ToolUseBlock toolUse = postActing.getToolUse();
        ToolResultBlock toolResult = postActing.getToolResult();

        if (toolUse == null || toolResult == null) {
            return Mono.just(event);
        }

        String toolName = toolUse.getName();
        if (config.getExcludedToolNames().contains(toolName)) {
            return Mono.just(event);
        }

        String fullText = extractText(toolResult);
        if (fullText.length() <= config.getMaxResultChars()) {
            return Mono.just(event);
        }

        String agentName = event.getAgent().getName();
        String toolCallId = toolUse.getId();
        String evictionPath = buildEvictionPath(agentName, toolCallId);

        try {
            WriteResult writeResult = filesystem.write(DEFAULT_FS_RUNTIME, evictionPath, fullText);
            if (!writeResult.isSuccess()) {
                log.warn(
                        "[{}] Failed to evict tool result [tool={}, id={}]: {}",
                        agentName,
                        toolName,
                        toolCallId,
                        writeResult.error());
                return Mono.just(event);
            }

            String placeholder = buildPlaceholder(fullText, evictionPath);
            ToolResultBlock evicted =
                    new ToolResultBlock(
                            toolResult.getId(),
                            toolResult.getName(),
                            List.of(TextBlock.builder().text(placeholder).build()),
                            null);

            postActing.setToolResult(evicted);

            log.info(
                    "[{}] Evicted large tool result [tool={}, id={}, chars={} -> {}]",
                    agentName,
                    toolName,
                    toolCallId,
                    fullText.length(),
                    evictionPath);
        } catch (Exception e) {
            log.warn(
                    "[{}] Exception evicting tool result [tool={}, id={}]: {}",
                    agentName,
                    toolName,
                    toolCallId,
                    e.getMessage());
        }

        return Mono.just(event);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
        // Sanitize to filesystem-safe characters
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
