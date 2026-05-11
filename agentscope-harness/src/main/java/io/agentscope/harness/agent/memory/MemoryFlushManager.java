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
package io.agentscope.harness.agent.memory;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Manages memory flush operations: extracting long-term memories from a conversation
 * window and appending them to today's daily memory ledger.
 *
 * <p><b>Two-layer memory model</b> (this class owns only the first layer):
 * <ul>
 *   <li>{@code memory/YYYY-MM-DD.md} — append-only daily ledger. Each compaction's flush
 *       appends a timestamped section here. Written ONLY by this class.</li>
 *   <li>{@code MEMORY.md} — globally curated, deduplicated, size-bounded long-term memory.
 *       Written ONLY by {@link MemoryConsolidator} on a periodic schedule. Treated as
 *       read-only context here.</li>
 * </ul>
 */
public class MemoryFlushManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushManager.class);

    private static final String FLUSH_SYSTEM_PROMPT =
            """
            You are a memory extraction assistant. Analyze the conversation below and extract \
            important facts, decisions, preferences, and contextual information that should be \
            remembered for future conversations.

            Output ONLY the extracted memories as a markdown bullet list. Each item should be \
            a concise, self-contained fact. Include dates, names, and specifics when available.

            If there is nothing worth remembering, respond with exactly: NO_REPLY

            Guidelines:
            - Extract user preferences, personal information, project decisions
            - Capture important technical decisions and their rationale
            - Note any commitments, deadlines, or action items
            - Record relationship context (who works on what, team structure)
            - Ignore routine greetings, tool invocations, and ephemeral status updates

            IMPORTANT — write target and append-only rules:
            - You are writing to TODAY'S daily memory ledger (memory/YYYY-MM-DD.md), NOT to \
            MEMORY.md. The daily ledger is append-only — your output will be appended after the \
            entries already shown below.
            - MEMORY.md is the curated long-term memory and is shown ONLY as read-only context. \
            Do NOT restate facts already covered by MEMORY.md or by today's earlier entries; a \
            separate consolidation step periodically merges new daily entries into MEMORY.md.
            - Keep each bullet point independent and self-contained so entries can be searched \
            individually.\
            """;

    private final WorkspaceManager workspaceManager;
    private final Model model;

    public MemoryFlushManager(WorkspaceManager workspaceManager, Model model) {
        this.workspaceManager = workspaceManager;
        this.model = model;
    }

    /**
     * Extracts long-term memories from messages using the model and writes them to disk.
     *
     * <p>Provides existing MEMORY.md and today's daily file content to the extraction LLM
     * so it can effectively deduplicate and avoid re-extracting known facts.
     */
    public Mono<Void> flushMemories(List<Msg> messages) {
        String conversationText = serializeMessages(messages);
        if (conversationText.isBlank()) {
            return Mono.empty();
        }

        String existingMemory = readExistingContent(WorkspaceConstants.MEMORY_MD);
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dailyRelPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        String existingDaily = readExistingContent(dailyRelPath);

        StringBuilder userPrompt = new StringBuilder();
        if (!existingMemory.isBlank()) {
            userPrompt
                    .append("MEMORY.md (read-only curated long-term memory — do NOT restate):\n")
                    .append(existingMemory)
                    .append("\n\n");
        }
        if (!existingDaily.isBlank()) {
            userPrompt
                    .append("Today's daily ledger so far (your output will be appended after):\n")
                    .append(existingDaily)
                    .append("\n\n");
        }
        userPrompt
                .append(
                        "Extract NEW memories from this conversation window (skip anything"
                                + " already covered above):\n\n")
                .append(conversationText);

        List<Msg> flushInput = new ArrayList<>();
        flushInput.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(FLUSH_SYSTEM_PROMPT).build())
                        .build());
        flushInput.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(userPrompt.toString()).build())
                        .build());

        return model.stream(flushInput, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, chatResponse) -> {
                            List<ContentBlock> blocks = chatResponse.getContent();
                            if (blocks != null) {
                                for (ContentBlock block : blocks) {
                                    if (block instanceof TextBlock tb) {
                                        String t = tb.getText();
                                        if (t != null) {
                                            sb.append(t);
                                        }
                                    }
                                }
                            }
                            return sb;
                        })
                .flatMap(
                        sb -> {
                            String extracted = sb.toString();
                            if (extracted.isBlank() || extracted.strip().equals("NO_REPLY")) {
                                log.debug("No memories to flush");
                                return Mono.empty();
                            }
                            writeMemoryFiles(extracted);
                            return Mono.empty();
                        });
    }

    /**
     * Returns the string path of the session JSONL file where messages for the given agent and
     * session are offloaded. Used by the compaction layer to embed the archive location in the
     * summary message so the agent can retrieve full history if needed.
     */
    public String resolveOffloadPath(String agentId, String sessionId) {
        try {
            Path p = workspaceManager.resolveSessionContextFile(agentId, sessionId);
            return p != null ? p.toString() : "";
        } catch (Exception e) {
            log.debug(
                    "Could not resolve offload path for agent={}, session={}: {}",
                    agentId,
                    sessionId,
                    e.getMessage());
            return "";
        }
    }

    /**
     * Offloads raw messages to the JSONL session tree.
     */
    public void offloadMessages(List<Msg> messages, String agentId, String sessionId) {
        offloadToSessionTree(messages, agentId, sessionId);

        log.debug(
                "Offloaded {} messages for agent={}, session={}",
                messages.size(),
                agentId,
                sessionId);
        workspaceManager.updateSessionIndex(agentId, sessionId, "conversation offloaded");
    }

    private void offloadToSessionTree(List<Msg> messages, String agentId, String sessionId) {
        try {
            Path contextFile = workspaceManager.resolveSessionContextFile(agentId, sessionId);
            SessionTree tree =
                    new SessionTree(
                            contextFile,
                            workspaceManager.getWorkspace(),
                            workspaceManager.getFilesystem());
            tree.load();
            // Sync from remote before appending so that entries written by a previous replica
            // (cross-machine handoff) are included in the merged file pushed to remote.
            tree.syncFromRemote();

            String lastId = null;
            for (Msg msg : messages) {
                if (msg.getRole() == null || isSessionContextMessage(msg)) {
                    continue;
                }
                String rendered = renderContentBlocks(msg);
                if (rendered == null || rendered.isBlank()) {
                    continue;
                }
                String toolCallId = extractToolCallId(msg);
                SessionEntry.MessageEntry entry =
                        new SessionEntry.MessageEntry(
                                null, lastId, null, msg.getRole().name(), rendered, toolCallId);
                tree.append(entry);
                lastId = entry.getId();
            }

            tree.flush();
        } catch (Exception e) {
            log.warn("Failed to offload to JSONL session tree: {}", e.getMessage());
        }
    }

    /**
     * Extracts a representative tool call ID from a message, if present.
     * For TOOL messages, returns the first ToolResultBlock's id.
     * For ASSISTANT messages with tool calls, returns the first ToolUseBlock's id.
     */
    private static String extractToolCallId(Msg msg) {
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock tr && tr.getId() != null) {
                return tr.getId();
            }
            if (block instanceof ToolUseBlock tu && tu.getId() != null) {
                return tu.getId();
            }
        }
        return null;
    }

    /**
     * Appends the extracted entries to today's daily memory ledger.
     *
     * <p>MEMORY.md is intentionally <b>NOT</b> touched here — it is owned by
     * {@link MemoryConsolidator}, which periodically merges the daily ledgers into a
     * curated, size-bounded MEMORY.md.
     */
    private void writeMemoryFiles(String content) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        String dailyEntry =
                String.format(
                        "\n## Memory Flush — %s\n%s\n",
                        java.time.Instant.now().toString(), content);

        String dailyRelPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        workspaceManager.appendUtf8WorkspaceRelative(dailyRelPath, dailyEntry);
    }

    private String readExistingContent(String relativePath) {
        try {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(relativePath);
            return content != null ? content : "";
        } catch (Exception e) {
            log.debug("Could not read {}: {}", relativePath, e.getMessage());
            return "";
        }
    }

    private static final String SESSION_CONTEXT_TAG = "<session_context>";

    /**
     * Serializes all messages into a textual representation for the memory extraction model.
     * Includes USER, ASSISTANT, and TOOL messages. Assistant tool-call blocks and tool-result
     * blocks are rendered as concise text so the model can extract memories from tool interactions.
     * The injected {@code <session_context>} user message is skipped as it contains only
     * environment metadata, not real conversation content.
     */
    private String serializeMessages(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getRole() != null && m.getRole() != MsgRole.SYSTEM)
                .filter(m -> !isSessionContextMessage(m))
                .map(this::renderMessage)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static boolean isSessionContextMessage(Msg msg) {
        if (msg.getRole() != MsgRole.USER) {
            return false;
        }
        String text = msg.getTextContent();
        return text != null && text.contains(SESSION_CONTEXT_TAG);
    }

    private String renderMessage(Msg msg) {
        String body = renderContentBlocks(msg);
        if (body == null) {
            return null;
        }
        return "[" + msg.getRole().name() + "]: " + body;
    }

    /**
     * Renders all content blocks of a message into a single text string.
     * Returns null if no renderable content is found.
     */
    private String renderContentBlocks(Msg msg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                String text = tb.getText();
                if (text != null && !text.isBlank()) {
                    parts.add(text);
                }
            } else if (block instanceof ToolUseBlock tu) {
                parts.add(renderToolUse(tu));
            } else if (block instanceof ToolResultBlock tr) {
                parts.add(renderToolResult(tr));
            }
        }

        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n", parts);
    }

    private static String renderToolUse(ToolUseBlock tu) {
        StringBuilder sb = new StringBuilder();
        sb.append("[tool_call: ").append(tu.getName());
        if (tu.getInput() != null && !tu.getInput().isEmpty()) {
            try {
                String inputJson = JsonUtils.getJsonCodec().toJson(tu.getInput());
                if (inputJson.length() > 500) {
                    inputJson = inputJson.substring(0, 500) + "...";
                }
                sb.append("(").append(inputJson).append(")");
            } catch (Exception e) {
                sb.append("(...)");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String renderToolResult(ToolResultBlock tr) {
        StringBuilder sb = new StringBuilder();
        sb.append("[tool_result");
        if (tr.getName() != null) {
            sb.append(": ").append(tr.getName());
        }
        sb.append("] ");

        List<ContentBlock> outputs = tr.getOutput();
        if (outputs != null) {
            for (ContentBlock out : outputs) {
                if (out instanceof TextBlock tb) {
                    String text = tb.getText();
                    if (text != null) {
                        if (text.length() > 1000) {
                            sb.append(text, 0, 1000).append("...(truncated)");
                        } else {
                            sb.append(text);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }
}
