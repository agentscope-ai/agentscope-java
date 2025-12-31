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
package io.agentscope.quarkus.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AgentScopeRecorder.
 */
class AgentScopeRecorderTest {

    private AgentScopeRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new AgentScopeRecorder();
    }

    @Test
    void testRecorderCanBeInstantiated() {
        assertNotNull(recorder);
    }

    @Test
    void testInitializeWithNullConfig() {
        // The initialize method should handle null gracefully
        assertDoesNotThrow(() -> recorder.initialize(null));
    }

    @Test
    void testInitializeWithMockConfig() {
        AgentScopeConfig mockConfig = mock(AgentScopeConfig.class);

        // Initialize should not throw
        assertDoesNotThrow(() -> recorder.initialize(mockConfig));
    }

    @Test
    void testInitializeMultipleTimes() {
        AgentScopeConfig mockConfig = mock(AgentScopeConfig.class);

        // Multiple initializations should not throw
        assertDoesNotThrow(
                () -> {
                    recorder.initialize(mockConfig);
                    recorder.initialize(mockConfig);
                    recorder.initialize(null);
                });
    }

    @Test
    void testRecorderHasRecorderAnnotation() {
        // Verify the class has the @Recorder annotation
        assertTrue(
                AgentScopeRecorder.class.isAnnotationPresent(
                        io.quarkus.runtime.annotations.Recorder.class));
    }
}
