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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * LLM-based consolidation of daily memory ledgers into the curated {@code MEMORY.md}.
 *
 * <p>This component owns the second layer of the two-layer memory model:
 * <ul>
 *   <li><b>Layer 1 — daily ledger</b>: {@code memory/YYYY-MM-DD.md} files written by
 *       {@link MemoryFlushManager}, append-only, one section per compaction flush.</li>
 *   <li><b>Layer 2 — curated MEMORY.md</b>: Owned by this class. Periodically reads the
 *       daily ledgers (those modified since the last consolidation watermark) plus the
 *       current MEMORY.md, asks the LLM to merge / dedupe / trim, and overwrites
 *       MEMORY.md with the result.</li>
 * </ul>
 *
 * <p>A small state file ({@code memory/.consolidation_state}) records the timestamp of
 * the last successful consolidation. Daily files whose {@code modifiedAt} is at or before
 * that timestamp are skipped — reducing token usage and protecting MEMORY.md from being
 * re-rewritten with stale content.
 *
 * <p>All file I/O is performed via the {@link AbstractFilesystem} obtained from the
 * {@link WorkspaceManager}, so this class is backend-agnostic (works with Local,
 * Sandbox, and Remote filesystems without any direct {@code java.nio.file.Files} usage).
 */
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    /** Hidden state file inside {@code memory/} tracking the last consolidation Instant. */
    public static final String STATE_FILE = ".consolidation_state";

    private static final String CONSOLIDATION_PROMPT =
            """
            You are a memory consolidation assistant. You own the curated long-term memory \
            file MEMORY.md. Your job is to merge new daily ledger entries into MEMORY.md while \
            keeping it concise, deduplicated, and high-signal.

            You are given two inputs:
            1. The current MEMORY.md content (the existing curated long-term memory).
            2. New daily ledger entries that have been appended since the last consolidation.

            Rules:
            - MEMORY.md is the single source of truth for cross-day, cross-session knowledge. \
            Keep it stable and authoritative.
            - Daily ledger entries are stream-of-consciousness flush logs — they may be noisy, \
            redundant with MEMORY.md, or redundant with each other. Promote only what is \
            durable and reusable.
            - Deduplicate: if a new entry restates something MEMORY.md already covers, skip it.
            - Merge related facts: combine entries about the same topic into cohesive paragraphs \
            with clear section headers.
            - Update or remove stale information when new entries supersede it.
            - Keep total output within %d tokens (approximately %d characters); prioritize \
            recent and frequently-referenced information when trimming.

            Output the COMPLETE new MEMORY.md content (not just a diff). Use markdown.\
            """;

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final int maxMemoryTokens;

    public MemoryConsolidator(WorkspaceManager workspaceManager, Model model) {
        this(workspaceManager, model, 4000);
    }

    public MemoryConsolidator(WorkspaceManager workspaceManager, Model model, int maxMemoryTokens) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.maxMemoryTokens = maxMemoryTokens;
    }

    /**
     * Runs consolidation: reads daily files modified after the last watermark and the
     * current MEMORY.md, uses the LLM to merge them, overwrites MEMORY.md, and
     * advances the watermark on success.
     *
     * <p>If no daily files have been touched since the last run, this is a no-op.
     *
     * <h3>Consolidation Flow</h3>
     * <pre>
     *                     ┌──────────────────┐
     *                     │ 1. readWatermark │  ← 从 memory/.consolidation_state 读取上次时间戳
     *                     │    首次运行为 EPOCH │    文件内容如: 2026-06-06T10:30:00Z
     *                     └────────┬─────────┘
     *                              │
     *                     ┌────────▼─────────┐
     *                     │ 2. readMemoryMd  │  ← 读取当前 MEMORY.md
     *                     │  + readDailyEntries│   读取 memory/ 下所有 YYYY-MM-DD.md
     *                     └────────┬─────────┘    仅挑选 modifiedAt > watermark 的文件
     *                              │
     *                     ┌────────▼─────────┐
     *                     │ 3. 检查是否有新的 │  ← dailyEntries 为空？→ 直接返回 Mono.empty()
     *                     │    daily entries  │
     *                     └────────┬─────────┘
     *                              │
     *                     ┌────────▼─────────┐
     *                     │ 4. 组装 LLM 请求  │  ← SYSTEM: consolidation prompt（去重/合并规则）
     *                     │                  │     USER:   "Current MEMORY.md:\n...\n\n
     *                     │                  │              New daily entries:\n### 2026-06-05.md\n..."
     *                     └────────┬─────────┘
     *                              │
     *                     ┌────────▼─────────┐
     *                     │ 5. model.stream() │  ← 流式调用 LLM，收集全部 TextBlock 拼接
     *                     └────────┬─────────┘
     *                              │
     *                     ┌────────▼─────────┐
     *                     │ 6. writeConsolidated│ ← 把 LLM 输出的完整 MEMORY.md 覆盖写入
     *                     │    writeWatermark  │    把 runStart 时间戳写入 .consolidation_state
     *                     └──────────────────┘
     * </pre>
     *
     * <h3>Concrete Example</h3>
     * <pre>
     * 假设 workspace 当前状态：
     *   MEMORY.md:                    memory/
     *   ┌──────────────────┐         ├── .consolidation_state  →  "2026-06-05T18:00:00Z"
     *   │ - User prefers   │         ├── 2026-06-05.md         →  modifiedAt=2026-06-05T17:00Z  (skip)
     *   │   short answers  │         ├── 2026-06-06.md         →  modifiedAt=2026-06-06T09:30Z  (include)
     *   │ - Project uses   │         └── archive/
     *   │   Java 17        │
     *   └──────────────────┘
     *
     * Step 1: watermark = 2026-06-05T18:00:00Z
     * Step 2: currentMemory = "- User prefers short answers\n- Project uses Java 17"
     *         dailyEntries = "### 2026-06-06.md\nUser prefers dark theme\n\n"
     *         (2026-06-05.md modifiedAt=17:00 早于 watermark，被跳过)
     * Step 3: dailyEntries 不为空，继续
     * Step 4: LLM 收到：
     *         SYSTEM: "You are a memory consolidation assistant..."
     *         USER:   "Current MEMORY.md:\n
     *                  - User prefers short answers\n
     *                  - Project uses Java 17\n\n
     *                  New daily ledger entries to merge (since 2026-06-05T18:00:00Z):\n
     *                  ### 2026-06-06.md\n
     *                  User prefers dark theme\n"
     * Step 5: LLM 输出去重合并后的 MEMORY.md
     * Step 6: 写入 MEMORY.md，更新 .consolidation_state = "2026-06-06T10:30:00Z"
     *
     * 最终结果：
     *   MEMORY.md:
     *   ┌──────────────────────┐
     *   │ - User prefers:      │
     *   │   - short answers    │
     *   │   - dark theme       │   ← 新条目已合并
     *   │ - Project uses       │
     *   │   Java 17            │
     *   └──────────────────────┘
     * </pre>
     */
    public Mono<Void> consolidate(RuntimeContext rc) {
        Instant watermark = readWatermark(rc);
        Instant runStart = Instant.now();

        String currentMemory = workspaceManager.readMemoryMd(rc); // MEMORY.md
        String dailyEntries = readDailyEntries(rc, watermark);

        if (dailyEntries.isBlank()) {
            log.debug("No fresh daily entries since {} — skipping consolidation", watermark);
            return Mono.empty();
        }

        int maxChars = maxMemoryTokens * 4;
        String systemPrompt = String.format(CONSOLIDATION_PROMPT, maxMemoryTokens, maxChars);

        StringBuilder userContent = new StringBuilder();
        userContent.append("Current MEMORY.md:\n");
        userContent.append(currentMemory.isBlank() ? "(empty)" : currentMemory);
        userContent
                .append("\n\nNew daily ledger entries to merge")
                .append(watermark == Instant.EPOCH ? "" : " (since " + watermark + ")")
                .append(":\n");
        userContent.append(dailyEntries);

        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(systemPrompt).build())
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(userContent.toString()).build())
                        .build());

        return model.stream(messages, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, chatResponse) -> {
                            if (chatResponse.getContent() != null) {
                                for (var block : chatResponse.getContent()) {
                                    if (block instanceof TextBlock tb && tb.getText() != null) {
                                        sb.append(tb.getText());
                                    }
                                }
                            }
                            return sb;
                        })
                .flatMap(
                        sb -> {
                            String consolidated = sb.toString().strip();
                            if (consolidated.isBlank()) {
                                log.warn("Consolidation produced empty output, skipping");
                                return Mono.empty();
                            }
                            writeConsolidatedMemory(rc, consolidated);
                            writeWatermark(rc, runStart);
                            log.info(
                                    "MEMORY.md consolidated ({} chars), watermark advanced to {}",
                                    consolidated.length(),
                                    runStart);
                            return Mono.empty();
                        });
    }

    /**
     * Reads daily memory files modified strictly after the given watermark.
     * If watermark is {@link Instant#EPOCH}, all daily files are returned (first run).
     *
     * <p>All I/O is done through the {@link AbstractFilesystem} so this works equally well
     * with Local, Sandbox, and Store backends.
     */
    private String readDailyEntries(RuntimeContext rc, Instant watermark) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return "";
        }

        GlobResult glob = fs.glob(rc, "*.md", "memory");
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return "";
        }

        List<FileInfo> eligible = new ArrayList<>();
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String name = fileName(fi.path());
            if (name.equals(STATE_FILE) || name.equals("archive") || !name.endsWith(".md")) {
                continue;
            }
            if (isModifiedAfter(fi, watermark)) {
                eligible.add(fi);
            }
        }
        eligible.sort(Comparator.comparing(fi -> fileName(fi.path())));

        StringBuilder sb = new StringBuilder();
        for (FileInfo fi : eligible) {
            String rel = toRelative(fi.path());
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, rel);
            if (content != null && !content.isBlank()) {
                sb.append("### ").append(fileName(fi.path())).append("\n");
                sb.append(content.strip()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private static boolean isModifiedAfter(FileInfo fi, Instant watermark) {
        String modifiedAt = fi.modifiedAt();
        if (modifiedAt == null || modifiedAt.isBlank()) {
            return true; // be safe — include on unknown mtime
        }
        try {
            return Instant.parse(modifiedAt).isAfter(watermark);
        } catch (Exception e) {
            return true; // be safe on parse error
        }
    }

    /** Extracts the file name (last path segment) from a path string. */
    private static String fileName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String stripped = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = stripped.lastIndexOf('/');
        return idx >= 0 ? stripped.substring(idx + 1) : stripped;
    }

    /**
     * Converts an absolute filesystem path (e.g. {@code /memory/2025-01-01.md}) to a
     * workspace-relative path ({@code memory/2025-01-01.md}) for use with
     * {@link WorkspaceManager#readManagedWorkspaceFileUtf8}.
     */
    private static String toRelative(String path) {
        if (path == null) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private void writeConsolidatedMemory(RuntimeContext rc, String content) {
        workspaceManager.writeUtf8WorkspaceRelative(rc, "MEMORY.md", content);
    }

    static final String STATE_REL_PATH = "memory/" + STATE_FILE;

    /** Reads the last consolidation Instant, or {@link Instant#EPOCH} if none recorded. */
    Instant readWatermark(RuntimeContext rc) {
        try {
            String value = workspaceManager.readManagedWorkspaceFileUtf8(rc, STATE_REL_PATH);
            if (value == null || value.isBlank()) {
                return Instant.EPOCH;
            }
            return Instant.parse(value.strip());
        } catch (Exception e) {
            log.warn(
                    "Failed to read consolidation watermark at {}: {} — treating as EPOCH",
                    STATE_REL_PATH,
                    e.getMessage());
            return Instant.EPOCH;
        }
    }

    private void writeWatermark(RuntimeContext rc, Instant ts) {
        try {
            workspaceManager.writeUtf8WorkspaceRelative(rc, STATE_REL_PATH, ts.toString());
        } catch (Exception e) {
            log.warn(
                    "Failed to write consolidation watermark at {}: {}",
                    STATE_REL_PATH,
                    e.getMessage());
        }
    }
}
