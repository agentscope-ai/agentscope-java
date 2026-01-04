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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E2E tests for Session persistence functionality.
 *
 * <p>Tests state saving/loading via JsonSession, cross-session context restoration, and session
 * lifecycle management.
 */
@Tag("e2e")
@Tag("session")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Session Persistence E2E Tests")
class SessionE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    private Path tempSessionDir;

    @BeforeEach
    void setUp() throws IOException {
        tempSessionDir = Files.createTempDirectory("e2e-session-test");
        System.out.println("Created temp session directory: " + tempSessionDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempSessionDir != null && Files.exists(tempSessionDir)) {
            Files.walk(tempSessionDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    System.err.println("Failed to delete: " + path);
                                }
                            });
            System.out.println("Cleaned up temp session directory");
        }
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should save and load agent state via JsonSession")
    void testBasicStatePersistence(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Basic State Persistence with " + provider.getProviderName() + " ===");

        String sessionId = "test-session-" + System.currentTimeMillis();
        Session session = new JsonSession(tempSessionDir);

        // Phase 1: Create agent, have conversation, save state
        System.out.println("Phase 1: Creating agent and having conversation...");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent1 = provider.createAgent("SessionAgent", toolkit);

        Msg input = TestUtils.createUserMessage("User", "Remember this: My name is Alice.");
        Msg response1 = agent1.call(input).block(TEST_TIMEOUT);
        assertNotNull(response1, "Should receive response");

        int memoryBeforeSave = agent1.getMemory().getMessages().size();
        System.out.println("Memory size before save: " + memoryBeforeSave);

        // Save state
        agent1.saveTo(session, SimpleSessionKey.of(sessionId));
        System.out.println("Saved agent state to session: " + sessionId);

        // Phase 2: Create new agent, load state, verify
        System.out.println("Phase 2: Creating new agent and loading state...");

        ReActAgent agent2 = provider.createAgent("SessionAgent", toolkit);
        int memoryBeforeLoad = agent2.getMemory().getMessages().size();
        System.out.println("New agent memory size before load: " + memoryBeforeLoad);

        // Load state
        agent2.loadFrom(session, SimpleSessionKey.of(sessionId));
        int memoryAfterLoad = agent2.getMemory().getMessages().size();
        System.out.println("Memory size after load: " + memoryAfterLoad);

        // Verify state was restored
        assertEquals(
                memoryBeforeSave,
                memoryAfterLoad,
                "Memory should be restored for " + provider.getModelName());

        System.out.println("✓ Basic state persistence verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should restore context and continue conversation")
    void testCrossSessionContextRestoration(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Cross-Session Context Restoration with "
                        + provider.getProviderName()
                        + " ===");

        String sessionId = "context-session-" + System.currentTimeMillis();
        Session session = new JsonSession(tempSessionDir);

        // Phase 1: Set context
        System.out.println("Phase 1: Setting context...");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent1 = provider.createAgent("ContextAgent", toolkit);

        Msg contextMsg =
                TestUtils.createUserMessage(
                        "User", "Remember my name: ALPHA-7. It's very important.");
        Msg response1 = agent1.call(contextMsg).block(TEST_TIMEOUT);
        assertNotNull(response1, "Should acknowledge the context");

        // Save state
        agent1.saveTo(session, SimpleSessionKey.of(sessionId));
        System.out.println("Saved context to session");

        // Phase 2: Restore and verify context
        System.out.println("Phase 2: Restoring context and asking about it...");

        ReActAgent agent2 = provider.createAgent("ContextAgent", toolkit);
        agent2.loadFrom(session, SimpleSessionKey.of(sessionId));

        Msg followUpMsg = TestUtils.createUserMessage("User", "What is my name I told you?");
        Msg response2 = agent2.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(response2, "Should receive response");

        String responseText = TestUtils.extractTextContent(response2);
        System.out.println("Response: " + responseText);

        // Verify context was preserved
        assertTrue(
                ContentValidator.containsKeywords(response2, "ALPHA", "7"),
                "Should remember the secret code for " + provider.getModelName());

        System.out.println(
                "✓ Cross-session context restoration verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should handle non-existent session gracefully")
    void testNonExistentSession() {
        System.out.println("\n=== Test: Non-Existent Session Handling ===");

        Session session = new JsonSession(tempSessionDir);
        String nonExistentSessionId = "non-existent-session-" + System.currentTimeMillis();

        // Verify session doesn't exist
        assertFalse(
                session.exists(SimpleSessionKey.of(nonExistentSessionId)),
                "Non-existent session should return false for exists()");

        System.out.println("✓ Non-existent session handled gracefully");
    }

    @Test
    @DisplayName("Should create session directory if not exists")
    void testSessionDirectoryCreation() throws IOException {
        System.out.println("\n=== Test: Session Directory Creation ===");

        Path newDir = tempSessionDir.resolve("new-subdir");
        assertFalse(Files.exists(newDir), "Directory should not exist yet");

        Session session = new JsonSession(newDir);
        assertTrue(Files.exists(newDir), "JsonSession should create directory");

        System.out.println("✓ Session directory creation verified");
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should handle multiple save/load cycles")
    void testMultipleSaveLoadCycles(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Multiple Save/Load Cycles with "
                        + provider.getProviderName()
                        + " ===");

        String sessionId = "multi-cycle-" + System.currentTimeMillis();
        Session session = new JsonSession(tempSessionDir);
        Toolkit toolkit = new Toolkit();

        // Cycle 1
        System.out.println("Cycle 1: First conversation...");
        ReActAgent agent1 = provider.createAgent("CycleAgent", toolkit);
        Msg msg1 = TestUtils.createUserMessage("User", "Hello, this is message 1.");
        agent1.call(msg1).block(TEST_TIMEOUT);
        agent1.saveTo(session, SimpleSessionKey.of(sessionId));
        int sizeAfterCycle1 = agent1.getMemory().getMessages().size();
        System.out.println("Memory size after cycle 1: " + sizeAfterCycle1);

        // Cycle 2
        System.out.println("Cycle 2: Load and continue...");
        ReActAgent agent2 = provider.createAgent("CycleAgent", toolkit);
        agent2.loadFrom(session, SimpleSessionKey.of(sessionId));
        Msg msg2 = TestUtils.createUserMessage("User", "This is message 2.");
        agent2.call(msg2).block(TEST_TIMEOUT);
        agent2.saveTo(session, SimpleSessionKey.of(sessionId));
        int sizeAfterCycle2 = agent2.getMemory().getMessages().size();
        System.out.println("Memory size after cycle 2: " + sizeAfterCycle2);

        // Cycle 3: Verify all history is preserved
        System.out.println("Cycle 3: Load and verify history...");
        ReActAgent agent3 = provider.createAgent("CycleAgent", toolkit);
        agent3.loadFrom(session, SimpleSessionKey.of(sessionId));
        int sizeAfterCycle3Load = agent3.getMemory().getMessages().size();
        System.out.println("Memory size after cycle 3 load: " + sizeAfterCycle3Load);

        // Memory should grow across cycles
        assertTrue(
                sizeAfterCycle2 > sizeAfterCycle1,
                "Memory should grow between cycles for " + provider.getModelName());
        assertEquals(
                sizeAfterCycle2,
                sizeAfterCycle3Load,
                "Memory should be fully restored in cycle 3 for " + provider.getModelName());

        System.out.println(
                "✓ Multiple save/load cycles verified for " + provider.getProviderName());
    }
}
