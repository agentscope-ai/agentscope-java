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
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Emitted after the user has responded to a {@link RequireUserConfirmEvent}.
 */
public class UserConfirmResultEvent extends AgentEvent {

    private final String replyId;
    private final List<ConfirmResult> confirmResults;

    @JsonCreator
    public UserConfirmResultEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("confirmResults") List<ConfirmResult> confirmResults) {
        super(id, createdAt);
        this.replyId = replyId;
        this.confirmResults = confirmResults != null ? List.copyOf(confirmResults) : List.of();
    }

    public UserConfirmResultEvent(String replyId, List<ConfirmResult> confirmResults) {
        this.replyId = replyId;
        this.confirmResults = confirmResults != null ? List.copyOf(confirmResults) : List.of();
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.USER_CONFIRM_RESULT;
    }

    public String getReplyId() {
        return replyId;
    }

    public List<ConfirmResult> getConfirmResults() {
        return confirmResults;
    }
}
