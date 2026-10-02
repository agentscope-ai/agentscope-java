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
package io.agentscope.harness.agent.memory.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent writer for the session transcript JSONL ({@code .jsonl} / {@code .log.jsonl}).
 *
 * <p>Decoupled from {@link io.agentscope.harness.agent.memory.MemoryFlushManager}: transcript
 * completeness must not depend on memory-flush triggers or prompts. Callers should invoke this
 * at turn boundaries (see {@link
 * io.agentscope.harness.agent.middleware.TranscriptMiddleware}).
 *
 * <p>Tool calls and results are recorded as structured {@link SessionEntry.ToolUseEntry} /
 * {@link SessionEntry.ToolResultEntry} rows so the call/result process is machine-readable.
 * Content may still be truncated; truncated fields carry {@code truncated} + {@code originalSize}.
 */
public class SessionTranscriptWriter {

    private static final Logger log = LoggerFactory.getLogger(SessionTranscriptWriter.class);

    private static final String SESSION_CONTEXT_TAG = "<session_context>";

    /** Soft cap for tool input JSON in the transcript (process-complete; content may truncate). */
    public static final int INPUT_TRUNCATE_CHARS = 500;

    /** Soft cap for tool output text in the transcript. */
    public static final int OUTPUT_TRUNCATE_CHARS = 1000;

    private final WorkspaceManager workspaceManager;
    private final TranscriptStore transcriptStore;
    private final String tenant;

    public SessionTranscriptWriter(WorkspaceManager workspaceManager) {
        this(workspaceManager, null, null);
    }

    public SessionTranscriptWriter(
            WorkspaceManager workspaceManager, TranscriptStore transcriptStore, String tenant) {
        this.workspaceManager = workspaceManager;
        this.transcriptStore = transcriptStore;
        this.tenant = tenant != null && !tenant.isBlank() ? tenant : "default";
    }

    /**
     * Appends new messages to the session tree idempotently (dedup by stable entry ids).
     * Updates {@code sessions.json} index on success.
     */
    public void appendMessages(
            RuntimeContext rc, List<Msg> messages, String agentId, String sessionId) {
        if (messages == null || messages.isEmpty() || agentId == null || sessionId == null) {
            return;
        }
        try {
            Path contextFile = workspaceManager.resolveSessionContextFile(rc, agentId, sessionId);
            String contextRelPath =
                    WorkspaceConstants.AGENTS_DIR
                            + "/"
                            + agentId
                            + "/"
                            + WorkspaceConstants.SESSIONS_DIR
                            + "/"
                            + sessionId
                            + WorkspaceConstants.SESSION_CONTEXT_EXT;
            SessionTree tree =
                    new SessionTree(
                                    contextFile,
                                    workspaceManager.getWorkspace(),
                                    workspaceManager.getFilesystem(),
                                    workspaceManager.getIndex(),
                                    contextRelPath)
                            .setRuntimeContext(rc)
                            .setTranscriptStore(
                                    transcriptStore, new TranscriptRef(tenant, agentId, sessionId));
            tree.load();
            tree.syncFromRemote();

            List<SessionEntry> existingEntries = new ArrayList<>(tree.getAllEntries());
            Set<String> existingIds =
                    existingEntries.stream()
                            .map(SessionEntry::getId)
                            .collect(Collectors.toCollection(HashSet::new));
            String lastId =
                    existingEntries.isEmpty()
                            ? null
                            : existingEntries.get(existingEntries.size() - 1).getId();

            int appended = 0;
            for (Msg msg : messages) {
                if (msg.getRole() == null || isSessionContextMessage(msg)) {
                    continue;
                }
                List<SessionEntry> derived = deriveEntries(msg, lastId);
                for (SessionEntry entry : derived) {
                    if (existingIds.contains(entry.getId())) {
                        continue;
                    }
                    tree.append(entry);
                    existingIds.add(entry.getId());
                    lastId = entry.getId();
                    appended++;
                }
            }

            if (appended > 0) {
                tree.flush();
                workspaceManager.updateSessionIndex(
                        rc, agentId, sessionId, "transcript updated (" + appended + " entries)");
            }
            log.debug(
                    "Transcript append agent={} session={} msgs={} newEntries={}",
                    agentId,
                    sessionId,
                    messages.size(),
                    appended);
        } catch (Exception e) {
            log.warn(
                    "Failed to append transcript for agent={}, session={}: {}",
                    agentId,
                    sessionId,
                    e.getMessage());
        }
    }

    /**
     * Resolves the workspace-relative path of the session context JSONL (for compaction
     * summary references).
     */
    public String resolveContextPath(RuntimeContext rc, String agentId, String sessionId) {
        try {
            Path file = workspaceManager.resolveSessionContextFile(rc, agentId, sessionId);
            Path ws = workspaceManager.getWorkspace();
            if (ws != null && file.startsWith(ws)) {
                return ws.relativize(file).toString().replace('\\', '/');
            }
            return file.toString();
        } catch (Exception e) {
            log.debug(
                    "Could not resolve transcript path for agent={}, session={}: {}",
                    agentId,
                    sessionId,
                    e.getMessage());
            return "";
        }
    }

    /**
     * Derives zero or more session entries from a single {@link Msg}.
     *
     * <ul>
     *   <li>Text / thinking → one {@link SessionEntry.MessageEntry}</li>
     *   <li>Each {@link ToolUseBlock} → one {@link SessionEntry.ToolUseEntry}</li>
     *   <li>Each {@link ToolResultBlock} → one {@link SessionEntry.ToolResultEntry}</li>
     *   <li>Only non-text/non-tool blocks → one placeholder {@link SessionEntry.MessageEntry}
     *       with {@code blockTypes}</li>
     * </ul>
     */
    static List<SessionEntry> deriveEntries(Msg msg, String parentId) {
        List<SessionEntry> out = new ArrayList<>();
        String msgId = normalizeEntryId(msg.getId());
        String role = msg.getRole().name();
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            blocks = List.of();
        }

        List<String> textParts = new ArrayList<>();
        List<String> allBlockTypes = new ArrayList<>();
        List<ToolUseBlock> toolUses = new ArrayList<>();
        List<ToolResultBlock> toolResults = new ArrayList<>();

        for (ContentBlock block : blocks) {
            allBlockTypes.add(blockTypeName(block));
            if (block instanceof TextBlock tb) {
                String text = tb.getText();
                if (text != null && !text.isBlank()) {
                    textParts.add(text);
                }
            } else if (block instanceof ThinkingBlock th) {
                String thinking = th.getThinking();
                if (thinking != null && !thinking.isBlank()) {
                    textParts.add(thinking);
                }
            } else if (block instanceof ToolUseBlock tu) {
                toolUses.add(tu);
            } else if (block instanceof ToolResultBlock tr) {
                toolResults.add(tr);
            }
        }

        String lastParent = parentId;
        boolean hasText = !textParts.isEmpty();
        boolean hasTools = !toolUses.isEmpty() || !toolResults.isEmpty();

        if (hasText || (!hasTools && !allBlockTypes.isEmpty())) {
            // Text message, or A7 placeholder when only non-renderable blocks exist.
            String entryId = msgId != null ? msgId : null;
            String content = hasText ? String.join("\n", textParts) : "";
            List<String> blockTypes = hasText ? null : List.copyOf(allBlockTypes);
            SessionEntry.MessageEntry message =
                    new SessionEntry.MessageEntry(
                            entryId, lastParent, null, role, content, null, blockTypes);
            out.add(message);
            lastParent = message.getId();
        } else if (!hasTools && allBlockTypes.isEmpty()) {
            // Empty content list — still leave a trace so the turn is visible.
            SessionEntry.MessageEntry placeholder =
                    new SessionEntry.MessageEntry(
                            msgId, lastParent, null, role, "", null, List.of("empty"));
            out.add(placeholder);
            lastParent = placeholder.getId();
        }

        int useIndex = 0;
        for (ToolUseBlock tu : toolUses) {
            String toolCallId = tu.getId() != null ? tu.getId() : ("idx-" + useIndex);
            String entryId = msgId != null ? msgId + ":use:" + toolCallId : null;
            TruncatedMap truncatedInput = truncateInput(tu.getInput());
            SessionEntry.ToolUseEntry use =
                    new SessionEntry.ToolUseEntry(
                            entryId,
                            lastParent,
                            null,
                            toolCallId,
                            tu.getName(),
                            truncatedInput.value(),
                            truncatedInput.truncated(),
                            truncatedInput.originalSize());
            out.add(use);
            lastParent = use.getId();
            useIndex++;
        }

        int resultIndex = 0;
        for (ToolResultBlock tr : toolResults) {
            String toolCallId = tr.getId() != null ? tr.getId() : ("idx-" + resultIndex);
            String entryId = msgId != null ? msgId + ":result:" + toolCallId : null;
            TruncatedOutput truncatedOutput = truncateOutput(tr);
            SessionEntry.ToolResultEntry result =
                    new SessionEntry.ToolResultEntry(
                            entryId,
                            lastParent,
                            null,
                            toolCallId,
                            tr.getName(),
                            truncatedOutput.text(),
                            truncatedOutput.blockTypes(),
                            truncatedOutput.truncated(),
                            truncatedOutput.originalSize());
            out.add(result);
            lastParent = result.getId();
            resultIndex++;
        }

        return out;
    }

    private static TruncatedMap truncateInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return new TruncatedMap(input == null ? Map.of() : input, false, null);
        }
        try {
            String json = JsonUtils.getJsonCodec().toJson(input);
            int original = json.length();
            if (original <= INPUT_TRUNCATE_CHARS) {
                return new TruncatedMap(input, false, null);
            }
            // Keep structure machine-readable: store a single "_truncated" preview map.
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("_preview", json.substring(0, INPUT_TRUNCATE_CHARS));
            preview.put("_truncated", true);
            preview.put("_originalSize", original);
            return new TruncatedMap(preview, true, original);
        } catch (Exception e) {
            return new TruncatedMap(Map.of("_error", "serialize_failed"), true, null);
        }
    }

    private static TruncatedOutput truncateOutput(ToolResultBlock tr) {
        List<String> blockTypes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ContentBlock> outputs = tr.getOutput();
        if (outputs != null) {
            for (ContentBlock out : outputs) {
                blockTypes.add(blockTypeName(out));
                if (out instanceof TextBlock tb && tb.getText() != null) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(tb.getText());
                }
            }
        }
        String full = text.toString();
        int original = full.length();
        if (original > OUTPUT_TRUNCATE_CHARS) {
            return new TruncatedOutput(
                    full.substring(0, OUTPUT_TRUNCATE_CHARS),
                    blockTypes.isEmpty() ? null : List.copyOf(blockTypes),
                    true,
                    original);
        }
        return new TruncatedOutput(
                full, blockTypes.isEmpty() ? null : List.copyOf(blockTypes), false, null);
    }

    private static String blockTypeName(ContentBlock block) {
        if (block instanceof TextBlock) {
            return "text";
        }
        if (block instanceof ThinkingBlock) {
            return "thinking";
        }
        if (block instanceof ImageBlock) {
            return "image";
        }
        if (block instanceof ToolUseBlock) {
            return "tool_use";
        }
        if (block instanceof ToolResultBlock) {
            return "tool_result";
        }
        String simple = block.getClass().getSimpleName();
        if (simple.endsWith("Block")) {
            return simple.substring(0, simple.length() - 5).toLowerCase();
        }
        return simple.toLowerCase();
    }

    private static boolean isSessionContextMessage(Msg msg) {
        if (msg.getRole() != MsgRole.USER) {
            return false;
        }
        String text = msg.getTextContent();
        return text != null && text.contains(SESSION_CONTEXT_TAG);
    }

    private static String normalizeEntryId(String id) {
        return id != null && !id.isBlank() ? id : null;
    }

    private record TruncatedMap(
            Map<String, Object> value, boolean truncated, Integer originalSize) {}

    private record TruncatedOutput(
            String text, List<String> blockTypes, boolean truncated, Integer originalSize) {}
}
