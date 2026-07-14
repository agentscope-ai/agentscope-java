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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.SuggestionResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Emits a single {@link SuggestionResultEvent} between {@link AgentResultEvent} and
 * {@link AgentEndEvent}, carrying up to {@link #maxItems} follow-up suggestions produced by the
 * configured LLM.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li><b>Observe upstream</b> — collect the final {@link Msg} from {@link AgentResultEvent},
 *       buffer the terminal {@link AgentEndEvent} (filtered out and re-emitted at the tail so
 *       consumers always see it last), and watch for turn-abort signals
 *       ({@link ExceedMaxItersEvent}, {@link RequestStopEvent}).</li>
 *   <li><b>Cheap skip gates</b> — before touching the model, short-circuit when:
 *       <ul>
 *         <li>upstream errored (no {@link AgentEndEvent}) — return empty, propagate parent flow;</li>
 *         <li>an abort signal fired — re-emit {@link AgentEndEvent} only;</li>
 *         <li>the final assistant text is shorter than {@link #MIN_ANCHOR_CHARS} — no useful
 *             follow-up can be derived from a near-empty reply.</li>
 *       </ul></li>
 *   <li><b>Bounded prompt</b> — the final reply is truncated to
 *       {@link #MAX_REPLY_ANCHOR_CHARS}; the last user turn is extracted from
 *       {@link AgentInput#msgs()} and truncated to {@link #MAX_USER_INTENT_CHARS}. Both anchors
 *       are fed to the model so suggestions align with the <em>user's</em> intent, not just the
 *       assistant's phrasing.</li>
 *   <li><b>Aggregate → parse → emit</b> — {@link Model#stream} responses are reduced into one
 *       string, parsed as a strict JSON array (with a lenient line-based fallback), and surfaced
 *       as one {@link SuggestionResultEvent}. Empty parses suppress the event entirely.</li>
 *   <li><b>Trailer</b> — the original {@link AgentEndEvent} is re-emitted so the "AgentEndEvent
 *       is always last" contract of {@code call()} / {@code streamEvents()} holds.</li>
 * </ol>
 *
 * <p>Failure handling is intentionally lenient: any exception in the suggestion path is swallowed
 * and the original {@link AgentEndEvent} passes through unchanged. This is a strictly additive
 * feature — the main conversation result is never mutated.
 */
public class SuggestionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SuggestionMiddleware.class);

    // ---- Cost / precision knobs (all package-private for test override consideration) ----

    /** Skip LLM entirely when the assistant reply is shorter than this many characters. */
    static final int MIN_ANCHOR_CHARS = 20;

    /** Hard cap for the AGENT REPLY anchor injected into the prompt. */
    static final int MAX_REPLY_ANCHOR_CHARS = 3000;

    /** Hard cap for the USER INTENT anchor injected into the prompt. */
    static final int MAX_USER_INTENT_CHARS = 500;

    private static final int DEFAULT_MAX_ITEMS = 4;
    private static final String TRUNCATE_MARKER = "... [truncated]";
    private static final String NO_USER_INTENT = "(none)";

    /**
     * Hard cap on the LLM call for suggestion generation. If the model hangs we abort and let
     * {@link AgentEndEvent} pass through unchanged — a stuck side channel must never block the
     * main conversation from terminating.
     */
    private static final Duration SUGGESTION_TIMEOUT = Duration.ofSeconds(15);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    /**
     * Prompt template with two anchors and a strict output contract.
     * Placeholders (positional):
     * <ol>
     *   <li>{@code %1$s} — USER INTENT (last user turn, truncated)</li>
     *   <li>{@code %2$s} — AGENT REPLY (final assistant text, truncated)</li>
     *   <li>{@code %3$d} — max number of suggestions requested</li>
     * </ol>
     */
    private static final String PROMPT_TEMPLATE =
            """
            You are proposing follow-up questions the user might ask next in this conversation.
            Two anchors define the topic — a suggestion that ignores either anchor is unacceptable.

            USER INTENT (what the user just asked — align suggestions with this direction):
            ---
            %1$s
            ---

            AGENT REPLY (what the agent answered — main topic anchor, stay on this subject):
            ---
            %2$s
            ---

            QUALITY LADDER (try each tier in order; only fall back when the previous tier truly
            yields nothing):
              TIER 1 (preferred): Sharp, specific follow-ups that dig deeper into the exact
                subject of the reply — concrete next steps, clarifications on details actually
                present in the reply, natural extensions of the user's intent above.
              TIER 2 (acceptable fallback): Broader but still on-topic questions that stay
                within the same subject area, even if less pointed. Use when TIER 1 candidates
                feel forced or repetitive.
              TIER 3 (last resort): Return an empty array [] ONLY if you cannot produce even
                one suggestion that stays on topic without drifting.

            HARD CONSTRAINTS (apply at every tier):
            1. Never drift off topic. No unrelated, generic ("tell me more"), meta ("how do
               you work?"), or off-domain questions.
            2. Each suggestion MUST be a single natural-language sentence phrased as if the
               user were typing it in first person to the agent.
            3. Propose AT MOST %3$d suggestions. Fewer is fine — quality beats quantity, and
               a shorter on-topic list is better than a padded one.

            OUTPUT FORMAT (STRICT):
            Return ONLY a JSON array of strings. No prose before or after. No code fences.
            No numbering. No bullets. No keys. No trailing commas.

            Example shape (content must derive from the anchors above):
            ["First on-topic follow-up?", "Second on-topic follow-up?"]
            """;

    private final Model model;
    private final int maxItems;

    /**
     * @param model the LLM used to generate suggestions (must be non-null and streaming-capable)
     */
    public SuggestionMiddleware(Model model) {
        this(model, DEFAULT_MAX_ITEMS);
    }

    /**
     * @param model the LLM used to generate suggestions
     * @param maxItems soft cap on emitted suggestions; the middleware will truncate longer lists
     *     to this size. Values {@code <= 0} fall back to {@link #DEFAULT_MAX_ITEMS}
     */
    public SuggestionMiddleware(Model model, int maxItems) {
        if (model == null) {
            throw new IllegalArgumentException("SuggestionMiddleware requires a non-null model");
        }
        this.model = model;
        this.maxItems = maxItems > 0 ? maxItems : DEFAULT_MAX_ITEMS;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        // Per-invocation state captured by the observer wired into the upstream flow. Using
        // atomic refs keeps the observer thread-safe with the reactive scheduler; the state is
        // written under the upstream subscriber's serialized signal delivery.
        AtomicReference<Msg> finalMsgRef = new AtomicReference<>();
        AtomicReference<AgentEndEvent> endRef = new AtomicReference<>();
        AtomicBoolean skipRef = new AtomicBoolean(false);

        // We only observe upstream — we do NOT swallow errors here. Any exception propagates to
        // the caller unchanged; the deferred trailer below is never subscribed on an errored
        // upstream, so suggestion generation is naturally skipped in that case.
        Flux<AgentEvent> upstream =
                next.apply(input)
                        .doOnNext(
                                ev -> {
                                    if (ev instanceof AgentResultEvent res) {
                                        finalMsgRef.set(res.getResult());
                                    } else if (ev instanceof AgentEndEvent end) {
                                        endRef.set(end);
                                    } else if (ev instanceof ExceedMaxItersEvent
                                            || ev instanceof RequestStopEvent) {
                                        // The turn ended abnormally (max-iters guard tripped or a
                                        // stop was requested). Follow-ups on a truncated / aborted
                                        // reply are usually low-quality and often misleading —
                                        // skip.
                                        skipRef.set(true);
                                    }
                                })
                        .filter(ev -> !(ev instanceof AgentEndEvent));

        return upstream.concatWith(
                Flux.defer(
                        () -> emitTrailer(input, finalMsgRef.get(), endRef.get(), skipRef.get())));
    }

    // ---- Trailer decision + LLM generation ------------------------------------------------

    private Flux<AgentEvent> emitTrailer(
            AgentInput input, Msg finalMsg, AgentEndEvent end, boolean skip) {
        // No AgentEndEvent means upstream errored before completing; nothing to append.
        if (end == null) {
            return Flux.empty();
        }
        if (skip) {
            return Flux.<AgentEvent>just(end);
        }
        String replyText = extractText(finalMsg);
        if (replyText.length() < MIN_ANCHOR_CHARS) {
            // Reply too short (or blank) to base a useful follow-up on. Cheap early-out — no
            // LLM call, no wasted tokens.
            return Flux.<AgentEvent>just(end);
        }

        String userIntent = lastUserText(input);
        String replyAnchor = truncate(replyText, MAX_REPLY_ANCHOR_CHARS);
        return buildSuggestionMono(end.getReplyId(), userIntent, replyAnchor)
                .timeout(SUGGESTION_TIMEOUT)
                .onErrorResume(
                        e -> {
                            // Log at WARN so operators actually see failures in production (default
                            // log level is INFO). This is a strictly additive side channel — the
                            // exception is swallowed and the original AgentEndEvent still passes
                            // through, but a silent DEBUG-only trace hides real regressions
                            // (model timeouts, credential errors, parser regressions).
                            log.warn("SuggestionMiddleware suppressed error", e);
                            return Mono.empty();
                        })
                .flux()
                .concatWith(Flux.just(end));
    }

    /**
     * Aggregates the model completion end-to-end and emits at most one {@link SuggestionResultEvent}. Empty parses (blank output or no valid JSON items and no line-based
     * fallback lines) suppress the event entirely.
     */
    private Mono<AgentEvent> buildSuggestionMono(
            String replyId, String userIntent, String replyAnchor) {
        String prompt = String.format(PROMPT_TEMPLATE, userIntent, replyAnchor, maxItems);
        List<Msg> input =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(prompt).build())
                                .build());

        return model.stream(input, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, resp) -> {
                            List<ContentBlock> content = resp.getContent();
                            if (content != null) {
                                for (ContentBlock block : content) {
                                    if (block instanceof TextBlock tb) {
                                        sb.append(tb.getText());
                                    }
                                }
                            }
                            return sb;
                        })
                .map(StringBuilder::toString)
                .flatMap(
                        raw -> {
                            List<String> parsed = parseSuggestions(raw, maxItems);
                            if (parsed.isEmpty()) {
                                return Mono.empty();
                            }
                            return Mono.just(
                                    (AgentEvent) new SuggestionResultEvent(replyId, parsed));
                        });
    }

    // ---- Anchor extraction ----------------------------------------------------------------

    /**
     * Extract plain-text content from the final assistant {@link Msg}. Returns an empty string when
     * the message is null, has no text content, or all text blocks are blank.
     */
    private static String extractText(Msg msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return text != null ? text.strip() : "";
    }

    /**
     * Find the most recent USER message in the current turn's input and return its trimmed,
     * truncated text. Returns {@link #NO_USER_INTENT} if no usable user text is present so the
     * prompt template still substitutes cleanly.
     */
    private static String lastUserText(AgentInput input) {
        if (input == null || input.msgs() == null || input.msgs().isEmpty()) {
            return NO_USER_INTENT;
        }
        List<Msg> msgs = input.msgs();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m == null || m.getRole() != MsgRole.USER) {
                continue;
            }
            String text = m.getTextContent();
            if (text == null) {
                continue;
            }
            String trimmed = text.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            return truncate(trimmed, MAX_USER_INTENT_CHARS);
        }
        return NO_USER_INTENT;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + TRUNCATE_MARKER;
    }

    // ---- Parsing --------------------------------------------------------------------------

    /**
     * Parse a raw model response into a bounded list of suggestion strings.
     *
     * <p>Preferred wire format is a strict JSON array of strings. If the entire trimmed payload
     * parses as {@code List<String>} via Jackson, that list wins. Otherwise the method falls back
     * to a line-based scan that strips common list markers (bullets, digits, dashes) and blank
     * lines. In both paths blank items are dropped and the result is capped at {@code cap} items.
     * Never returns {@code null}.
     */
    static List<String> parseSuggestions(String raw, int cap) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = raw.strip();

        // Preferred: strict JSON array. Some models still wrap the payload in markdown fences,
        // so we try both the raw trimmed body and a fence-stripped variant.
        List<String> jsonParsed = tryParseJsonArray(trimmed);
        if (jsonParsed == null) {
            String unfenced = stripCodeFence(trimmed);
            if (!unfenced.equals(trimmed)) {
                jsonParsed = tryParseJsonArray(unfenced);
            }
        }
        if (jsonParsed != null) {
            return capNonBlank(jsonParsed, cap);
        }

        // Fallback: line-based parsing for models that ignore the JSON contract.
        String[] lines = trimmed.split("\\R");
        List<String> out = new ArrayList<>(Math.min(lines.length, cap));
        for (String line : lines) {
            String cleaned = stripListMarker(line).trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            out.add(cleaned);
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    private static List<String> tryParseJsonArray(String s) {
        if (s.isEmpty() || s.charAt(0) != '[') {
            return null;
        }
        try {
            return JSON.readValue(s, STRING_LIST_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripCodeFence(String s) {
        String body = s;
        if (body.startsWith("```")) {
            int firstNewline = body.indexOf('\n');
            if (firstNewline > 0) {
                body = body.substring(firstNewline + 1);
            }
        }
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3);
        }
        return body.strip();
    }

    private static List<String> capNonBlank(List<String> in, int cap) {
        List<String> out = new ArrayList<>(Math.min(in.size(), cap));
        for (String s : in) {
            if (s == null) {
                continue;
            }
            String v = s.strip();
            if (v.isEmpty()) {
                continue;
            }
            out.add(v);
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    private static String stripListMarker(String line) {
        if (line == null) {
            return "";
        }
        String s = line.strip();
        // Drop leading bullets: "- ", "* ", "• ".
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '*' || s.charAt(i) == '•')) {
            i++;
        }
        if (i > 0) {
            return s.substring(i).stripLeading();
        }
        // Numbered forms: 1. / 1) / 1 - / 1 followed by space.
        int digitEnd = 0;
        while (digitEnd < s.length() && Character.isDigit(s.charAt(digitEnd))) {
            digitEnd++;
        }
        if (digitEnd > 0 && digitEnd < s.length()) {
            char next = s.charAt(digitEnd);
            if (next == '.' || next == ')' || next == '-' || next == ' ') {
                String rest = s.substring(digitEnd + 1).stripLeading();
                if (next == ' ') {
                    return stripListMarker(rest);
                }
                return rest;
            }
        }
        return s;
    }
}
