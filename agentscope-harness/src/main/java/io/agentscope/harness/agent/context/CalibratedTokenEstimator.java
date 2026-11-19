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
package io.agentscope.harness.agent.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Calibrates a delegate {@link ContextTokenEstimator} with the provider-reported input size of
 * the most recent request: {@code providerInputTokens + (delegate(candidate) −
 * delegate(atAnchor))}, clamped at zero. The candidate must use the same model instance and
 * start with the same conversation messages (IDs, roles, names and content); otherwise the
 * delegate estimate is returned unchanged.
 *
 * <p>The anchor is committed lazily at the next {@link HarnessContextBuilder} prepare entry:
 * the last history message carrying {@link Msg#getChatUsage()} holds the provider measurement
 * of the request that produced it. It is paired with the recorded base only when the
 * conversation messages before the usage message match the recorded prefix. Conversation
 * messages exclude SYSTEM and synthetic messages.
 *
 * <p>Thread-safe and shared: one instance serves every call of the assembled agent, across
 * concurrent sessions. Calibration state is a bounded set of per-conversation slots keyed by
 * the first conversation message id — derived from the estimated messages alone, no session
 * identity involved — so interleaved sessions each pair their own usage and never
 * cross-calibrate. Slot updates are atomic copy-on-write swaps and reads are lock-free;
 * conversations beyond the bound are dropped and simply degrade to delegate estimates.
 *
 * <p>In-memory only: a fresh agent starts un-anchored (delegate estimates, i.e. the previous
 * behavior) and re-anchors on the first provider usage.
 */
public final class CalibratedTokenEstimator implements ContextTokenEstimator {

    private static final String METHOD_SUFFIX = "+provider-anchor";

    /**
     * Conversations tracked before the least recently written one is dropped — a memory guard,
     * not a concurrency limit: an evicted conversation re-tracks on its next prepare and
     * re-anchors on the usage after that.
     */
    private static final int MAX_CONVERSATIONS = 16;

    private final ContextTokenEstimator delegate;

    /**
     * Bounded per-conversation slots, swapped atomically (copy-on-write) so anchor and pending
     * never tear and readers stay lock-free.
     */
    private final AtomicReference<Map<String, Slot>> slots = new AtomicReference<>(Map.of());

    /** A verified prepared request awaiting its provider usage. */
    private record PreparedBase(Model model, List<String> prefix, long rawEstimate) {}

    /** A committed provider measurement anchored at a conversation prefix. */
    private record Anchor(
            Model model, List<String> prefix, long providerInputTokens, long rawEstimate) {}

    /** One conversation's calibration state. */
    private record Slot(Anchor anchor, PreparedBase pending) {}

    public CalibratedTokenEstimator(ContextTokenEstimator delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public Estimate estimate(ModelCallInput input) {
        Estimate raw = delegate.estimate(input);
        List<Msg> messages = conversationMessages(input.messages());
        if (messages == null || messages.isEmpty()) {
            return raw;
        }
        Slot slot = slots.get().get(messages.get(0).getId());
        Anchor current = slot == null ? null : slot.anchor();
        if (current == null
                || input.model() != current.model()
                || !startsWith(messages, current.prefix())) {
            return raw;
        }
        long calibrated =
                Math.max(0, current.providerInputTokens + (raw.tokens() - current.rawEstimate));
        return new Estimate(calibrated, raw.method() + METHOD_SUFFIX, raw.exact());
    }

    /**
     * Commits the pending base when the last usage-bearing message in the incoming history pairs
     * with it: the conversation messages preceding that message must match the recorded prefix.
     * Called at prepare entry, before this prepare's estimates run. The commit lands only while
     * the verified base still holds the pending slot, so a concurrently recorded request keeps
     * its own pairing chance.
     */
    void calibrateFromHistory(List<Msg> incoming) {
        List<Msg> messages = new ArrayList<>();
        int messagesBeforeUsage = 0;
        ChatUsage usage = null;
        for (Msg msg : incoming) {
            if (!isConversationMessage(msg)) {
                continue;
            }
            ChatUsage msgUsage = msg.getChatUsage();
            if (msgUsage != null && msgUsage.getInputTokens() > 0) {
                messagesBeforeUsage = messages.size();
                usage = msgUsage;
            }
            if (msg.getId() == null) {
                return; // an unidentifiable prefix must never anchor
            }
            messages.add(msg);
        }
        if (usage == null || messagesBeforeUsage == 0) {
            return;
        }
        String key = messages.get(0).getId();
        Slot slot = slots.get().get(key);
        PreparedBase base = slot == null ? null : slot.pending();
        if (base == null
                || messagesBeforeUsage != base.prefix().size()
                || !startsWith(messages, base.prefix())) {
            return;
        }
        Anchor committed =
                new Anchor(base.model(), base.prefix(), usage.getInputTokens(), base.rawEstimate());
        // Commit only while the verified base still holds its slot: a concurrently recorded
        // request for the same conversation keeps its own pairing chance.
        slots.updateAndGet(
                m -> {
                    Slot s = m.get(key);
                    return s != null && s.pending() == base
                            ? withSlot(m, key, new Slot(committed, null))
                            : m;
                });
    }

    /**
     * Records the verified prepare whose provider usage is expected next. The base estimate is
     * always taken in the delegate's frame, never a previously calibrated value, so later
     * increments stay frame-consistent.
     */
    void recordPreparedRequest(ModelCallInput prepared) {
        List<Msg> messages = conversationMessages(prepared.messages());
        if (messages == null || messages.isEmpty()) {
            return;
        }
        PreparedBase next =
                new PreparedBase(
                        prepared.model(),
                        messageHashes(messages),
                        delegate.estimate(prepared).tokens());
        String key = messages.get(0).getId();
        slots.updateAndGet(
                m -> {
                    Slot previous = m.get(key);
                    return withSlot(
                            m, key, new Slot(previous == null ? null : previous.anchor(), next));
                });
    }

    /** Copy-on-write slot update; when the set overflows, the oldest tracked conversation goes. */
    private static Map<String, Slot> withSlot(Map<String, Slot> current, String key, Slot slot) {
        Map<String, Slot> next = new LinkedHashMap<>(current);
        next.remove(key); // refresh recency when the conversation is already tracked
        next.put(key, slot);
        if (next.size() > MAX_CONVERSATIONS) {
            Iterator<String> oldest = next.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return Collections.unmodifiableMap(next);
    }

    private static boolean isConversationMessage(Msg msg) {
        return msg.getRole() != MsgRole.SYSTEM
                && !(msg.getMetadata() != null
                        && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC)));
    }

    /**
     * Ordered conversation messages, or {@code null} when any id is missing (an
     * unidentifiable prefix must never anchor).
     */
    private static List<Msg> conversationMessages(List<Msg> messages) {
        List<Msg> conversation = new ArrayList<>();
        for (Msg msg : messages) {
            if (!isConversationMessage(msg)) {
                continue;
            }
            if (msg.getId() == null) {
                return null;
            }
            conversation.add(msg);
        }
        return conversation;
    }

    private static boolean startsWith(List<Msg> candidate, List<String> prefix) {
        int length = prefix.size();
        return candidate.size() >= length
                && messageHashes(candidate.subList(0, length)).equals(prefix);
    }

    private static List<String> messageHashes(List<Msg> messages) {
        return messages.stream().map(CalibratedTokenEstimator::messageHash).toList();
    }

    private static String messageHash(Msg msg) {
        Object[] fields = {msg.getId(), msg.getRole(), msg.getName(), msg.getContent()};
        return HarnessContextBuilder.hash(JsonUtils.getJsonCodec().toJson(fields));
    }
}
