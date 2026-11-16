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
package io.agentscope.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import java.util.Locale;
import java.util.Objects;

/**
 * The semantic result of one invocation. A normally completed publisher can have a suspended,
 * interrupted or failed outcome. This record does not decide the terminal state of a durable task.
 */
public record RunOutcome(Status status, String reason) {
    public enum Status {
        COMPLETED,
        SUSPENDED,
        INTERRUPTED,
        FAILED,
        CANCELLED;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Status fromValue(String value) {
            for (var status : values()) if (status.value().equals(value)) return status;
            throw new IllegalArgumentException("Unknown invocation outcome: " + value);
        }
    }

    public RunOutcome {
        Objects.requireNonNull(status, "status");
    }

    public static RunOutcome fromResult(Msg result) {
        return fromReason(result == null ? null : result.getGenerateReason());
    }

    public static RunOutcome fromReason(GenerateReason reason) {
        Status status =
                reason == null
                        ? Status.COMPLETED
                        : switch (reason) {
                            case TOOL_SUSPENDED,
                                    HOST_PAUSED,
                                    PERMISSION_ASKING,
                                    REASONING_STOP_REQUESTED,
                                    ACTING_STOP_REQUESTED,
                                    MIDDLEWARE_STOP_REQUESTED ->
                                    Status.SUSPENDED;
                            case INTERRUPTED -> Status.INTERRUPTED;
                            case MAX_ITERATIONS, ALL_TOOLS_DENIED -> Status.FAILED;
                            default -> Status.COMPLETED;
                        };
        return new RunOutcome(status, reason == null ? null : reason.name());
    }
}
