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
package io.agentscope.harness.agent.subagent.task;

import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import java.io.Closeable;
import java.util.List;
import java.util.function.Consumer;

/**
 * Transport abstraction for remote subagent execution (Agent Protocol today; A2A later).
 */
public interface RemoteSubagentTransport {

    /** Stable transport type string persisted on {@link TaskRecord#getTransportType()}. */
    String transportType();

    void submit(
            RemoteTarget target,
            String taskId,
            String agentId,
            String input,
            RemoteSubmitContext context)
            throws Exception;

    RemoteTaskStatus getStatus(RemoteTarget target, String taskId) throws Exception;

    String waitForResult(RemoteTarget target, String taskId, long timeoutSeconds) throws Exception;

    void cancel(RemoteTarget target, String taskId) throws Exception;

    void resume(RemoteTarget target, String taskId, List<RemoteConfirmDecision> decisions)
            throws Exception;

    /**
     * Opens an event stream for the remote task. Default is a no-op so transports without streaming
     * degrade gracefully to polling.
     *
     * @return closeable handle that stops the subscription
     */
    default Closeable streamEvents(
            RemoteTarget target, String taskId, long fromSeq, Consumer<RemoteAgentEvent> consumer) {
        return () -> {};
    }
}
