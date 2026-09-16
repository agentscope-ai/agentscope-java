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
package io.agentscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for {@link ReActAgent#validateFallbackChainCapabilities}: each branch of the
 * build-time capability check is pinned by asserting the returned warning list directly (stricter
 * than a "builds without throwing" smoke assertion).
 */
@Tag("unit")
@DisplayName("ReActAgent fallback-chain capability validation Tests")
class ReActAgentCapabilityValidationTest {

    @Test
    @DisplayName("Unknown capabilities (window 0, default native flags) yield no warnings")
    void unknownCapabilitiesYieldNoWarnings() {
        Model primary = stub("primary", 0, false, false);
        Model fallback = stub("fallback", 0, false, false);

        List<String> warnings =
                ReActAgent.validateFallbackChainCapabilities(primary, List.of(fallback));

        assertEquals(List.of(), warnings, "Unknown capabilities must be tolerated silently");
    }

    @Test
    @DisplayName("Throwing capability getters are skipped, not propagated")
    void throwingGettersAreSkipped() {
        Model primary = stub("primary", 0, false, false);
        Model broken =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.empty();
                    }

                    @Override
                    public String getModelName() {
                        throw new IllegalStateException("no name");
                    }

                    @Override
                    public int getContextWindowSize() {
                        throw new IllegalStateException("no metrics");
                    }

                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        throw new IllegalStateException("no metrics");
                    }

                    @Override
                    public boolean supportsNativeStructuredOutputWithTools() {
                        throw new IllegalStateException("no metrics");
                    }
                };

        List<String> warnings =
                ReActAgent.validateFallbackChainCapabilities(primary, List.of(broken));

        assertEquals(
                List.of(), warnings, "A throwing capability getter must be skipped, never thrown");
    }

    @Test
    @DisplayName("Smaller context window yields a compaction warning")
    void smallerContextWindowYieldsCompactionWarning() {
        Model primary = stub("primary", 8192, false, false);
        Model fallback = stub("fallback", 4096, false, false);

        List<String> warnings =
                ReActAgent.validateFallbackChainCapabilities(primary, List.of(fallback));

        assertEquals(1, warnings.size(), "Exactly one warning expected");
        assertTrue(
                warnings.get(0).contains("smaller context window"),
                "Warning must mention the context window mismatch: " + warnings.get(0));
        assertTrue(warnings.get(0).contains("fallback"), "Warning must name the candidate");
    }

    @Test
    @DisplayName("Candidate positively declaring extra native support yields a warning")
    void positiveNativeMismatchYieldsWarning() {
        Model primary = stub("primary", 8192, false, false);
        Model fallback = stub("fallback", 8192, true, false);

        List<String> warnings =
                ReActAgent.validateFallbackChainCapabilities(primary, List.of(fallback));

        assertEquals(1, warnings.size(), "Exactly one warning expected");
        assertTrue(
                warnings.get(0).contains("structured-output support"),
                "Warning must mention the structured-output mismatch: " + warnings.get(0));
    }

    @Test
    @DisplayName(
            "Candidate merely lacking primary capability stays silent (unknown==default false)")
    void candidateLackingPrimaryCapabilityStaysSilent() {
        Model primary = stub("primary", 8192, true, false);
        Model fallback = stub("fallback", 8192, false, false);

        List<String> warnings =
                ReActAgent.validateFallbackChainCapabilities(primary, List.of(fallback));

        // supportsNativeStructuredOutput() has no "unknown" state: the candidate's default false
        // is indistinguishable from genuine lack of support, so warning here would be noise on a
        // legitimate mixed-provider chain.
        assertEquals(List.of(), warnings, "Reverse-direction mismatch must stay silent");
    }

    @Test
    @DisplayName("Empty chain or null primary yields no warnings")
    void emptyChainOrNullPrimaryYieldsNoWarnings() {
        assertEquals(
                List.of(),
                ReActAgent.validateFallbackChainCapabilities(
                        null, List.of(stub("fb", 8192, false, false))));
        assertEquals(
                List.of(),
                ReActAgent.validateFallbackChainCapabilities(
                        stub("p", 8192, false, false), List.of()));
    }

    /** Builds a minimal stubbed Model with explicit capability values. */
    private static Model stub(String name, int window, boolean nativeOut, boolean nativeWithTools) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return name;
            }

            @Override
            public int getContextWindowSize() {
                return window;
            }

            @Override
            public boolean supportsNativeStructuredOutput() {
                return nativeOut;
            }

            @Override
            public boolean supportsNativeStructuredOutputWithTools() {
                return nativeWithTools;
            }
        };
    }
}
