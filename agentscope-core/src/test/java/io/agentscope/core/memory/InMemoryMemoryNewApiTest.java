/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for InMemoryMemory's new StateModule API (saveTo/loadFrom). */
@DisplayName("InMemoryMemory New StateModule API Tests")
class InMemoryMemoryNewApiTest {

    private InMemorySession session;
    private SessionKey sessionKey;

    @BeforeEach
    void setUp() {
        session = new InMemorySession();
        sessionKey = SimpleSessionKey.of("test_session");
    }

    @Nested
    @DisplayName("saveTo() and loadFrom()")
    class SaveToLoadFromTests {

        @Test
        @DisplayName("Should save and load messages via saveTo/loadFrom")
        void testSaveToLoadFrom() {
            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("Hello"));
            memory.addMessage(createAssistantMsg("Hi there!"));

            memory.saveTo(session, sessionKey);

            InMemoryMemory loadedMemory = new InMemoryMemory();
            loadedMemory.loadFrom(session, sessionKey);

            assertEquals(2, loadedMemory.getMessages().size());
            assertEquals("Hello", getTextContent(loadedMemory.getMessages().get(0)));
            assertEquals("Hi there!", getTextContent(loadedMemory.getMessages().get(1)));
        }

        @Test
        @DisplayName("Should handle empty memory")
        void testEmptyMemory() {
            InMemoryMemory memory = new InMemoryMemory();
            memory.saveTo(session, sessionKey);

            InMemoryMemory loadedMemory = new InMemoryMemory();
            loadedMemory.loadFrom(session, sessionKey);

            assertTrue(loadedMemory.getMessages().isEmpty());
        }

        @Test
        @DisplayName("Should handle incremental saves")
        void testIncrementalSave() {
            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("Message 1"));
            memory.saveTo(session, sessionKey);

            memory.addMessage(createUserMsg("Message 2"));
            memory.addMessage(createUserMsg("Message 3"));
            memory.saveTo(session, sessionKey);

            InMemoryMemory loadedMemory = new InMemoryMemory();
            loadedMemory.loadFrom(session, sessionKey);

            assertEquals(3, loadedMemory.getMessages().size());
            assertEquals("Message 1", getTextContent(loadedMemory.getMessages().get(0)));
            assertEquals("Message 2", getTextContent(loadedMemory.getMessages().get(1)));
            assertEquals("Message 3", getTextContent(loadedMemory.getMessages().get(2)));
        }

        @Test
        @DisplayName("Should replace loaded memory contents on loadFrom")
        void testLoadFromReplacesContents() {
            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("Original message"));
            memory.saveTo(session, sessionKey);

            InMemoryMemory loadedMemory = new InMemoryMemory();
            loadedMemory.addMessage(createUserMsg("Pre-existing message"));
            loadedMemory.loadFrom(session, sessionKey);

            assertEquals(1, loadedMemory.getMessages().size());
            assertEquals("Original message", getTextContent(loadedMemory.getMessages().get(0)));
        }
    }

    @Nested
    @DisplayName("loadIfExists()")
    class LoadIfExistsTests {

        @Test
        @DisplayName("Should return true and load when session exists")
        void testLoadIfExistsTrue() {
            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("Test message"));
            memory.saveTo(session, sessionKey);

            InMemoryMemory loadedMemory = new InMemoryMemory();
            boolean exists = loadedMemory.loadIfExists(session, sessionKey);

            assertTrue(exists);
            assertEquals(1, loadedMemory.getMessages().size());
        }

        @Test
        @DisplayName("Should return false when session doesn't exist")
        void testLoadIfExistsFalse() {
            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("Pre-existing"));

            // No session saved, so loadIfExists should return false
            boolean exists = memory.loadIfExists(session, SimpleSessionKey.of("non_existent"));

            // loadIfExists returns false when session doesn't exist
            assertFalse(exists);
            // loadFrom is NOT called when session doesn't exist, so memory is preserved
            assertEquals(1, memory.getMessages().size());
            assertEquals("Pre-existing", getTextContent(memory.getMessages().get(0)));
        }
    }

    @Nested
    @DisplayName("Integration with JsonSession")
    class JsonSessionIntegrationTests {

        @TempDir Path tempDir;

        @Test
        @DisplayName("Should work with JsonSession for persistence")
        void testWithJsonSession() {
            io.agentscope.core.session.JsonSession jsonSession =
                    new io.agentscope.core.session.JsonSession(tempDir);
            SessionKey key = SimpleSessionKey.of("json_session_test");

            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("Hello from JSON"));
            memory.addMessage(createAssistantMsg("Response from JSON"));
            memory.saveTo(jsonSession, key);

            InMemoryMemory loadedMemory = new InMemoryMemory();
            loadedMemory.loadFrom(jsonSession, key);

            assertEquals(2, loadedMemory.getMessages().size());
            assertEquals("Hello from JSON", getTextContent(loadedMemory.getMessages().get(0)));
            assertEquals("Response from JSON", getTextContent(loadedMemory.getMessages().get(1)));
        }

        @Test
        @DisplayName("Should handle incremental saves with JsonSession")
        void testIncrementalWithJsonSession() {
            io.agentscope.core.session.JsonSession jsonSession =
                    new io.agentscope.core.session.JsonSession(tempDir);
            SessionKey key = SimpleSessionKey.of("incremental_test");

            InMemoryMemory memory = new InMemoryMemory();
            memory.addMessage(createUserMsg("First message"));
            memory.saveTo(jsonSession, key);

            memory.addMessage(createUserMsg("Second message"));
            memory.saveTo(jsonSession, key);

            memory.addMessage(createUserMsg("Third message"));
            memory.saveTo(jsonSession, key);

            InMemoryMemory loadedMemory = new InMemoryMemory();
            loadedMemory.loadFrom(jsonSession, key);

            assertEquals(3, loadedMemory.getMessages().size());
        }
    }

    // Helper methods

    private Msg createUserMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createAssistantMsg(String text) {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private String getTextContent(Msg msg) {
        return ((TextBlock) msg.getFirstContentBlock()).getText();
    }
}
