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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.agentscope.core.ReActAgent
import io.agentscope.core.agent.CallableAgent
import io.agentscope.core.agent.ObservableAgent
import io.agentscope.core.agent.test.MockModel
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono

/**
 * Tests for the suspend extensions over [CallableAgent] / [ObservableAgent].
 *
 * Two layers: a recording fake pins down delegation and Reactor/coroutine semantics
 * deterministically, and a real [ReActAgent] backed by `MockModel` proves the extensions work
 * against the actual agent implementation.
 */
class AgentExtensionsTest {

    private val json: ObjectMapper = ObjectMapper()

    private fun userMsg(text: String): Msg =
        Msg.builder().name("user").role(MsgRole.USER).textContent(text).build()

    private fun assistantMsg(text: String): Msg =
        Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent(text).build()

    /** Records which member overload an extension delegated to, and what it was given. */
    private inner class RecordingAgent(
        private val result: Mono<Msg> = Mono.just(Msg.builder().name("a").role(MsgRole.ASSISTANT).textContent("ok").build())
    ) : CallableAgent, ObservableAgent {

        var lastMethod: String? = null
        var lastMsgs: List<Msg>? = null
        var lastStructuredModel: Class<*>? = null
        var lastSchema: JsonNode? = null
        var observedMsgs: List<Msg>? = null

        override fun call(msgs: List<Msg>): Mono<Msg> {
            lastMethod = "call(List)"
            lastMsgs = msgs
            return result
        }

        override fun call(msgs: List<Msg>, structuredModel: Class<*>): Mono<Msg> {
            lastMethod = "call(List,Class)"
            lastMsgs = msgs
            lastStructuredModel = structuredModel
            return result
        }

        override fun call(msgs: List<Msg>, schema: JsonNode): Mono<Msg> {
            lastMethod = "call(List,JsonNode)"
            lastMsgs = msgs
            lastSchema = schema
            return result
        }

        override fun observe(msg: Msg): Mono<Void> {
            observedMsgs = listOf(msg)
            return Mono.empty()
        }

        override fun observe(msgs: List<Msg>): Mono<Void> {
            observedMsgs = msgs
            return Mono.empty()
        }
    }

    /* ---------- delegation ---------- */

    @Test
    fun `callSuspend with a message delegates to call(List) and returns the value`() = runBlocking {
        val agent = RecordingAgent()

        val reply = agent.callSuspend(userMsg("hi"))

        assertEquals("call(List)", agent.lastMethod)
        assertEquals(1, agent.lastMsgs?.size)
        assertEquals("hi", agent.lastMsgs?.first()?.textContent)
        assertEquals("ok", reply.textContent)
    }

    @Test
    fun `callSuspend with text delegates to the text overload`() = runBlocking {
        val agent = RecordingAgent()

        agent.callSuspend("plain text")

        assertEquals("call(List)", agent.lastMethod)
        assertEquals("plain text", agent.lastMsgs?.first()?.textContent)
    }

    @Test
    fun `callSuspend with no input delegates with an empty message list`() = runBlocking {
        val agent = RecordingAgent()

        agent.callSuspend()

        assertEquals("call(List)", agent.lastMethod)
        assertTrue(agent.lastMsgs.isNullOrEmpty())
    }

    @Test
    fun `callSuspend with a message list delegates as-is`() = runBlocking {
        val agent = RecordingAgent()
        val msgs = listOf(userMsg("one"), userMsg("two"))

        agent.callSuspend(msgs)

        assertEquals("call(List)", agent.lastMethod)
        assertEquals(2, agent.lastMsgs?.size)
    }

    @Test
    fun `callSuspend forwards a structured-output class`() = runBlocking {
        val agent = RecordingAgent()

        agent.callSuspend(userMsg("hi"), String::class.java)

        assertEquals("call(List,Class)", agent.lastMethod)
        assertEquals(String::class.java, agent.lastStructuredModel)
    }

    @Test
    fun `every structured-class overload reaches call(List,Class)`() = runBlocking {
        for (invoke in
            listOf<suspend (RecordingAgent) -> Msg>(
                { it.callSuspend(String::class.java) },
                { it.callSuspend(userMsg("m"), String::class.java) },
                { it.callSuspend(listOf(userMsg("m")), String::class.java) },
                { it.callSuspend("text", String::class.java) },
            )) {
            val agent = RecordingAgent()
            invoke(agent)
            assertEquals("call(List,Class)", agent.lastMethod)
            assertEquals(String::class.java, agent.lastStructuredModel)
        }
    }

    @Test
    fun `callSuspend forwards a JSON schema`() = runBlocking {
        val agent = RecordingAgent()
        val schema = json.readTree("""{"type":"object"}""")

        agent.callSuspend(listOf(userMsg("hi")), schema)

        assertEquals("call(List,JsonNode)", agent.lastMethod)
        assertEquals(schema, agent.lastSchema)
    }

    @Test
    fun `every JSON-schema overload reaches call(List,JsonNode)`() = runBlocking {
        val schema = json.readTree("""{"type":"object"}""")
        for (invoke in
            listOf<suspend (RecordingAgent) -> Msg>(
                { it.callSuspend(schema) },
                { it.callSuspend(userMsg("m"), schema) },
                { it.callSuspend("text", schema) },
            )) {
            val agent = RecordingAgent()
            invoke(agent)
            assertEquals("call(List,JsonNode)", agent.lastMethod)
            assertEquals(schema, agent.lastSchema)
        }
    }

    @Test
    fun `observeSuspend completes and forwards the messages`() = runBlocking {
        val agent = RecordingAgent()
        val msgs = listOf(userMsg("one"), userMsg("two"))

        agent.observeSuspend(msgs)

        assertEquals(2, agent.observedMsgs?.size)
        assertEquals("two", agent.observedMsgs?.last()?.textContent)
    }

    @Test
    fun `observeSuspend with a single message forwards it`() = runBlocking {
        val agent = RecordingAgent()

        agent.observeSuspend(userMsg("solo"))

        assertEquals(1, agent.observedMsgs?.size)
        assertEquals("solo", agent.observedMsgs?.first()?.textContent)
    }

    /* ---------- error and cancellation semantics ---------- */

    @Test
    fun `an error signal surfaces as a thrown exception`() {
        val agent = RecordingAgent(Mono.error(IllegalStateException("model exploded")))

        val error =
            assertThrows<IllegalStateException> { runBlocking { agent.callSuspend(userMsg("hi")) } }

        assertEquals("model exploded", error.message)
    }

    @Test
    fun `cancelling the coroutine cancels the underlying Mono subscription`() {
        val subscribed = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val agent =
            RecordingAgent(
                Mono.never<Msg>()
                    .doOnSubscribe { subscribed.countDown() }
                    .doOnCancel { cancelled.countDown() }
            )

        runBlocking {
            // Dispatchers.Default, not the runBlocking event loop: awaiting the latch below
            // blocks this thread, which would otherwise starve the child coroutine.
            val job: Job = launch(Dispatchers.Default) { agent.callSuspend(userMsg("hi")) }
            assertTrue(subscribed.await(5, TimeUnit.SECONDS), "Mono was never subscribed")
            job.cancelAndJoin()
        }

        assertTrue(
            cancelled.await(5, TimeUnit.SECONDS),
            "cancelling the coroutine must cancel the Mono subscription"
        )
    }

    /* ---------- against a real ReActAgent ---------- */

    @Test
    fun `callSuspend works against a real ReActAgent`() = runBlocking {
        val agent =
            ReActAgent.builder()
                .name("kotlin-test")
                .sysPrompt("You are a test agent.")
                .model(MockModel("hello from kotlin"))
                .build()

        val reply = agent.callSuspend("ping")

        assertNotNull(reply)
        assertEquals("hello from kotlin", reply.textContent)
    }

    @Test
    fun `observeSuspend works against a real ReActAgent and records the message`() = runBlocking {
        val agent =
            ReActAgent.builder()
                .name("kotlin-observe")
                .sysPrompt("You are a test agent.")
                .model(MockModel("unused"))
                .build()

        agent.observeSuspend(assistantMsg("remember this"))

        val context = agent.agentState.context
        assertTrue(
            context.any { it.textContent == "remember this" },
            "observed message must be recorded in the agent context"
        )
    }
}
