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
package io.agentscope.core.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CompositeAgentException extends RuntimeException {
    private final List<AgentExceptionInfo> causes;

    public CompositeAgentException(String message, List<AgentExceptionInfo> causes) {
        super(message);
        this.causes = new ArrayList<>(causes != null ? causes : Collections.emptyList());
    }

    @Override
    public String getMessage() {
        String baseMessage = super.getMessage();
        if (causes == null || causes.isEmpty()) {
            return baseMessage;
        }

        StringBuilder sb = new StringBuilder(baseMessage);
        sb.append(" (caused by: ");

        for (int i = 0; i < causes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }

            AgentExceptionInfo cause = causes.get(i);

            if (cause.agentId() != null) {
                sb.append("agentId=").append(cause.agentId());
                if (cause.agentName() != null) {
                    sb.append("(").append(cause.agentName()).append(")");
                }
            } else if (cause.agentName() != null) {
                sb.append("agentName=").append(cause.agentName());
            }

            Throwable throwable = cause.throwable();
            if (throwable != null) {
                if (cause.agentId() != null || cause.agentName() != null) {
                    sb.append(": ");
                }
                sb.append(throwable.getClass().getSimpleName())
                        .append(": ")
                        .append(
                                throwable.getMessage() != null
                                        ? throwable.getMessage()
                                        : "No message");
            } else {
                if (cause.agentId() != null || cause.agentName() != null) {
                    sb.append(": ");
                }
                sb.append("Unknown error");
            }
        }
        sb.append(")");

        return sb.toString();
    }

    public List<AgentExceptionInfo> getCauses() {
        return Collections.unmodifiableList(causes);
    }

    public record AgentExceptionInfo(String agentId, String agentName, Throwable throwable) {}
}
