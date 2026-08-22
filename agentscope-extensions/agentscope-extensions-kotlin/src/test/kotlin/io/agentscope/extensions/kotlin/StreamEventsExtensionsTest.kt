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
import io.agentscope.core.agent.test.MockModel
import io.agentscope.core.event.AgentEndEvent
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentStartEvent
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests for the `Flow` extensions over `ReActAgent.streamEvents`. */
class StreamEventsExtensionsTest {

    private fun newAgent(response: String = "streamed reply"): ReActAgent =
        ReActAgent.builder()
            .name("kotlin-stream")
            .sysPrompt("You are a test agent.")
            .model(MockModel(response))
            .build()

    private fun userMsg(text: String): Msg =
        Msg.builder().name("user").role(MsgRole.USER).textContent(text).build()

    @Test
    fun `streamEventsFlow emits the full event stream in order`() = runBlocking {
        val events: List<AgentEvent> = newAgent().streamEventsFlow("hello").toList()

        assertTrue(events.isNotEmpty(), "expected at least one event")
        assertTrue(
            events.first() is AgentStartEvent,
            "stream must open with AgentStartEvent, got ${events.first()::class.java.simpleName}"
        )
        assertTrue(
            events.last() is AgentEndEvent,
            "stream must close with AgentEndEvent, got ${events.last()::class.java.simpleName}"
        )
    }

    @Test
    fun `streamEventsFlow accepts a message and a message list`() = runBlocking {
        val fromMsg = newAgent().streamEventsFlow(userMsg("hi")).toList()
        val fromList = newAgent().streamEventsFlow(listOf(userMsg("hi"))).toList()

        assertTrue(fromMsg.isNotEmpty())
        assertTrue(fromList.isNotEmpty())
    }

    @Test
    fun `streamEventsFlow overloads with a RuntimeContext emit the event stream`() = runBlocking {
        val ctx = RuntimeContext.builder().sessionId("kotlin-ctx").build()

        val fromText = newAgent().streamEventsFlow("hi", ctx).toList()
        val fromMsg = newAgent().streamEventsFlow(userMsg("hi"), ctx).toList()
        val fromList = newAgent().streamEventsFlow(listOf(userMsg("hi")), ctx).toList()

        for (events in listOf(fromText, fromMsg, fromList)) {
            assertTrue(events.first() is AgentStartEvent)
            assertTrue(events.last() is AgentEndEvent)
        }
    }

    @Test
    fun `the returned Flow is cold - each collection starts a new run`() = runBlocking {
        val model = MockModel(listOf("first", "second"))
        val agent =
            ReActAgent.builder()
                .name("kotlin-cold")
                .sysPrompt("You are a test agent.")
                .model(model)
                .build()

        val flow = agent.streamEventsFlow("go")
        flow.toList()
        val callsAfterFirst = model.callCount
        flow.toList()

        assertEquals(1, callsAfterFirst, "first collection must trigger exactly one model call")
        assertEquals(2, model.callCount, "collecting again must start a second run")
    }

    @Test
    fun `terminating collection early cancels the underlying subscription`() = runBlocking {
        val agent = newAgent()

        // `first()` cancels upstream as soon as one element arrives; if cancellation were not
        // propagated to the Flux this would hang until the whole run completed.
        val firstEvent = agent.streamEventsFlow("hello").first()

        assertTrue(firstEvent is AgentStartEvent)
    }
}
