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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
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
 * the last successful consolidation. Daily files whose mtime is at or before that
 * timestamp are skipped — reducing token usage and protecting MEMORY.md from being
 * re-rewritten with stale content.
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
     */
    public Mono<Void> consolidate() {
        Instant watermark = readWatermark();
        Instant runStart = Instant.now();

        String currentMemory = workspaceManager.readMemoryMd();
        String dailyEntries = readDailyEntries(watermark);

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
                            writeConsolidatedMemory(consolidated);
                            writeWatermark(runStart);
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
     */
    private String readDailyEntries(Instant watermark) {
        Path memoryDir = workspaceManager.getMemoryDir();
        if (!Files.isDirectory(memoryDir)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("archive"))
                    .filter(p -> isModifiedAfter(p, watermark))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(
                            p -> {
                                try {
                                    String content = Files.readString(p);
                                    if (!content.isBlank()) {
                                        sb.append("### ").append(p.getFileName()).append("\n");
                                        sb.append(content.strip()).append("\n\n");
                                    }
                                } catch (IOException e) {
                                    log.warn("Failed to read {}: {}", p, e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to list memory dir: {}", e.getMessage());
        }
        return sb.toString();
    }

    private static boolean isModifiedAfter(Path p, Instant watermark) {
        try {
            return Files.getLastModifiedTime(p).toInstant().isAfter(watermark);
        } catch (IOException e) {
            return true; // be safe — include on read error
        }
    }

    private void writeConsolidatedMemory(String content) {
        Path memoryFile = workspaceManager.getWorkspace().resolve(WorkspaceConstants.MEMORY_MD);
        try {
            if (memoryFile.getParent() != null) {
                Files.createDirectories(memoryFile.getParent());
            }
            Files.writeString(memoryFile, content);
        } catch (IOException e) {
            log.warn("Failed to write consolidated MEMORY.md: {}", e.getMessage());
        }
    }

    private Path stateFilePath() {
        return workspaceManager.getMemoryDir().resolve(STATE_FILE);
    }

    /** Reads the last consolidation Instant, or {@link Instant#EPOCH} if none recorded. */
    Instant readWatermark() {
        Path state = stateFilePath();
        if (!Files.isRegularFile(state)) {
            return Instant.EPOCH;
        }
        try {
            String value = Files.readString(state).strip();
            if (value.isEmpty()) {
                return Instant.EPOCH;
            }
            return Instant.parse(value);
        } catch (Exception e) {
            log.warn(
                    "Failed to read consolidation watermark at {}: {} — treating as EPOCH",
                    state,
                    e.getMessage());
            return Instant.EPOCH;
        }
    }

    private void writeWatermark(Instant ts) {
        Path state = stateFilePath();
        try {
            if (state.getParent() != null) {
                Files.createDirectories(state.getParent());
            }
            Files.writeString(state, ts.toString());
        } catch (IOException e) {
            log.warn("Failed to write consolidation watermark at {}: {}", state, e.getMessage());
        }
    }
}
