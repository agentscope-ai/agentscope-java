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

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.message.Msg
import io.agentscope.harness.agent.HarnessAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow

/*
 * Flow views over HarnessAgent.streamEvents, mirroring the ReActAgent extensions.
 *
 * agentscope-harness is a provided dependency of this module. These extensions compile into their
 * own class file (HarnessAgentExtensionsKt), so a core-only application that never calls them
 * never triggers its class loading, and no harness classes are required at runtime.
 *
 * HarnessAgent does not extend AgentBase (it implements Agent directly) and streamEvents is
 * declared on the concrete classes rather than on the Agent interface, so the ReActAgent
 * overloads cannot be reused here.
 */

/** [Flow] equivalent of [HarnessAgent.streamEvents] for a list of messages. */
fun HarnessAgent.streamEventsFlow(msgs: List<Msg>): Flow<AgentEvent> = streamEvents(msgs).asFlow()

/** [Flow] equivalent of [HarnessAgent.streamEvents] for a single message. */
fun HarnessAgent.streamEventsFlow(msg: Msg): Flow<AgentEvent> = streamEvents(msg).asFlow()

/** [Flow] equivalent of [HarnessAgent.streamEvents] for a plain user text prompt. */
fun HarnessAgent.streamEventsFlow(text: String): Flow<AgentEvent> = streamEvents(text).asFlow()

/** [Flow] equivalent of [HarnessAgent.streamEvents] for a list of messages with a runtime context. */
fun HarnessAgent.streamEventsFlow(msgs: List<Msg>, context: RuntimeContext): Flow<AgentEvent> =
    streamEvents(msgs, context).asFlow()

/** [Flow] equivalent of [HarnessAgent.streamEvents] for a single message with a runtime context. */
fun HarnessAgent.streamEventsFlow(msg: Msg, context: RuntimeContext): Flow<AgentEvent> =
    streamEvents(msg, context).asFlow()

/** [Flow] equivalent of [HarnessAgent.streamEvents] for a text prompt with a runtime context. */
fun HarnessAgent.streamEventsFlow(text: String, context: RuntimeContext): Flow<AgentEvent> =
    streamEvents(text, context).asFlow()
