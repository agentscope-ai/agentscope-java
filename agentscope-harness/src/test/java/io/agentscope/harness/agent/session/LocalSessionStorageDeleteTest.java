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
package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LocalSessionStorageDeleteTest {
    @TempDir Path directory;

    private static byte[] payload(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static SessionEvent event() {
        return new SessionEvent(1, "e", 1, 1, "run/start", "r", "t", true, "{}");
    }

    @Test
    void deletePathsRemovesThePrefixSubtreeAndNothingElse() {
        var storage = new LocalSessionStorage(directory.resolve("runtime"));
        String doomed = "agents/a/sessions/u/s1";
        String kept = "agents/a/sessions/u/s2";
        storage.compareAndSet(doomed + "/session.json", 0, payload("one"));
        storage.compareAndSet(doomed + "/inbox/session.json", 0, payload("two"));
        storage.compareAndSet(kept + "/session.json", 0, payload("keep"));

        storage.deletePaths(doomed);

        assertNull(storage.read(doomed + "/session.json"));
        assertNull(storage.read(doomed + "/inbox/session.json"));
        assertTrue(storage.listPaths(doomed).isEmpty());
        assertEquals("keep", new String(storage.read(kept + "/session.json").bytes()));
        // Behavior assertion, not a count: the POSIX backend leaves its CAS lock files in place,
        // so the exact remaining size is a backend detail.
        var remaining = storage.listPaths("agents/");
        assertTrue(remaining.contains(kept + "/session.json"));
        assertTrue(remaining.stream().noneMatch(p -> p.startsWith(doomed)));
    }

    @Test
    void deletePathsOnAMissingPrefixIsANoOp() {
        var storage = new LocalSessionStorage(directory.resolve("runtime"));
        storage.deletePaths("agents/a/sessions/u/missing");
        assertTrue(storage.listPaths("agents/").isEmpty());
        // A delete never creates anything, including the runtime root itself.
        assertFalse(Files.exists(directory.resolve("runtime")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void deletePathsRefusesSymlinkedSubtreesInsteadOfDeletingAroundThem() throws Exception {
        var storage = new LocalSessionStorage(directory.resolve("runtime"));
        String prefix = "agents/a/sessions/u/s1";
        storage.compareAndSet(prefix + "/session.json", 0, payload("data"));
        Path victim = directory.resolve("outside");
        Files.createFile(victim);
        Files.createSymbolicLink(directory.resolve("runtime").resolve(prefix + "/escape"), victim);

        assertThrows(SessionLogException.class, () -> storage.deletePaths(prefix));
        // Refused wholesale: the plain object inside the subtree is still there.
        assertEquals("data", new String(storage.read(prefix + "/session.json").bytes()));
        assertTrue(Files.exists(victim));
    }

    @Test
    void workspaceSessionLogStoreDeleteGoesThroughTheSessionNamespace() {
        var store = new WorkspaceSessionLogStore(new LocalFilesystem(directory.resolve("runtime")));
        var key = new SessionKey("u", "a", "s1");
        var other = new SessionKey("u", "a", "s2");
        var context = RuntimeContext.builder().userId("u").sessionId("s1").build();
        var log = store.open(key, context);
        var writer = log.acquire("w", Duration.ofMinutes(2));
        log.commit(writer, "b1", 0, List.of(event()));
        var otherLog = store.open(other, context);
        var otherWriter = otherLog.acquire("w2", Duration.ofMinutes(2));
        otherLog.commit(otherWriter, "b1", 0, List.of(event()));
        assertTrue(store.list(context).contains(key));

        store.delete(key, context);

        assertTrue(store.list(context).stream().noneMatch(k -> k.sessionId().equals("s1")));
        assertTrue(store.list(context).stream().anyMatch(k -> k.sessionId().equals("s2")));
        assertFalse(Files.exists(directory.resolve("runtime").resolve(key.storagePath())));
    }
}
