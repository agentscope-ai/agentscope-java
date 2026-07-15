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
package io.agentscope.builder.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

    @TempDir Path tempDir;

    @Test
    void localSessionStorePersistsSessionsJson() {
        Path storeFile = tempDir.resolve("sessions.json");
        LocalSessionStore store = new LocalSessionStore(storeFile);

        store.load();
        store.save(entry("agent:main:main:session-1", 100L));
        store.touch("agent:main:main:session-1", 200L);

        LocalSessionStore reloaded = new LocalSessionStore(storeFile);
        reloaded.load();

        assertTrue(Files.isRegularFile(storeFile));
        assertEquals(1, reloaded.size());
        SessionEntry restored =
                reloaded.get("agent:main:main:session-1").orElseThrow().toSessionEntry();
        assertEquals("main", restored.agentId());
        assertEquals(200L, restored.lastActivityMs());
        assertEquals("user-1", restored.userId());
    }

    @Test
    void remoteSessionStoreRejectsMutationsUntilConcreteBackendIsProvided() {
        RemoteSessionStore store = new RemoteSessionStore();

        store.load();

        assertThrows(
                UnsupportedOperationException.class,
                () -> store.save(entry("agent:main:main:session-1", 100L)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> store.touch("agent:main:main:session-1", 200L));
        assertThrows(
                UnsupportedOperationException.class,
                () -> store.remove("agent:main:main:session-1"));
        assertEquals(0, store.size());
        assertTrue(store.listAll().isEmpty());
        assertTrue(store.get("agent:main:main:session-1").isEmpty());
    }

    private static SessionEntry entry(String sessionKey, long lastActivityMs) {
        return new SessionEntry(
                sessionKey,
                "main",
                "session-1",
                "label",
                SessionKind.MAIN,
                null,
                0,
                100L,
                lastActivityMs,
                "/tmp/session-1.json",
                null,
                "gate-1",
                "user-1");
    }
}
