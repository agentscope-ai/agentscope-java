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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.FinishReasons;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.context.ContextModelCalls;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p><b>Commit gate.</b> The LLM merge runs through the budget-only auxiliary call
 * boundary ({@link ContextModelCalls#auxiliary} — no parent history, tools, or task
 * projection, no recursive compaction). Its result replaces {@code MEMORY.md} and
 * advances the watermark only when it is a <em>complete</em> (not length-truncated),
 * text-only (no tool-call blocks), non-empty body within the configured body-text
 * target. Truncation, tool-call output, an empty or over-budget body, a model error,
 * a pre-commit cancellation, or a rejected auxiliary input leaves the existing
 * {@code MEMORY.md} and the watermark untouched. The watermark advances only after
 * the write is confirmed by reading the file back. An unconfirmed write leaves the
 * watermark unchanged so the next run retries on the same ledgers; the memory body
 * may already have been written.
 *
 * <p>{@code maxMemoryTokens} is that <em>body-text target</em>: it is stated in the
 * prompt (tokens and the approximate character equivalent) and enforced on the
 * committed body. It is deliberately <b>not</b> passed to the model API as an output
 * limit — a reasoning model's API limit must also cover its reasoning tokens, so
 * equating the two would either starve the body or pad the limit. The model's own
 * output limit keeps its provider default; should it cut the response short, the
 * truncation check above refuses to commit it.
 */
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    /** Hidden state file inside {@code memory/} tracking the last consolidation Instant. */
    public static final String STATE_FILE = ".consolidation_state";

    /**
     * Default prompt for the consolidation step. Exposed publicly so callers can extend
     * (e.g. append project-specific guidelines) when constructing
     * {@link io.agentscope.harness.agent.memory.MemoryConfig}. The prompt contains exactly
     * two {@code %d} placeholders (max-tokens, max-chars) that are filled in at call time.
     */
    public static final String DEFAULT_CONSOLIDATION_PROMPT =
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

    private static final List<String> WATERMARK_NAMESPACE = List.of("memory", "consolidation");
    private static final String WATERMARK_KEY = "watermark";
    private static final int MAX_CAS_RETRIES = 5;

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final String consolidationPrompt;
    private final int maxMemoryTokens;
    private final BaseStore baseStore;

    public MemoryConsolidator(WorkspaceManager workspaceManager, Model model) {
        this(workspaceManager, model, DEFAULT_CONSOLIDATION_PROMPT, 4000, null);
    }

    public MemoryConsolidator(WorkspaceManager workspaceManager, Model model, int maxMemoryTokens) {
        this(workspaceManager, model, DEFAULT_CONSOLIDATION_PROMPT, maxMemoryTokens, null);
    }

    /**
     * @param consolidationPrompt prompt template for the consolidation LLM call. Must contain
     *     exactly two {@code %d} placeholders (max-tokens, max-chars). {@code null} falls back
     *     to {@link #DEFAULT_CONSOLIDATION_PROMPT}.
     */
    public MemoryConsolidator(
            WorkspaceManager workspaceManager,
            Model model,
            String consolidationPrompt,
            int maxMemoryTokens) {
        this(workspaceManager, model, consolidationPrompt, maxMemoryTokens, null);
    }

    /**
     * @param baseStore optional distributed store for the consolidation watermark; when set,
     *     watermark reads prefer the store and writes use CAS with a filesystem fallback
     */
    public MemoryConsolidator(
            WorkspaceManager workspaceManager,
            Model model,
            String consolidationPrompt,
            int maxMemoryTokens,
            BaseStore baseStore) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.consolidationPrompt =
                consolidationPrompt != null ? consolidationPrompt : DEFAULT_CONSOLIDATION_PROMPT;
        this.maxMemoryTokens = maxMemoryTokens;
        this.baseStore = baseStore;
    }

    /**
     * Runs consolidation: reads daily files modified after the last watermark and the
     * current MEMORY.md, uses the LLM to merge them, overwrites MEMORY.md, and
     * advances the watermark on success.
     *
     * <p>If no daily files have been touched since the last run, this is a no-op.
     */
    public Mono<Void> consolidate(RuntimeContext rc) {
        Instant watermark = readWatermark(rc);
        Instant runStart = Instant.now();

        String currentMemory = workspaceManager.readMemoryMd(rc);
        String dailyEntries = readDailyEntries(rc, watermark);

        if (dailyEntries.isBlank()) {
            log.debug("No fresh daily entries since {} — skipping consolidation", watermark);
            return Mono.empty();
        }

        int maxChars = maxMemoryTokens * 4;
        String systemPrompt = String.format(consolidationPrompt, maxMemoryTokens, maxChars);

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

        // The merge call is an auxiliary request: it goes through the budget-only boundary
        // (no parent history, tools, or task projection, no recursive compaction) and the
        // existing session model-record entry — the same one the flush and compaction
        // summary use.
        return ContextModelCalls.auxiliary(model, messages)
                .reduce(
                        new ConsolidationOutput(),
                        (acc, chatResponse) -> {
                            acc.accept(chatResponse);
                            return acc;
                        })
                .flatMap(
                        acc -> {
                            String consolidated = acc.text().strip();
                            if (FinishReasons.indicatesOutputLimit(
                                    acc.finishReason(), acc.metadata())) {
                                log.warn(
                                        "Consolidation output truncated (finish reason {}) —"
                                                + " keeping existing MEMORY.md and watermark",
                                        acc.finishReason());
                                return Mono.empty();
                            }
                            if (acc.sawToolBlock()) {
                                log.warn(
                                        "Consolidation output contained tool-call blocks —"
                                                + " keeping existing MEMORY.md and watermark");
                                return Mono.empty();
                            }
                            if (consolidated.isBlank()) {
                                log.warn("Consolidation produced empty output, skipping");
                                return Mono.empty();
                            }
                            if (consolidated.length() > maxChars) {
                                log.warn(
                                        "Consolidation output of {} chars exceeds the {}-char body"
                                                + " target — keeping existing MEMORY.md and"
                                                + " watermark",
                                        consolidated.length(),
                                        maxChars);
                                return Mono.empty();
                            }
                            writeConsolidatedMemory(rc, consolidated);
                            // Confirm the write before advancing: the memory-family writers
                            // swallow IO failures, so an unconfirmed write (read-back
                            // mismatch) must leave the watermark behind — the next run
                            // retries the same ledgers. No cross-store transaction: the
                            // memory write and the watermark remain two ordinary writes.
                            String readBack = workspaceManager.readMemoryMd(rc);
                            if (!consolidated.equals(readBack)) {
                                log.warn(
                                        "MEMORY.md write could not be confirmed — keeping"
                                                + " watermark so the next run retries");
                                return Mono.empty();
                            }
                            writeWatermark(rc, runStart);
                            log.info(
                                    "MEMORY.md consolidated ({} chars), watermark advanced to {}",
                                    consolidated.length(),
                                    runStart);
                            return Mono.empty();
                        });
    }

    /** Accumulates one consolidation stream: body text plus the terminal finish signal. */
    private static final class ConsolidationOutput {

        private final StringBuilder text = new StringBuilder();
        private String finishReason;
        private final Map<String, Object> metadata = new HashMap<>();
        private boolean sawToolBlock;

        void accept(ChatResponse chunk) {
            if (chunk.getFinishReason() != null) {
                finishReason = chunk.getFinishReason();
            }
            if (chunk.getMetadata() != null && !chunk.getMetadata().isEmpty()) {
                metadata.putAll(chunk.getMetadata());
            }
            if (chunk.getContent() != null) {
                for (var block : chunk.getContent()) {
                    if (block instanceof TextBlock tb && tb.getText() != null) {
                        text.append(tb.getText());
                    } else if (block instanceof ToolUseBlock || block instanceof ToolResultBlock) {
                        sawToolBlock = true;
                    }
                }
            }
        }

        String text() {
            return text.toString();
        }

        String finishReason() {
            return finishReason;
        }

        Map<String, Object> metadata() {
            return metadata;
        }

        boolean sawToolBlock() {
            return sawToolBlock;
        }
    }

    /**
     * Reads daily memory files modified strictly after the given watermark.
     * If watermark is {@link Instant#EPOCH}, all daily files are returned (first run).
     *
     * <p>All I/O is done through the {@link AbstractFilesystem} so this works equally well
     * with Local, Sandbox, and Store stores.
     */
    private String readDailyEntries(RuntimeContext rc, Instant watermark) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return "";
        }

        // Listing delegates to the WorkspaceManager's single-home memory listing: under a
        // sandbox-backed filesystem it enumerates the host-side authoritative copies (the
        // sandbox glob would throw once the lease is released; routed configurations are
        // resolved by the unified probe there, not a bare instanceof here).
        List<FileInfo> matches = workspaceManager.listMemoryFileInfos(rc);
        if (matches.isEmpty()) {
            return "";
        }

        List<FileInfo> eligible = new ArrayList<>();
        for (FileInfo fi : matches) {
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
            String content = workspaceManager.readMemoryFileUtf8(rc, rel);
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
     * {@link WorkspaceManager#readMemoryFileUtf8}.
     */
    private static String toRelative(String path) {
        if (path == null) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private void writeConsolidatedMemory(RuntimeContext rc, String content) {
        workspaceManager.writeMemoryFileUtf8(rc, "MEMORY.md", content);
    }

    static final String STATE_REL_PATH = "memory/" + STATE_FILE;

    /** Reads the last consolidation Instant, or {@link Instant#EPOCH} if none recorded. */
    Instant readWatermark(RuntimeContext rc) {
        BaseStore store = this.baseStore;
        if (store != null) {
            try {
                StoreItem item = store.get(WATERMARK_NAMESPACE, WATERMARK_KEY);
                if (item != null && item.value() != null) {
                    Object ts = item.value().get("ts");
                    if (ts instanceof Number n) {
                        return Instant.ofEpochMilli(n.longValue());
                    }
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to read consolidation watermark from store: {} — falling back to"
                                + " file",
                        e.getMessage());
            }
        }
        return readWatermarkFromFile(rc);
    }

    private Instant readWatermarkFromFile(RuntimeContext rc) {
        try {
            String value = workspaceManager.readMemoryFileUtf8(rc, STATE_REL_PATH);
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
        if (baseStore != null) {
            writeWatermarkToStore(ts);
        }
        try {
            workspaceManager.writeMemoryFileUtf8(rc, STATE_REL_PATH, ts.toString());
        } catch (Exception e) {
            log.warn(
                    "Failed to write consolidation watermark at {}: {}",
                    STATE_REL_PATH,
                    e.getMessage());
        }
    }

    private void writeWatermarkToStore(Instant ts) {
        Map<String, Object> value = new HashMap<>();
        value.put("ts", ts.toEpochMilli());
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            StoreItem item;
            try {
                item = baseStore.get(WATERMARK_NAMESPACE, WATERMARK_KEY);
            } catch (RuntimeException e) {
                log.warn("Failed to read consolidation watermark for CAS: {}", e.getMessage());
                return;
            }
            long expectedVersion = item != null ? item.version() : 0L;
            try {
                if (baseStore.putIfVersion(
                        WATERMARK_NAMESPACE, WATERMARK_KEY, value, expectedVersion)) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to write consolidation watermark to store: {}", e.getMessage());
                return;
            }
        }
        log.warn(
                "Failed to advance consolidation watermark in store after {} CAS retries",
                MAX_CAS_RETRIES);
    }
}
