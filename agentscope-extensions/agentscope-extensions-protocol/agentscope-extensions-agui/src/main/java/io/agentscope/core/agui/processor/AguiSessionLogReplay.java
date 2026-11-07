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
package io.agentscope.core.agui.processor;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiFramedEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.session.SessionLog;
import reactor.core.publisher.Flux;

/**
 * Reconnects a client to a {@link SessionLog}-backed run, for callers that resolve the log
 * themselves.
 *
 * <p>This is the entry point for applications whose AG-UI agent registry does not expose a
 * {@code HarnessAgent}, for example when a placeholder agent delegates execution to custom code.
 * It never starts or resumes an Agent run, never calls a model and never writes to the log, so it
 * is the reconnect path of an existing run: the same cursor yields the same events. The
 * application supplies the conversation history up to that cursor.
 *
 * @see AguiRequestProcessor#attach(io.agentscope.core.agui.runtime.AguiRuntimeContextRequest)
 */
public final class AguiSessionLogReplay {

    private AguiSessionLogReplay() {}

    /**
     * Reconnects to a run, following the session log after the cursor.
     *
     * @param input the AG-UI input; only {@code threadId}, {@code runId} and {@code tools} are used
     * @param log the session log to observe
     * @param after the last sequence already represented by the application history
     * @param executionRunId the session log execution run id, or {@code null} to follow any active
     *     run
     * @param config the adapter config controlling replay detail
     * @return framed AG-UI reconnect events, ending when the run finishes
     */
    public static Flux<AguiFramedEvent> attach(
            RunAgentInput input,
            SessionLog log,
            long after,
            String executionRunId,
            AguiAdapterConfig config) {
        return AguiSessionLogAttach.attach(input, log, after, executionRunId, config);
    }

    /**
     * Reconnects to a run and hands the tail over to a live source.
     *
     * <p>The log is replayed up to {@link AguiRunBroadcaster.LiveRun#lastSeq()} and the live frames
     * continue from there without a gap or a duplicate. When {@code live} is {@code null} the
     * call is equivalent to {@link #attach(RunAgentInput, SessionLog, long, String,
     * AguiAdapterConfig)}.
     *
     * @param input the AG-UI input
     * @param log the session log to observe
     * @param after the last sequence already represented by the application history
     * @param live the live tail of the running thread, or {@code null}
     * @param executionRunId the session log execution run id, or {@code null} to follow any active
     *     run
     * @param config the adapter config controlling replay detail
     * @return framed AG-UI reconnect events
     */
    public static Flux<AguiFramedEvent> attach(
            RunAgentInput input,
            SessionLog log,
            long after,
            AguiRunBroadcaster.LiveRun live,
            String executionRunId,
            AguiAdapterConfig config) {
        return AguiSessionLogAttach.attach(input, log, after, live, executionRunId, config);
    }
}
