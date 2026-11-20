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
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.formatter.DocumentReferenceRenderer;
import io.agentscope.core.journal.JournalException;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.FinishReasons;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.context.ContextModelCalls;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Extracts facts into the daily memory ledger. {@code MEMORY.md} is read-only reference context;
 * {@link MemoryConsolidator} maintains it separately.
 */
public class MemoryFlushManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushManager.class);

    /**
     * Default prompt for the memory extraction step. Exposed publicly so callers can extend
     * (e.g. append project-specific guidelines) when constructing
     * {@link io.agentscope.harness.agent.memory.MemoryConfig}.
     */
    public static final String DEFAULT_FLUSH_PROMPT =
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
    private final String flushPrompt;
    private final MemoryAdmission admission;
    private final Duration flushTimeout;
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*+] |\\d+[.)] )\\S");

    public MemoryFlushManager(WorkspaceManager workspaceManager, Model model) {
        this(workspaceManager, model, DEFAULT_FLUSH_PROMPT);
    }

    /**
     * @param flushPrompt SYSTEM prompt for the extraction LLM call. {@code null} falls back to
     *     {@link #DEFAULT_FLUSH_PROMPT}.
     */
    public MemoryFlushManager(WorkspaceManager workspaceManager, Model model, String flushPrompt) {
        this(workspaceManager, model, flushPrompt, null);
    }

    public MemoryFlushManager(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryAdmission admission) {
        this(workspaceManager, model, flushPrompt, admission, MemoryConfig.DEFAULT_FLUSH_TIMEOUT);
    }

    public MemoryFlushManager(
            WorkspaceManager workspaceManager,
            Model model,
            String flushPrompt,
            MemoryAdmission admission,
            Duration flushTimeout) {
        if (flushTimeout == null || flushTimeout.isZero() || flushTimeout.isNegative())
            throw new IllegalArgumentException("flushTimeout must be positive");
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.flushPrompt = flushPrompt != null ? flushPrompt : DEFAULT_FLUSH_PROMPT;
        this.admission = admission;
        this.flushTimeout = flushTimeout;
    }

    /**
     * Extracts long-term memories from messages using the model and writes them to disk.
     *
     * <p>Provides existing MEMORY.md and today's daily file content to the extraction LLM
     * so it can effectively deduplicate and avoid re-extracting known facts.
     */
    public Mono<Void> flushMemories(RuntimeContext rc, List<Msg> messages) {
        String conversationText = serializeMessages(messages);
        if (conversationText.isBlank()) {
            return Mono.empty();
        }

        String existingMemory = readExistingContent(rc, WorkspaceConstants.MEMORY_MD);
        String today = LocalDate.now(clock(rc)).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dailyRelPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        String existingDaily = readExistingContent(rc, dailyRelPath);

        return extract(conversationText, existingMemory, existingDaily, null)
                .flatMap(
                        content ->
                                admit(
                                        rc,
                                        content,
                                        conversationText,
                                        existingMemory,
                                        existingDaily,
                                        this::assess))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(
                        extracted -> {
                            if (!extracted.isBlank() && !extracted.strip().equals("NO_REPLY"))
                                writeMemoryFiles(rc, extracted);
                        })
                .then()
                .timeout(flushTimeout);
    }

    /** Consumes a bounded committed journal page; progress is independent of working context. */
    public Mono<Void> flushJournal(RuntimeContext rc, ThreadKey key, ThreadJournal journal) {
        return flushJournal(rc, key, journal, true);
    }

    /** Persists the inline trigger decision with the selected prefix for finalization replay. */
    public Mono<Void> flushJournal(
            RuntimeContext rc, ThreadKey key, ThreadJournal journal, boolean enabled) {
        return new JournalMemoryFlush(workspaceManager, this, model, flushTimeout)
                .flush(rc, key, journal, enabled);
    }

    Mono<String> extract(
            String conversationText,
            String existingMemory,
            String existingDaily,
            GenerateOptions options) {
        return ContextModelCalls.auxiliary(
                        model, input(conversationText, existingMemory, existingDaily), options)
                .reduceWith(
                        StringBuilder::new,
                        (sb, response) -> {
                            if (FinishReasons.indicatesOutputLimit(
                                            response.getFinishReason(), response.getMetadata())
                                    || "content_filter".equals(response.getFinishReason()))
                                throw new IllegalStateException(
                                        "Incomplete memory extraction response");
                            if (response.getContent() != null)
                                for (ContentBlock block : response.getContent())
                                    if (block instanceof TextBlock text) {
                                        if (text.getText() != null) sb.append(text.getText());
                                    } else if (!(block instanceof ThinkingBlock)) {
                                        throw new IllegalStateException(
                                                "Non-text memory extraction response");
                                    }
                            return sb;
                        })
                .map(StringBuilder::toString);
    }

    Mono<String> admit(
            RuntimeContext rc,
            String content,
            String source,
            String known,
            String recent,
            Function<MemoryAdmission.Input, Mono<MemoryAdmission.Decision>> judge) {
        if (content.isBlank() || content.strip().equals("NO_REPLY")) {
            log.info("Memory extraction outcome=no_reply (session={})", rc.getSessionId());
            return Mono.just("");
        }
        if (admission == null) return Mono.just(content);
        return Mono.defer(
                () -> {
                    List<String> candidates = candidates(content);
                    List<String> existing =
                            new ArrayList<>(
                                    List.of(known, recent).stream()
                                            .filter(text -> !text.isBlank())
                                            .toList());
                    List<String> accepted = new ArrayList<>();
                    String owner =
                            rc.getUserId() == null || rc.getUserId().isBlank()
                                    ? "anonymous:"
                                            + Objects.toString(rc.getSessionId(), "workspace")
                                    : rc.getUserId();
                    return Flux.fromIterable(candidates)
                            .concatMap(
                                    candidate ->
                                            Mono.defer(
                                                            () ->
                                                                    judge.apply(
                                                                            new MemoryAdmission
                                                                                    .Input(
                                                                                    owner,
                                                                                    candidate,
                                                                                    source,
                                                                                    existing)))
                                                    .switchIfEmpty(
                                                            Mono.error(
                                                                    new IllegalStateException(
                                                                            "Empty memory admission"
                                                                                    + " decision")))
                                                    .doOnNext(
                                                            decision -> {
                                                                if (decision.accepted()) {
                                                                    accepted.add(candidate);
                                                                    existing.add(candidate);
                                                                }
                                                            }))
                            .then(
                                    Mono.fromSupplier(
                                            () -> {
                                                log.info(
                                                        "Memory admission: accepted={}, rejected={}"
                                                                + " (session={})",
                                                        accepted.size(),
                                                        candidates.size() - accepted.size(),
                                                        rc.getSessionId());
                                                return String.join("\n", accepted);
                                            }));
                });
    }

    Mono<MemoryAdmission.Decision> assess(MemoryAdmission.Input input) {
        return admission.assess(input);
    }

    private static List<String> candidates(String content) {
        List<String> result = new ArrayList<>();
        boolean continuation = false;
        for (String line : content.lines().toList()) {
            if (BULLET.matcher(line).find()) {
                result.add(line.strip());
                continuation = true;
            } else if (continuation && !line.isBlank() && Character.isWhitespace(line.charAt(0))) {
                int last = result.size() - 1;
                result.set(last, result.get(last) + "\n" + line);
            } else if (!line.isBlank()) continuation = false;
        }
        return result.isEmpty() ? List.of("- " + content.strip()) : result;
    }

    List<Msg> input(String conversationText, String existingMemory, String existingDaily) {
        String userPrompt =
                "MEMORY.md (read-only; do not repeat):\n"
                        + existingMemory
                        + "\nToday's ledger (do not repeat):\n"
                        + existingDaily
                        + "\nExtract NEW memories from these original messages:\n"
                        + conversationText;
        return List.of(
                Msg.builder()
                        .id("memory:system")
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .textContent(flushPrompt)
                        .build(),
                Msg.builder()
                        .id("memory:input")
                        .role(MsgRole.USER)
                        .textContent(userPrompt)
                        .build());
    }

    /** Appends the extracted entries to today's daily memory ledger. */
    private void writeMemoryFiles(RuntimeContext rc, String content) {
        String today = LocalDate.now(clock(rc)).format(DateTimeFormatter.ISO_LOCAL_DATE);

        String dailyEntry =
                String.format(
                        "\n## Memory Flush — %s\n%s\n", Instant.now(clock(rc)).toString(), content);

        String dailyRelPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        workspaceManager.appendMemoryFileUtf8(rc, dailyRelPath, dailyEntry);
    }

    private static Clock clock(RuntimeContext context) {
        Clock supplied = context == null ? null : context.get(Clock.class);
        return supplied == null ? Clock.systemDefaultZone() : supplied;
    }

    private String readExistingContent(RuntimeContext rc, String relativePath) {
        try {
            String content = workspaceManager.readMemoryFileUtf8(rc, relativePath);
            return content != null ? content : "";
        } catch (Exception e) {
            if (JournalException.causedBy(e))
                throw new JournalException(
                        "Memory read cannot advance without its recorded outcome", e);
            log.debug("Could not read {}: {}", relativePath, e.getMessage());
            return "";
        }
    }

    private static final String SESSION_CONTEXT_TAG = "<session_context>";

    /**
     * Serializes all messages into a textual representation for the memory extraction model.
     * Includes USER, ASSISTANT, and TOOL messages. Assistant tool-call blocks and tool-result
     * blocks are rendered as concise text so the model can extract memories from tool interactions.
     * Internal context messages are skipped because they are generated from existing context,
     * not new user/assistant/tool facts.
     */
    String serializeMessages(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getRole() != null && m.getRole() != MsgRole.SYSTEM)
                .filter(m -> !isInternalContextMessage(m))
                .map(this::renderMessage)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static boolean isInternalContextMessage(Msg msg) {
        return isSessionContextMessage(msg) || isCompactionSummaryMessage(msg);
    }

    private static boolean isCompactionSummaryMessage(Msg msg) {
        return msg != null && ConversationCompactor.SUMMARY_MSG_NAME.equals(msg.getName());
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
            } else if (block instanceof DocumentBlock doc) {
                parts.add(DocumentReferenceRenderer.render(doc));
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
                } else if (out instanceof DocumentBlock doc) {
                    if (sb.length() > 0 && !Character.isWhitespace(sb.charAt(sb.length() - 1))) {
                        sb.append(' ');
                    }
                    sb.append(DocumentReferenceRenderer.render(doc));
                }
            }
        }
        return sb.toString();
    }
}
