/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.MessageUtils;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deduplicates a full-history client resend against the server-persisted {@link AgentState}
 * context by stripping the overlapping prefix at the call boundary.
 *
 * <p>Intended for the combination "client resends the full message history every turn × server
 * persists conversation context through an AgentState store" (AG-UI clients such as CopilotKit
 * are the documented first use case; the middleware itself is protocol-neutral). Registration is
 * the developer's decision — see the AG-UI integration docs for applicability and migration
 * notes:
 *
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *         // ...
 *         .middleware(new InputMessageDeduplicationMiddleware())
 *         .build();
 * }</pre>
 *
 * <p>Deduplication is driven by the last persisted context message (the anchor); see
 * {@link #extractDelta} for the matching and fallback rules. Do <b>not</b> register this
 * middleware when the frontend's full message list is the single source of truth and the server
 * keeps no conversation history — deduplication would wrongly strip that prefix.
 *
 * @author shanhongyu
 */
public class InputMessageDeduplicationMiddleware implements MiddlewareBase {

    /**
     * Runs before default-order business middlewares so they observe the deduplicated input.
     * Input-processing middlewares should use a lower {@code order()} to run after the
     * deduplication.
     */
    static final int ORDER = 1000;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InputMessageDeduplicationMiddleware.class);

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT_STATE_READY);
    }

    @Override
    public int order() {
        return ORDER;
    }

    /**
     * Replaces {@code inputMessages} in place with the delta against the persisted context;
     * no-op when either list is empty. The persisted state is never modified.
     *
     * @param agent the agent instance
     * @param ctx per-call runtime context
     * @param state this call's just-ready persisted state
     * @param inputMessages this call's private mutable input copy
     */
    @Override
    public void onAgentStateReady(
            Agent agent, RuntimeContext ctx, AgentState state, List<Msg> inputMessages) {
        List<Msg> persistedContext = state.getContext();
        if (persistedContext.isEmpty() || inputMessages.isEmpty()) {
            return; // first turn / no persisted context: the full input is already correct
        }
        DeduplicationOutcome outcome = computeDelta(persistedContext, inputMessages);
        if (outcome.anchorHit()) {
            LOGGER.debug(
                    "input message dedup: anchor hit, stripped {} of {} incoming messages, {}"
                            + " effective messages remain (session {})",
                    inputMessages.size() - outcome.delta().size(),
                    inputMessages.size(),
                    outcome.delta().size(),
                    ctx.getSessionId());
        } else {
            LOGGER.debug(
                    "input message dedup: anchor miss, passing {} incoming messages through"
                            + " (session {})",
                    inputMessages.size(),
                    ctx.getSessionId());
        }
        // inputMessages is this call's private mutable copy; the caller's list is unaffected.
        inputMessages.clear();
        inputMessages.addAll(outcome.delta());
    }

    /**
     * Returns the effective input for this call: {@code incoming} with the prefix overlapping
     * the persisted context stripped. The anchor — the last persisted message — locates the
     * overlap, matched by id first and then by role + text + block signature; an unmatched
     * anchor passes the input through unchanged (fail-open). A strip to empty keeps the input
     * empty while tool calls are pending (resume path), otherwise re-answers the last persisted
     * user message with a fresh id (regenerate semantics).
     *
     * <p>Pure function: never modifies its arguments, always returns a new list.
     *
     * @param persistedContext the persisted conversation, read-only
     * @param incoming the caller's input for this call
     * @return a new list with the effective input messages
     */
    static List<Msg> extractDelta(List<Msg> persistedContext, List<Msg> incoming) {
        return computeDelta(persistedContext, incoming).delta();
    }

    /**
     * Same as {@link #extractDelta}, additionally reporting whether the anchor matched.
     *
     * @param persistedContext the persisted conversation, read-only
     * @param incoming the caller's input for this call
     * @return the deduplication outcome (delta plus anchor-hit flag)
     */
    private static DeduplicationOutcome computeDelta(
            List<Msg> persistedContext, List<Msg> incoming) {
        if (persistedContext == null
                || persistedContext.isEmpty()
                || incoming == null
                || incoming.isEmpty()) {
            return new DeduplicationOutcome(new ArrayList<>(orEmpty(incoming)), false);
        }
        Msg anchor = lastMessage(persistedContext);
        if (anchor == null) {
            return new DeduplicationOutcome(new ArrayList<>(incoming), false);
        }
        int anchorIndex = indexOfAnchor(incoming, anchor);
        if (anchorIndex < 0) {
            return new DeduplicationOutcome(new ArrayList<>(incoming), false);
        }
        List<Msg> delta = new ArrayList<>(incoming.subList(anchorIndex + 1, incoming.size()));
        if (delta.isEmpty()) {
            // Regenerate/continue: the anchor was the last incoming element. With pending tool
            // uses empty input is the correct resume path; otherwise the last persisted user
            // turn becomes the prompt again. The restored turn is rebuilt with a fresh id —
            // it is appended to the persisted context later, so re-adding the original
            // instance would duplicate its id there.
            if (MessageUtils.pendingToolUseIds(persistedContext).isEmpty()) {
                restoreLastUserPrompt(persistedContext, delta);
            }
        }
        return new DeduplicationOutcome(delta, true);
    }

    /**
     * Returns the index of the last incoming message that matches the anchor — by id first,
     * then by role + text + block signature (clients may omit or rewrite ids) — or {@code -1}
     * when nothing matches. Null entries are skipped.
     *
     * @param msgs the incoming messages to scan
     * @param anchor the anchor message (last persisted context entry)
     * @return the anchor position, or {@code -1} when nothing matches
     */
    static int indexOfAnchor(List<Msg> msgs, Msg anchor) {
        String anchorId = anchor.getId();
        if (anchorId != null) {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                Msg candidate = msgs.get(i);
                if (candidate != null && anchorId.equals(candidate.getId())) {
                    return i;
                }
            }
        }
        AnchorProfile profile = AnchorProfile.of(anchor);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg candidate = msgs.get(i);
            if (candidate != null && contentMatchesAnchor(candidate, profile)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Conservative content fallback: role, text and block signature (count + types) must all
     * agree, so a same-text message with different attachments counts as a new turn. An anchor
     * without text never matches, guarding against empty-text collisions.
     *
     * @param candidate the incoming message to test
     * @param anchor the precomputed anchor profile
     * @return whether the candidate can serve as the anchor
     */
    static boolean contentMatchesAnchor(Msg candidate, AnchorProfile anchor) {
        if (candidate.getRole() != anchor.role()) {
            return false;
        }
        if (anchor.text().isEmpty()) {
            return false;
        }
        return anchor.text().equals(candidate.getTextContent())
                && sameBlockSignature(candidate.getContent(), anchor.blockSignature());
    }

    /**
     * Compares block counts and per-position block types.
     *
     * @param candidate the candidate message's blocks
     * @param anchor the anchor's block-type signature
     * @return whether both signatures agree
     */
    static boolean sameBlockSignature(List<ContentBlock> candidate, List<Class<?>> anchor) {
        if (candidate.size() != anchor.size()) {
            return false;
        }
        for (int i = 0; i < candidate.size(); i++) {
            if (candidate.get(i).getClass() != anchor.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** Precomputed anchor view (role, text, block-type signature) for the fallback scan. */
    record AnchorProfile(MsgRole role, String text, List<Class<?>> blockSignature) {

        /**
         * Builds the profile of the given anchor message.
         *
         * @param anchor the anchor message
         * @return the anchor's role, text and block-type signature
         */
        static AnchorProfile of(Msg anchor) {
            List<Class<?>> signature = new ArrayList<>(anchor.getContent().size());
            for (ContentBlock block : anchor.getContent()) {
                signature.add(block.getClass());
            }
            return new AnchorProfile(anchor.getRole(), anchor.getTextContent(), signature);
        }
    }

    /** Deduplication outcome: the effective delta plus whether the anchor was found. */
    private record DeduplicationOutcome(List<Msg> delta, boolean anchorHit) {}

    /**
     * Appends a rebuild of the last persisted user message to {@code delta}, carrying over the
     * subtype, fields and timestamp and regenerating only the id so the appended prompt cannot
     * duplicate the persisted message's id.
     *
     * @param persistedContext the persisted conversation, read-only
     * @param delta the delta list to append the restored prompt to
     */
    private static void restoreLastUserPrompt(List<Msg> persistedContext, List<Msg> delta) {
        for (int i = persistedContext.size() - 1; i >= 0; i--) {
            Msg src = persistedContext.get(i);
            if (src != null && src.getRole() == MsgRole.USER) {
                delta.add(
                        Msg.builderForRole(MsgRole.USER)
                                .name(src.getName())
                                .content(new ArrayList<>(src.getContent()))
                                .metadata(src.getMetadata())
                                .timestamp(src.getTimestamp())
                                .usage(src.getUsage())
                                .build());
                return;
            }
        }
        LOGGER.debug(
                "input message dedup: empty delta with no pending tool use and no persisted user"
                        + " message; keeping the input empty");
    }

    /**
     * Returns the last non-null message of {@code messages}, or {@code null} when there is
     * none.
     *
     * @param messages the messages to scan
     * @return the last non-null message, or {@code null}
     */
    private static Msg lastMessage(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null) {
                return msg;
            }
        }
        return null;
    }

    /**
     * Returns {@code list}, or an empty list when it is {@code null}.
     *
     * @param list the list to guard
     * @return the list itself, or an empty list
     */
    private static List<Msg> orEmpty(List<Msg> list) {
        return list == null ? List.of() : list;
    }
}
