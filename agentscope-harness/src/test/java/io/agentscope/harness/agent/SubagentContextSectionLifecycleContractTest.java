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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Contract markers for the child identity section appended to every subagent system prompt: the
 * final message is the invocation's delivered result (not the "only thing the main agent
 * receives" — intermediate events stream separately for observability), follow-ups may continue
 * supported sessions but must not be counted on, and the result must state what was actually
 * verified and its limitations. Marker-level only — behavior is proven by the AgentSpawnTool*
 * tests, not by verbatim prompt snapshots.
 */
class SubagentContextSectionLifecycleContractTest {

    /** The child prompt as actually assembled (blank base → context section only). */
    private static final String SECTION = HarnessAgentBuilderSupport.buildSubagentSysPrompt("");

    @Test
    void noAbsoluteEphemeralOrOnlyFinalTextClaims() {
        assertFalse(SECTION.contains("Be ephemeral"), SECTION);
        assertFalse(SECTION.contains("terminated after task completion"), SECTION);
        assertFalse(SECTION.contains("ONLY thing the main agent receives"), SECTION);
    }

    @Test
    void finalMessageIsTheDeliveredResult_intermediateEventsAreObservability() {
        assertTrue(SECTION.contains("final message"), SECTION);
        assertTrue(SECTION.contains("run ends"), SECTION);
        assertTrue(SECTION.contains("observability"), SECTION);
    }

    @Test
    void followUpsPossibleButNotCountedOn() {
        assertTrue(SECTION.contains("Follow-up"), SECTION);
        assertTrue(SECTION.contains("finish completely in this run"), SECTION);
    }

    @Test
    void resultMustStateVerificationAndLimitations() {
        assertTrue(SECTION.contains("verified"), SECTION);
        assertTrue(SECTION.contains("limitations"), SECTION);
        assertTrue(SECTION.contains("No report files"), SECTION);
    }
}
