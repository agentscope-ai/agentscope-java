<!--
  ~ Copyright 2024-2026 the original author or authors.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
-->





package io.agentscope.kotlin

import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.Event
import io.agentscope.core.agent.StreamOptions
import io.agentscope.core.message.Msg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactive.asFlow

/* ---------- call(...) -> suspend ---------- */

suspend fun Agent.callSuspend(msg: Msg): Msg =
    this.call(msg).awaitSingle()

suspend fun Agent.callSuspend(msgs: List<Msg>): Msg =
    this.call(msgs).awaitSingle()

suspend fun Agent.callSuspend(): Msg =
    this.call().awaitSingle()

suspend fun Agent.callSuspend(
    msg: Msg,
    structuredModel: Class<*>
): Msg =
    this.call(msg, structuredModel).awaitSingle()

suspend fun Agent.callSuspend(
    msgs: List<Msg>,
    structuredModel: Class<*>
): Msg =
    this.call(msgs, structuredModel).awaitSingle()

suspend fun Agent.callSuspend(
    structuredModel: Class<*>
): Msg =
    this.call(structuredModel).awaitSingle()

/* ---------- observe(...) -> suspend ---------- */

suspend fun Agent.observeSuspend(msg: Msg) {
    this.observe(msg).awaitFirstOrNull()
}

suspend fun Agent.observeSuspend(msgs: List<Msg>) {
    this.observe(msgs).awaitFirstOrNull()
}

/* ---------- stream(...) -> Flow ---------- */

fun Agent.streamFlow(
    msg: Msg,
    options: StreamOptions = StreamOptions.defaults()
): Flow<Event> =
    this.stream(msg, options).asFlow()

fun Agent.streamFlow(
    msgs: List<Msg>,
    options: StreamOptions = StreamOptions.defaults()
): Flow<Event> =
    this.stream(msgs, options).asFlow()

fun Agent.streamFlow(
    msg: Msg,
    options: StreamOptions,
    structuredModel: Class<*>
): Flow<Event> =
    this.stream(msg, options, structuredModel).asFlow()

fun Agent.streamFlow(
    msgs: List<Msg>,
    options: StreamOptions,
    structuredModel: Class<*>
): Flow<Event> =
    this.stream(msgs, options, structuredModel).asFlow()
