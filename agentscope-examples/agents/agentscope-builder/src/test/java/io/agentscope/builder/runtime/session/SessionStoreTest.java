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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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
    void localSessionStoreStartsEmptyForMissingAndBlankFiles() throws IOException {
        LocalSessionStore missingStore =
                new LocalSessionStore(tempDir.resolve("missing-sessions.json"));

        missingStore.load();

        assertEquals(0, missingStore.size());

        Path blankFile = tempDir.resolve("blank-sessions.json");
        Files.writeString(blankFile, "  \n");
        LocalSessionStore blankStore = new LocalSessionStore(blankFile);

        blankStore.load();

        assertEquals(0, blankStore.size());
    }

    @Test
    void localSessionStoreUpsertsAndRemovesEntriesDurably() {
        Path storeFile = tempDir.resolve("sessions.json");
        LocalSessionStore store = new LocalSessionStore(storeFile);
        String firstKey = "agent:main:main:session-1";
        String secondKey = "agent:main:main:session-2";

        store.save(entry(firstKey, 100L));
        store.save(entry(firstKey, 300L));
        store.save(entry(secondKey, 200L));

        assertEquals(2, store.size());
        assertEquals(300L, store.get(firstKey).orElseThrow().lastActivityMs());

        store.remove(firstKey);

        LocalSessionStore reloaded = new LocalSessionStore(storeFile);
        reloaded.load();
        assertEquals(1, reloaded.size());
        assertTrue(reloaded.get(firstKey).isEmpty());
        assertTrue(reloaded.get(secondKey).isPresent());
        assertFalse(Files.exists(storeFile.resolveSibling("sessions.json.tmp")));
    }

    @Test
    void localSessionStoreIgnoresUnknownKeysWithoutCreatingAFile() {
        Path storeFile = tempDir.resolve("sessions.json");
        LocalSessionStore store = new LocalSessionStore(storeFile);

        store.touch("missing", 200L);
        store.remove("missing");

        assertEquals(0, store.size());
        assertFalse(Files.exists(storeFile));
    }

    @Test
    void localSessionStoreRecoversAsEmptyFromMalformedJson() throws IOException {
        Path storeFile = tempDir.resolve("sessions.json");
        LocalSessionStore store = new LocalSessionStore(storeFile);
        store.save(entry("agent:main:main:session-1", 100L));
        Files.writeString(storeFile, "{not-json");

        assertDoesNotThrow(store::load);

        assertEquals(0, store.size());
        assertTrue(store.listAll().isEmpty());
    }

    @Test
    void localSessionStoreIgnoresUnknownJsonFields() throws IOException {
        Path storeFile = tempDir.resolve("sessions.json");
        Files.writeString(
                storeFile,
                """
                {
                  "agent:main:main:session-1": {
                    "sessionKey": "agent:main:main:session-1",
                    "agentId": "main",
                    "sessionId": "session-1",
                    "label": "label",
                    "kind": "main",
                    "spawnDepth": 0,
                    "createdAtMs": 100,
                    "lastActivityMs": 200,
                    "sessionFilePath": "/tmp/session-1.json",
                    "gateKey": "gate-1",
                    "userId": "user-1",
                    "futureField": "ignored"
                  }
                }
                """);
        LocalSessionStore store = new LocalSessionStore(storeFile);

        store.load();

        SessionEntry restored =
                store.get("agent:main:main:session-1").orElseThrow().toSessionEntry();
        assertEquals("main", restored.agentId());
        assertEquals(200L, restored.lastActivityMs());
        assertEquals("user-1", restored.userId());
    }

    @Test
    void localSessionStoreListAllReturnsAnImmutableSnapshot() {
        LocalSessionStore store = new LocalSessionStore(tempDir.resolve("sessions.json"));
        store.save(entry("agent:main:main:session-1", 100L));

        Collection<SessionStore.StoredEntry> snapshot = store.listAll();
        store.save(entry("agent:main:main:session-2", 200L));

        assertEquals(1, snapshot.size());
        assertEquals(2, store.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void storedEntryRoundTripsAllSessionMetadata() {
        SessionEntry original =
                new SessionEntry(
                        "agent:main:subagent:session-2",
                        "worker",
                        "session-2",
                        "worker-label",
                        SessionKind.SUBAGENT,
                        "agent:main:main:session-1",
                        2,
                        100L,
                        200L,
                        "/tmp/session-2.json",
                        "run-2",
                        "gate-2",
                        "user-2");

        SessionEntry restored = SessionStore.StoredEntry.from(original).toSessionEntry();

        assertEquals(original, restored);
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
