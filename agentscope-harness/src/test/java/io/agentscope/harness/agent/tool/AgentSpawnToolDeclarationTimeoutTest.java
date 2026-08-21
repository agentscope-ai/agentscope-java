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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests operator-controlled subagent timeout resolution: a per-subagent
 * {@link SubagentDeclaration#getTimeoutSeconds()} or a per-call
 * {@link AgentSpawnTool#CTX_TIMEOUT_SECONDS} overrides the LLM's {@code timeout_seconds} argument,
 * so long-running subagents can be bounded by the application rather than the model.
 */
@DisplayName("AgentSpawnTool operator timeout (declaration / CTX_TIMEOUT_SECONDS)")
class AgentSpawnToolDeclarationTimeoutTest {

    private static final RuntimeContext EMPTY = RuntimeContext.empty();

    private static Optional<SubagentDeclaration> declWithTimeout(Integer seconds) {
        return Optional.of(
                SubagentDeclaration.builder()
                        .name("sub")
                        .description("long-running subagent")
                        .timeoutSeconds(seconds)
                        .build());
    }

    @Test
    @DisplayName("Declaration timeout overrides the LLM timeout_seconds")
    void declarationOverridesLlmValue() {
        assertEquals(
                120_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, EMPTY, declWithTimeout(120)),
                "declaration timeout must win over the LLM's 5s");
    }

    @Test
    @DisplayName("Declaration timeout overrides an LLM async request (timeout_seconds=0)")
    void declarationOverridesAsyncRequest() {
        assertEquals(
                90_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(0, EMPTY, declWithTimeout(90)),
                "a configured timeout means run synchronously, overriding async 0");
    }

    @Test
    @DisplayName("Declaration timeout is clamped to the maximum")
    void declarationClampedToMax() {
        assertEquals(
                600_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, EMPTY, declWithTimeout(9999)),
                "declaration timeout above 600s must clamp");
    }

    @Test
    @DisplayName("Non-positive declaration timeout is ignored, deferring to the LLM value")
    void nonPositiveDeclarationIgnored() {
        assertEquals(
                5_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, EMPTY, declWithTimeout(0)),
                "timeout<=0 is unset; fall back to the LLM's 5s");
        assertEquals(
                0L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(0, EMPTY, declWithTimeout(-1)),
                "unset declaration keeps async 0");
    }

    @Test
    @DisplayName("Null declaration timeout leaves LLM behavior unchanged")
    void nullDeclarationLeavesLlmBehavior() {
        assertEquals(
                0L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(0, EMPTY, declWithTimeout(null)),
                "no configured timeout keeps async 0");
        assertEquals(
                7_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(7, EMPTY, Optional.empty()),
                "no declaration keeps the LLM's 7s");
    }

    @Test
    @DisplayName("CTX_TIMEOUT_SECONDS overrides both the declaration and the LLM value")
    void contextOverridesDeclaration() {
        RuntimeContext ctx =
                RuntimeContext.builder().put(AgentSpawnTool.CTX_TIMEOUT_SECONDS, 200).build();
        assertEquals(
                200_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, ctx, declWithTimeout(120)),
                "per-call context timeout takes precedence over the declaration");
    }

    @Test
    @DisplayName("CTX_TIMEOUT_SECONDS accepts a numeric string and overrides async 0")
    void contextAcceptsStringAndOverridesAsync() {
        RuntimeContext ctx =
                RuntimeContext.builder().put(AgentSpawnTool.CTX_TIMEOUT_SECONDS, "150").build();
        assertEquals(150_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, ctx, Optional.empty()));
    }

    @Test
    @DisplayName("Force-sync absolute override still wins over declaration timeout")
    void forceSyncOverrideBeatsDeclaration() {
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
                        .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 45)
                        .build();
        assertEquals(
                45_000L,
                AgentSpawnTool.resolveEffectiveTimeoutMs(5, ctx, declWithTimeout(120)),
                "force-sync absolute override retains top priority");
    }

    @Test
    @DisplayName("Two-arg overload is unchanged (no declaration): async 0 stays async")
    void twoArgOverloadUnchanged() {
        assertEquals(0L, AgentSpawnTool.resolveEffectiveTimeoutMs(0, EMPTY));
        assertEquals(5_000L, AgentSpawnTool.resolveEffectiveTimeoutMs(5, EMPTY));
    }
}
