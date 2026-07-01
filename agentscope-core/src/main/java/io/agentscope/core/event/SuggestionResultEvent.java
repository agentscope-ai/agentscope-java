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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

/**
 * Terminal, non-streaming suggestion event emitted between {@link AgentResultEvent} and
 * {@link AgentEndEvent}.
 *
 * <p>Carries the final parsed follow-up suggestion list produced by the post-conversation
 * suggestion middleware. Suggestions are always exposed as an immutable {@link List} of strings —
 * a failed or skipped generation surfaces as an empty list so consumers can render
 * "no suggestions" without null checks.
 *
 * <p>This event replaces the earlier three-event {@code SuggestionStart / Delta / End} stream:
 * a single result event is easier to consume for front-ends that only care about the final list.
 */
public class SuggestionResultEvent extends AgentEvent {

    private final String replyId;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final List<String> suggestions;

    @JsonCreator
    public SuggestionResultEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("suggestions") List<String> suggestions) {
        super(id, createdAt);
        this.replyId = replyId;
        this.suggestions = freeze(suggestions);
    }

    public SuggestionResultEvent(String replyId, List<String> suggestions) {
        this.replyId = replyId;
        this.suggestions = freeze(suggestions);
    }

    private static List<String> freeze(List<String> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(in);
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.SUGGESTION_RESULT;
    }

    public String getReplyId() {
        return replyId;
    }

    /** Returns an immutable list of suggestion strings; empty when generation produced nothing. */
    public List<String> getSuggestions() {
        return suggestions;
    }
}
