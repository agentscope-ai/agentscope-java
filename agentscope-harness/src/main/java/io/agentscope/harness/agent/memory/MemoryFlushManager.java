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

import io.agentscope.core.agent.RuntimeContext;
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
     * Extracts long-term memories(追加 ## Memory Flush — extracted...到/memory/yyy-MM-dd.md中)
     * from messages(这里是会话消息)  using the model and writes them to disk.
     *
     * <p>Provides existing MEMORY.md and today's daily file content to the extraction LLM
     * so it can effectively deduplicate and avoid re-extracting known facts.
     */
    public Mono<Void> flushMemories(RuntimeContext rc, List<Msg> messages) {
        String conversationText = serializeMessages(messages);
        if (conversationText.isBlank()) {
            return Mono.empty();
        }
        // 当前时刻的memory.md文档内容
        String existingMemory = readExistingContent(rc, WorkspaceConstants.MEMORY_MD);
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dailyRelPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        // 旧的memory/yyyy-MM-dd.md 文档内容
        String existingDaily = readExistingContent(rc, dailyRelPath);

        StringBuilder userPrompt = new StringBuilder();
        if (!existingMemory.isBlank()) {
            userPrompt
                    .append("MEMORY.md (read-only curated long-term memory — do NOT restate):\n")
                    .append(existingMemory)
                    .append("\n\n");
        }
        if (!existingDaily.isBlank()) {
            // 告诉LLM到目前为止的 memory/yyyy-MM-dd.md 内容
            userPrompt
                    .append("Today's daily ledger so far (your output will be appended after):\n")
                    .append(existingDaily)
                    .append("\n\n");
        }
        // 告诉LLM 从当前会话窗口中 抽取新的memories
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

        // model.stream() 返回 Flux<ChatResponse>，即流式输出的逐块响应
        // 每个 ChatResponse 包含一小段增量文本（流式 chunk）
        // reduce 的作用：将流式 chunks 拼接成一个完整的字符串
        //
        // reduce 签名：
        //   reduce(initial, BiFunction<T, U, U> accumulator)
        //     - 参数1: 初始值（空 StringBuilder）
        //     - 参数2: (累加器, 当前元素) → 累加器
        //     - 返回值: Mono<U>，只在流完成后发射一次，包含最终累加结果
        //
        // 这里不用 map+collectList 的原因：
        //   - reduce 流式消费，边收 chunk 边 append，内存保留一个 StringBuilder
        //   - collectList 需要先把所有 chunk 缓存到一个 List，再 join，多一次 List 分配
        //   - reduce 语义更直接：把多个值折叠成单个值
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
                // reduce 返回 Mono<StringBuilder>，flatMap 拿到的是完整拼接后的文本
                // 追加 ## Memory Flush — extracted...到/memory/yyy-MM-dd.md中
                .flatMap(
                        sb -> {
                            String extracted = sb.toString();
                            if (extracted.isBlank() || extracted.strip().equals("NO_REPLY")) {
                                log.debug("No memories to flush");
                                return Mono.empty();
                            }
                            writeMemoryFiles(rc, extracted);
                            return Mono.empty();
                        });
    }

    /**
     * Returns the string path of the session JSONL file where messages for the given agent and
     * session are offloaded. Used by the compaction layer to embed the archive location in the
     * summary message so the agent can retrieve full history if needed.
     */
    public String resolveOffloadPath(RuntimeContext rc, String agentId, String sessionId) {
        try {
            Path p = workspaceManager.resolveSessionContextFile(rc, agentId, sessionId);
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
     * 对话归档：将传入的消息列表持久化到 JSONL session tree，并更新空间索引。
     *
     * <p>调用时机（两处，各自传不同的消息列表）：
     *
     * <p><b>1. CompactionHook（PreReasoningEvent 阶段，priority=10）</b>
     * <pre>{@code
     * // ConversationCompactor.compactIfNeeded():
     * List<Msg> prefix = filterSummaryMessages(messages.subList(0, cutoff));
     * List<Msg> tail = messages.subList(cutoff, messages.size());
     * // 注意：此处传入的是全部 messages（prefix + tail），不是只传 prefix
     * flushManager.offloadMessages(rc, messages, agentId, sessionId);
     * // 然后替换 Memory: [summaryMsg] + tail
     * }</pre>
     *
     * <p><b>2. MemoryFlushHook（PostCallEvent 阶段，priority=5）</b>
     * <pre>{@code
     * // MemoryFlushHook.doFlush():
     * List<Msg> messages = memory.getMessages(); // Memory 中当前的全部消息
     * flushManager.offloadMessages(rc, messages, agentId, sessionId);
     * }</pre>
     *
     * <p>消息流示意（触发压缩的场景）：
     * <pre>{@code
     * 时间线: PreReasoning → LLM推理 → 产生新消息 → PostCall
     *
     * Memory 快照（PreReasoning）: [msg1, msg2, msg3, msg4, msg5, msg6, msg7, msg8]
     *                                        ↑── prefix ──↑  ↑─ tail ─↑
     * CompactionHook.offloadMessages(messages) ← 传入全部 8 条，写入 JSONL
     * 压缩后 Memory → [summary, msg6, msg7, msg8]
     *
     * ... LLM 推理 ...
     * Memory 新增本轮消息: [summary, msg6, msg7, msg8, newUser, newAssistant]
     *
     * MemoryFlushHook.offloadMessages(memory.getMessages()) ← 传入全部 6 条
     *   ↓ 写入 JSONL（msg6~msg8 和 summary 也会再次记入，SessionTree 是
     *     追加式日志，不同时间点的快照各自独立记录）
     * }</pre>
     *
     * <p>不触发压缩的场景：
     * <pre>{@code
     * MemoryFlushHook.offloadMessages(memory.getMessages()) ← 传入 Memory 中全部消息
     * CompactionHook 返回 Optional.empty()，不调用 offloadMessages
     * }</pre>
     *
     * <p>两步操作：
     * <ol>
     *   <li>{@code offloadToSessionTree} — 每条消息渲染为文本后追加到
     *       {@code agents/<agentId>/sessions/<sessionId>.jsonl}
     *   <li>{@code updateSessionIndex} — 同步更新 SQLite/远程索引，标记该
     *       session 有新的归档内容，方便 SessionSearchTool 检索
     * </ol>
     *
     * @param rc        运行时上下文（用户/session 标识）
     * @param messages  要归档的消息列表（由调用方决定范围）
     * @param agentId   agent 标识
     * @param sessionId session 标识
     */
    public void offloadMessages(
            RuntimeContext rc, List<Msg> messages, String agentId, String sessionId) {
        offloadToSessionTree(rc, messages, agentId, sessionId);

        log.debug(
                "Offloaded {} messages for agent={}, session={}",
                messages.size(),
                agentId,
                sessionId);
        workspaceManager.updateSessionIndex(rc, agentId, sessionId, "conversation offloaded");
    }

    /**
     * 将消息列表逐条追加到 SessionTree 的 JSONL 文件中。
     *
     * <p>消息流示例（Memory 中的消息 → 过滤 → 写入 JSONL）：
     * <pre>{@code
     * Memory 中当前消息:
     *   [0] SYSTEM   "You are a helpful assistant..."
     *   [1] USER     "帮我写一个 Python 爬虫"
     *   [2] ASSISTANT  ToolUseBlock: read_file("requirements.txt")
     *   [3] TOOL     ToolResultBlock: "requests>=2.28\nbeautifulsoup4>=4.11"
     *   [4] ASSISTANT  TextBlock: "好的，我来写一个爬虫..."
     *   [5] USER     "再加个异步支持"
     *
     * 过滤规则：
     *   (1) role==null 的无效消息 ✓ 跳过
     *   (2) 包含 "<session_context>" 字符串的 USER 消息 ✓ 跳过
     *       — 预留的防御性检查，当前没有任何代码注入带此标签的消息，属于 no-op
     *   (3) 渲染后纯文本为空 ✓ 跳过
     *
     * 最终写入 JSONL:
     *   [1] USER       "帮我写一个 Python 爬虫"
     *   [2] ASSISTANT  "ToolUseBlock: read_file requirements.txt"
     *   [3] TOOL       "ToolResultBlock: requests>=2.28..."
     *   [4] ASSISTANT  "好的，我来写一个爬虫..."
     *   [5] USER       "再加个异步支持"
     * }</pre>
     *
     * <p>注意：压缩摘要消息的过滤不在本方法内，而是由上游调用方处理：
     * <pre>{@code
     * // ConversationCompactor.compactIfNeeded() 中:
     * List<Msg> prefix = filterSummaryMessages(prefix);  // 先把 __compaction_summary__ 过滤掉
     * flushManager.offloadMessages(rc, prefix, agentId, sessionId);  // 再传给本方法
     *
     * // filterSummaryMessages 的实现:
     * messages.stream()
     *     .filter(m -> !"__compaction_summary__".equals(m.getName()))
     *     .collect(Collectors.toList());
     * }</pre>
     */
    private void offloadToSessionTree(
            RuntimeContext rc, List<Msg> messages, String agentId, String sessionId) {
        try {
            Path contextFile = workspaceManager.resolveSessionContextFile(rc, agentId, sessionId);
            // agents/<agentId>/sessions/<sessionId>.jsonl
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
                            .setRuntimeContext(rc);
            tree.load();
            // Sync from remote before appending so that entries written by a previous replica
            // (cross-machine handoff) are included in the merged file pushed to remote.
            tree.syncFromRemote();

            String lastId = null;
            for (Msg msg : messages) {
                // 过滤：(1) role 为空 (2) <session_context> 标记（防御性检查，当前为 no-op）
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
    private void writeMemoryFiles(RuntimeContext rc, String content) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        String dailyEntry =
                String.format(
                        "\n## Memory Flush — %s\n%s\n",
                        java.time.Instant.now().toString(), content);

        String dailyRelPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        workspaceManager.appendUtf8WorkspaceRelative(rc, dailyRelPath, dailyEntry);
    }

    private String readExistingContent(RuntimeContext rc, String relativePath) {
        try {
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, relativePath);
            return content != null ? content : "";
        } catch (Exception e) {
            log.debug("Could not read {}: {}", relativePath, e.getMessage());
            return "";
        }
    }

    private static final String SESSION_CONTEXT_TAG = "<session_context>";

    /**
     * 将消息列表序列化为纯文本，供记忆提取模型使用。
     *
     * <p>包含 USER、ASSISTANT、TOOL 消息。工具调用/结果块渲染为简洁文本。
     *
     * <p>过滤规则：
     * <ul>
     *   <li>SYSTEM 消息 — 跳过（系统提示词不是对话内容）
     *   <li>包含 {@code <session_context>} 字符串的 USER 消息 — 跳过（预留的防御性检查，
     *       当前代码中无任何地方注入此标签，实际为 no-op）
     * </ul>
     */
    private String serializeMessages(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getRole() != null && m.getRole() != MsgRole.SYSTEM)
                .filter(m -> !isSessionContextMessage(m))
                .map(this::renderMessage)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 检查是否为包含 {@code <session_context>} 标记的 USER 消息。
     *
     * <p>预留的防御性检查：当前代码中没有任何地方向消息里注入此标签，
     * WorkspaceContextHook 通过 {@code appendSystemContent()} 把 Session Context 注入
     * SYSTEM 消息，格式为 Markdown 标题 {@code ## Session Context}，不含 XML 标签。
     * 因此此方法在当前代码路径中始终返回 false（no-op）。
     */
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
