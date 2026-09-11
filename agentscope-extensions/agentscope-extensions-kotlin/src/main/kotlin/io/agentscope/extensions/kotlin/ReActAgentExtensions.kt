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
package io.agentscope.extensions.kotlin

import io.agentscope.core.ReActAgent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.message.Msg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow

/*
 * Flow views over ReActAgent.streamEvents:
 *
 *     agent.streamEventsFlow("Hello").collect { event -> render(event) }
 *
 * StreamableAgent.stream(...) is intentionally not wrapped: every overload is
 * @Deprecated(since = "2.0.0", forRemoval = true) in favour of streamEvents, which emits the
 * fine-grained AgentEvent stream.
 *
 * The returned Flow is cold - each collection starts a new agent run, mirroring Flux semantics.
 * Cancelling the collecting coroutine cancels the underlying subscription.
 */

/** [Flow] equivalent of [ReActAgent.streamEvents] for a list of messages. */
fun ReActAgent.streamEventsFlow(msgs: List<Msg>): Flow<AgentEvent> = streamEvents(msgs).asFlow()

/** [Flow] equivalent of [ReActAgent.streamEvents] for a single message. */
fun ReActAgent.streamEventsFlow(msg: Msg): Flow<AgentEvent> = streamEvents(msg).asFlow()

/** [Flow] equivalent of [ReActAgent.streamEvents] for a plain user text prompt. */
fun ReActAgent.streamEventsFlow(text: String): Flow<AgentEvent> = streamEvents(text).asFlow()

/** [Flow] equivalent of [ReActAgent.streamEvents] for a list of messages with a runtime context. */
fun ReActAgent.streamEventsFlow(msgs: List<Msg>, context: RuntimeContext): Flow<AgentEvent> =
    streamEvents(msgs, context).asFlow()

/** [Flow] equivalent of [ReActAgent.streamEvents] for a single message with a runtime context. */
fun ReActAgent.streamEventsFlow(msg: Msg, context: RuntimeContext): Flow<AgentEvent> =
    streamEvents(msg, context).asFlow()

/** [Flow] equivalent of [ReActAgent.streamEvents] for a text prompt with a runtime context. */
fun ReActAgent.streamEventsFlow(text: String, context: RuntimeContext): Flow<AgentEvent> =
    streamEvents(text, context).asFlow()
