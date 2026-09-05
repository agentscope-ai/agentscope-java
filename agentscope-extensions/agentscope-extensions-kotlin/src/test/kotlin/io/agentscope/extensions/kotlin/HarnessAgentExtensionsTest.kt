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
import io.agentscope.core.agent.test.MockModel
import io.agentscope.core.event.AgentEndEvent
import io.agentscope.core.event.AgentStartEvent
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem
import java.nio.file.Path
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for the `Flow` extensions over [HarnessAgent.streamEvents].
 *
 * `agentscope-harness` is a `provided` dependency, which is on the test classpath, so these
 * also prove the extensions link against the real harness API.
 */
class HarnessAgentExtensionsTest {

    @TempDir lateinit var workspace: Path

    private fun newAgent(): HarnessAgent =
        HarnessAgent.builder()
            .name("kotlin-harness")
            .model(MockModel("harness reply"))
            .workspace(workspace)
            .abstractFilesystem(LocalFilesystem(workspace))
            .build()

    private fun userMsg(text: String): Msg =
        Msg.builder().name("user").role(MsgRole.USER).textContent(text).build()

    @Test
    fun `streamEventsFlow emits the harness event stream for text, message and list`() =
        runBlocking {
            newAgent().use { agent ->
                for (events in
                    listOf(
                        agent.streamEventsFlow("hello").toList(),
                        agent.streamEventsFlow(userMsg("hello")).toList(),
                        agent.streamEventsFlow(listOf(userMsg("hello"))).toList(),
                    )) {
                    assertTrue(events.isNotEmpty(), "expected a non-empty event stream")
                    assertTrue(
                        events.any { it is AgentStartEvent },
                        "expected an AgentStartEvent in the stream"
                    )
                    assertTrue(
                        events.any { it is AgentEndEvent },
                        "expected an AgentEndEvent in the stream"
                    )
                }
            }
        }

    @Test
    fun `streamEventsFlow overloads with a RuntimeContext emit the harness event stream`() =
        runBlocking {
            val ctx = RuntimeContext.builder().sessionId("kotlin-harness-ctx").build()

            newAgent().use { agent ->
                for (events in
                    listOf(
                        agent.streamEventsFlow("hello", ctx).toList(),
                        agent.streamEventsFlow(userMsg("hello"), ctx).toList(),
                        agent.streamEventsFlow(listOf(userMsg("hello")), ctx).toList(),
                    )) {
                    assertTrue(events.any { it is AgentEndEvent })
                }
            }
        }
}
