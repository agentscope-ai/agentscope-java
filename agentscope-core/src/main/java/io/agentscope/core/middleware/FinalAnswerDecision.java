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
package io.agentscope.core.middleware;

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Objects;

/**
 * Decision returned from {@link MiddlewareBase#onFinalAnswer}. Immutable value
 * type, constructed through the static factories:
 *
 * <ul>
 *   <li>{@link #accept()} — the candidate counts; output it unchanged and finish
 *   <li>{@link #accept(Msg)} — output the given message instead; the replacement is fed
 *       forward to later gates and, if the round is accepted, memorized and delivered as
 *       the chain's output
 *   <li>{@link #retry(List)} / {@link #retry(Msg)} — the candidate does not count; it
 *       enters the context together with the injected messages and reasoning re-runs
 * </ul>
 *
 * <p>The decision vocabulary may grow through new factory methods in future releases; do
 * not write exhaustive switch expressions over {@link Kind} without a {@code default}
 * branch.
 *
 * @author shanhongyu
 */
public final class FinalAnswerDecision {

    /** Decision kind. */
    public enum Kind {
        /** The candidate counts and is output, possibly replaced. */
        ACCEPT,

        /** The candidate is rejected; reasoning re-runs with the injected messages. */
        RETRY
    }

    /** Shared plain-accept instance. */
    private static final FinalAnswerDecision ACCEPT =
            new FinalAnswerDecision(Kind.ACCEPT, null, List.of());

    /** The decision kind. */
    private final Kind kind;

    /** Replacement message on an accept-with-replacement; {@code null} otherwise. */
    private final Msg replacement;

    /** Messages injected on a retry; empty otherwise. */
    private final List<Msg> retryMessages;

    /** Creates a decision; use the static factories. */
    private FinalAnswerDecision(Kind kind, Msg replacement, List<Msg> retryMessages) {
        this.kind = kind;
        this.replacement = replacement;
        this.retryMessages = retryMessages;
    }

    /**
     * The candidate counts and is output unchanged.
     *
     * @return a plain accept with no replacement
     */
    public static FinalAnswerDecision accept() {
        return ACCEPT;
    }

    /**
     * The candidate counts but {@code replacement} is output (and memorized) instead.
     *
     * @param replacement the message to output and memorize instead of the candidate
     * @return an accept carrying the replacement
     */
    public static FinalAnswerDecision accept(Msg replacement) {
        return new FinalAnswerDecision(Kind.ACCEPT, Objects.requireNonNull(replacement), List.of());
    }

    /**
     * The candidate is rejected; it and {@code msgs} enter the context, then reasoning re-runs.
     * An empty list is honored but leaves the model with no corrective signal — prefer
     * injecting at least one message explaining what to fix.
     *
     * @param msgs the messages injected after the rejected candidate
     * @return a retry carrying the injected messages
     */
    public static FinalAnswerDecision retry(List<Msg> msgs) {
        return new FinalAnswerDecision(Kind.RETRY, null, List.copyOf(Objects.requireNonNull(msgs)));
    }

    /**
     * The candidate is rejected; it and {@code msg} enter the context, then reasoning re-runs.
     *
     * @param msg the message injected after the rejected candidate
     * @return a retry carrying the injected message
     */
    public static FinalAnswerDecision retry(Msg msg) {
        return retry(List.of(Objects.requireNonNull(msg)));
    }

    /**
     * Returns the decision kind.
     *
     * @return the decision kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns whether this decision rejects the candidate.
     *
     * @return {@code true} when the decision is a retry
     */
    public boolean isRetry() {
        return kind == Kind.RETRY;
    }

    /**
     * Returns the replacement message.
     *
     * @return the message to output instead of the candidate, or {@code null} on a plain
     *     accept or retry
     */
    public Msg replacement() {
        return replacement;
    }

    /**
     * Returns the messages injected on a retry.
     *
     * @return the injected messages; empty unless {@link Kind#RETRY}
     */
    public List<Msg> retryMessages() {
        return retryMessages;
    }

    /** Returns a debug representation of this decision. */
    @Override
    public String toString() {
        return kind == Kind.RETRY
                ? "FinalAnswerDecision.RETRY" + retryMessages
                : "FinalAnswerDecision.ACCEPT"
                        + (replacement != null ? "(" + replacement.getTextContent() + ")" : "");
    }
}
