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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * First use registers the owner pair, every later use verifies it.
 *
 * <p>The pair is what makes a caller-named session safe: the session id alone is guessable from
 * logs, but appending to someone else's conversation also requires their user and agent id — and a
 * mismatch is rejected instead of silently opening an empty conversation in another namespace.
 */
@DisplayName("ProtocolSessionRegistry ownership")
class ProtocolSessionRegistryTest {

    private final ProtocolSessionRegistry registry = BaseStoreProtocolSessionRegistry.inMemory();

    @Test
    void firstUseRegistersAndLaterUsesOfTheSameOwnerPass() {
        registry.registerOrVerify("sub-1", "u-1", "worker", "planner");

        assertDoesNotThrow(() -> registry.registerOrVerify("sub-1", "u-1", "worker", "planner"));
        assertDoesNotThrow(() -> registry.registerOrVerify("sub-1", "u-1", "worker", "other"));
    }

    @Test
    void anotherUserCannotClaimTheSession() {
        registry.registerOrVerify("sub-1", "u-1", "worker", "planner");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> registry.registerOrVerify("sub-1", "u-2", "worker", "planner"));

        assertTrue(error.getMessage().contains("session_owner_mismatch"), error.getMessage());
    }

    @Test
    void anotherAgentCannotClaimTheSession() {
        registry.registerOrVerify("sub-1", "u-1", "worker", "planner");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> registry.registerOrVerify("sub-1", "u-1", "reviewer", "planner"));
        assertTrue(error.getMessage().contains("session_owner_mismatch"), error.getMessage());
    }

    @Test
    void aBlankSessionIsIgnored() {
        assertDoesNotThrow(() -> registry.registerOrVerify(null, "u-1", "worker", "planner"));
        assertDoesNotThrow(() -> registry.registerOrVerify(" ", "u-1", "worker", "planner"));
    }
}
