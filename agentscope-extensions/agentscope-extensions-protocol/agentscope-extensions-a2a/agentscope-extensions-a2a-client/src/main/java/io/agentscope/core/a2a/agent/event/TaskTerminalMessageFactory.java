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

package io.agentscope.core.a2a.agent.event;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;

final class TaskTerminalMessageFactory {

    private static final String AUTH_REQUIRED_UNSUPPORTED_MESSAGE =
            "Remote A2A task requires authentication, but AgentScope 2.0 has no in-task "
                    + "authentication resume contract.";

    private TaskTerminalMessageFactory() {}

    static boolean isTerminal(TaskState state) {
        return state != null && (state.isFinal() || state.isInterrupted());
    }

    static boolean isAuthenticationRequired(TaskState state) {
        return state == TaskState.TASK_STATE_AUTH_REQUIRED;
    }

    static IllegalStateException authenticationRequiredError() {
        return new IllegalStateException(AUTH_REQUIRED_UNSUPPORTED_MESSAGE);
    }

    static Msg create(Task task, TaskStatus status, String agentName) {
        TaskState state = status == null ? null : status.state();
        List<Artifact> artifacts = task == null ? List.of() : task.artifacts();
        if (artifacts == null) {
            artifacts = List.of();
        }

        Msg result;
        if (state == TaskState.TASK_STATE_COMPLETED && !artifacts.isEmpty()) {
            result = MessageConvertUtil.convertFromArtifact(artifacts, agentName);
        } else if (status != null && status.message() != null) {
            result = MessageConvertUtil.convertFromMessage(status.message(), agentName);
        } else if (!artifacts.isEmpty()) {
            result = MessageConvertUtil.convertFromArtifact(artifacts, agentName);
        } else {
            result =
                    Msg.builder()
                            .name(agentName)
                            .textContent("A2A task ended with state: " + wireState(state))
                            .build();
        }
        result.getMetadata().put(MessageConstants.A2A_TASK_STATE_METADATA_KEY, wireState(state));
        return result;
    }

    static String wireState(TaskState state) {
        if (state == null) {
            return "unknown";
        }
        return switch (state) {
            case TASK_STATE_SUBMITTED -> "submitted";
            case TASK_STATE_WORKING -> "working";
            case TASK_STATE_INPUT_REQUIRED -> "input-required";
            case TASK_STATE_AUTH_REQUIRED -> "auth-required";
            case TASK_STATE_COMPLETED -> "completed";
            case TASK_STATE_CANCELED -> "canceled";
            case TASK_STATE_FAILED -> "failed";
            case TASK_STATE_REJECTED -> "rejected";
            case UNRECOGNIZED -> "unrecognized";
        };
    }
}
