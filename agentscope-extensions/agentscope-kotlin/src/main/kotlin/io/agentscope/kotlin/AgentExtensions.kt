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
