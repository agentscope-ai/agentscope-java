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
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link RemoteSubagentTransport} backed by the Agent Protocol HTTP client. */
public final class AgentProtocolTransport implements RemoteSubagentTransport {

    public static final String TYPE = "agent-protocol";

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolTransport.class);

    private final AgentProtocolTaskClient client;

    public AgentProtocolTransport() {
        this(new AgentProtocolTaskClient());
    }

    public AgentProtocolTransport(AgentProtocolTaskClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String transportType() {
        return TYPE;
    }

    @Override
    public void submit(
            RemoteTarget target,
            String taskId,
            String agentId,
            String input,
            RemoteSubmitContext context)
            throws Exception {
        client.submitTask(
                target.baseUrl(),
                target.headers(),
                taskId,
                agentId,
                input,
                context != null ? context : RemoteSubmitContext.empty());
    }

    @Override
    public RemoteTaskStatus getStatus(RemoteTarget target, String taskId) throws Exception {
        return client.getStatus(target.baseUrl(), target.headers(), taskId);
    }

    @Override
    public String waitForResult(RemoteTarget target, String taskId, long timeoutSeconds)
            throws Exception {
        return client.waitForResult(target.baseUrl(), target.headers(), taskId, timeoutSeconds);
    }

    @Override
    public void cancel(RemoteTarget target, String taskId) throws Exception {
        client.cancelTask(target.baseUrl(), target.headers(), taskId);
    }

    @Override
    public void resume(RemoteTarget target, String taskId, List<RemoteConfirmDecision> decisions)
            throws Exception {
        client.resumeTask(target.baseUrl(), target.headers(), taskId, decisions);
    }

    @Override
    public Closeable streamEvents(
            RemoteTarget target, String taskId, long fromSeq, Consumer<RemoteAgentEvent> consumer) {
        try {
            return client.openEventStream(
                    target.baseUrl(), target.headers(), taskId, fromSeq, consumer);
        } catch (Exception e) {
            log.debug(
                    "Failed to open remote event stream for task {} (degrading to poll): {}",
                    taskId,
                    e.toString());
            return () -> {};
        }
    }
}
