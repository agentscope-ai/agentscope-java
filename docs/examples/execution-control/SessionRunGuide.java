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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Companion to the session guide; the application configures the agent and authenticates users. */
public final class SessionRunGuide {
    private SessionRunGuide() {}

    public static AgentSession session(HarnessAgent agent, String ownerId, String sessionId) {
        return agent.session(RuntimeContext.builder().userId(ownerId).sessionId(sessionId).build());
    }

    /** HTTP acceptance: the framework owns execution, independently of the response subscriber. */
    public static Mono<AgentSession.Task> submit(AgentSession session, String requestKey, String input) {
        return Mono.fromCallable(() -> session.submit(requestKey, input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Guide the known active task; stale or idle operations are rejected. */
    public static Mono<AgentSession.InputReceipt> steer(AgentSession session, AgentSession.Task task, String input) {
        return Mono.fromCallable(() -> session.steer(task, input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Observe durable history. Cancellation of this read does not cancel inference. */
    public static Mono<List<SessionEvent>> events(AgentSession session, long after) {
        return Mono.fromCallable(() -> session.log().readAfter(after, 100))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Guard against a stop request arriving after a different execution has started. */
    public static Mono<Boolean> interrupt(AgentSession session, String runId) {
        return Mono.fromCallable(() -> session.interrupt(runId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Call only after the prior execution has stopped and pending actions have been answered. */
    public static Mono<AgentSession.Task> resume(AgentSession session, String turnId) {
        return Mono.fromCallable(() -> session.resume(turnId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
