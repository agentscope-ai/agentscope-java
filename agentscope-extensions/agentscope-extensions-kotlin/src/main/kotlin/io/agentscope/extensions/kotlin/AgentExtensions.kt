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
import io.agentscope.core.agent.CallableAgent
import io.agentscope.core.agent.ObservableAgent
import io.agentscope.core.message.Msg
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull

/*
 * Coroutine-friendly views over the Reactor-based agent APIs.
 *
 * The core stays Reactor-based; these extensions only adapt the boundary, so Kotlin callers
 * rarely need to touch Mono directly:
 *
 *     val reply = agent.callSuspend("What is AgentScope?")
 *
 * The "Suspend" suffix is deliberate: Kotlin resolves member functions before extension
 * functions, so an extension named `call` would be shadowed by CallableAgent.call and could
 * never be invoked. The naming also matches the 1.x agentscope-extensions-kotlin module, so
 * code migrating from 1.x keeps compiling.
 *
 * Cancellation propagates: cancelling the calling coroutine cancels the underlying Mono
 * subscription. Errors surface as thrown exceptions rather than as an error signal.
 */

/* ---------- CallableAgent.call(...) -> suspend ---------- */

/** Suspending equivalent of [CallableAgent.call] with no input. */
suspend fun CallableAgent.callSuspend(): Msg = call().awaitSingle()

/** Suspending equivalent of [CallableAgent.call] for a single message. */
suspend fun CallableAgent.callSuspend(msg: Msg): Msg = call(msg).awaitSingle()

/** Suspending equivalent of [CallableAgent.call] for a list of messages. */
suspend fun CallableAgent.callSuspend(msgs: List<Msg>): Msg = call(msgs).awaitSingle()

/** Suspending equivalent of [CallableAgent.call] for a plain user text prompt. */
suspend fun CallableAgent.callSuspend(text: String): Msg = call(text).awaitSingle()

/** Suspending structured-output call with no input, described by a class. */
suspend fun CallableAgent.callSuspend(structuredModel: Class<*>): Msg =
    call(structuredModel).awaitSingle()

/** Suspending structured-output call for a single message, described by a class. */
suspend fun CallableAgent.callSuspend(msg: Msg, structuredModel: Class<*>): Msg =
    call(msg, structuredModel).awaitSingle()

/** Suspending structured-output call for a list of messages, described by a class. */
suspend fun CallableAgent.callSuspend(msgs: List<Msg>, structuredModel: Class<*>): Msg =
    call(msgs, structuredModel).awaitSingle()

/** Suspending structured-output call for a text prompt, described by a class. */
suspend fun CallableAgent.callSuspend(text: String, structuredModel: Class<*>): Msg =
    call(text, structuredModel).awaitSingle()

/** Suspending structured-output call with no input, described by a JSON schema. */
suspend fun CallableAgent.callSuspend(schema: JsonNode): Msg = call(schema).awaitSingle()

/** Suspending structured-output call for a single message, described by a JSON schema. */
suspend fun CallableAgent.callSuspend(msg: Msg, schema: JsonNode): Msg =
    call(msg, schema).awaitSingle()

/** Suspending structured-output call for a list of messages, described by a JSON schema. */
suspend fun CallableAgent.callSuspend(msgs: List<Msg>, schema: JsonNode): Msg =
    call(msgs, schema).awaitSingle()

/** Suspending structured-output call for a text prompt, described by a JSON schema. */
suspend fun CallableAgent.callSuspend(text: String, schema: JsonNode): Msg =
    call(text, schema).awaitSingle()

/* ---------- ObservableAgent.observe(...) -> suspend ---------- */

/**
 * Suspending equivalent of [ObservableAgent.observe] for a single message.
 *
 * `observe` returns `Mono<Void>`, which completes without emitting, so this awaits completion
 * rather than a value.
 */
suspend fun ObservableAgent.observeSuspend(msg: Msg) {
    observe(msg).awaitSingleOrNull()
}

/**
 * Suspending equivalent of [ObservableAgent.observe] for a list of messages.
 *
 * See [observeSuspend] for why completion, not a value, is awaited.
 */
suspend fun ObservableAgent.observeSuspend(msgs: List<Msg>) {
    observe(msgs).awaitSingleOrNull()
}
