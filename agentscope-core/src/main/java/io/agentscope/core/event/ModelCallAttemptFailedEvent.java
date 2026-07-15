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

/**
 * Emitted when a model call attempt fails.
 *
 * <p>Carries a structured {@link ModelCallAttemptFailureCategory} and sanitized error details
 * without exposing API keys, credentials, or raw response bodies.
 */
public class ModelCallAttemptFailedEvent extends AgentEvent {

    private final String replyId;
    private final int attemptIndex;
    private final int maxAttempts;
    private final ModelCallAttemptFailureCategory failureCategory;
    private final boolean retryable;
    private final ModelCallAttemptNextAction nextAction;
    private final String errorCode;
    private final String errorMessage;
    private final ModelCallAttemptRole role;

    @JsonCreator
    public ModelCallAttemptFailedEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("attemptIndex") int attemptIndex,
            @JsonProperty("maxAttempts") int maxAttempts,
            @JsonProperty("failureCategory") ModelCallAttemptFailureCategory failureCategory,
            @JsonProperty("retryable") boolean retryable,
            @JsonProperty("nextAction") ModelCallAttemptNextAction nextAction,
            @JsonProperty("errorCode") String errorCode,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("role") ModelCallAttemptRole role) {
        super(id, createdAt);
        this.replyId = replyId;
        this.attemptIndex = attemptIndex;
        this.maxAttempts = maxAttempts;
        this.failureCategory = failureCategory;
        this.retryable = retryable;
        this.nextAction = nextAction;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.role = role;
    }

    public ModelCallAttemptFailedEvent(
            String replyId,
            int attemptIndex,
            int maxAttempts,
            ModelCallAttemptFailureCategory failureCategory,
            boolean retryable,
            ModelCallAttemptNextAction nextAction,
            String errorCode,
            String errorMessage,
            ModelCallAttemptRole role) {
        this.replyId = replyId;
        this.attemptIndex = attemptIndex;
        this.maxAttempts = maxAttempts;
        this.failureCategory = failureCategory;
        this.retryable = retryable;
        this.nextAction = nextAction;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.role = role;
    }

    /**
     * Backward-compatible constructor without nextAction and maxAttempts.
     *
     * <p>Computes {@code nextAction} from {@code retryable} and {@code role}:
     * retryable → RETRY, not retryable with FALLBACK role or fallback available → FALLBACK,
     * otherwise → FAIL.
     *
     * @deprecated Use the full constructor with maxAttempts and nextAction instead.
     */
    @Deprecated
    public ModelCallAttemptFailedEvent(
            String replyId,
            int attemptIndex,
            ModelCallAttemptFailureCategory failureCategory,
            boolean retryable,
            String errorCode,
            String errorMessage,
            ModelCallAttemptRole role) {
        this.replyId = replyId;
        this.attemptIndex = attemptIndex;
        this.maxAttempts = 0;
        this.failureCategory = failureCategory;
        this.retryable = retryable;
        this.nextAction =
                retryable ? ModelCallAttemptNextAction.RETRY : ModelCallAttemptNextAction.FAIL;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.role = role;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.MODEL_ATTEMPT_FAILED;
    }

    public String getReplyId() {
        return replyId;
    }

    public int getAttemptIndex() {
        return attemptIndex;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public ModelCallAttemptFailureCategory getFailureCategory() {
        return failureCategory;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public ModelCallAttemptNextAction getNextAction() {
        return nextAction;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public ModelCallAttemptRole getRole() {
        return role;
    }
}
