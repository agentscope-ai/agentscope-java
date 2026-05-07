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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RemoteFilesystemSpec;
import io.agentscope.harness.agent.store.InMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTreeMirrorTest {

    @TempDir Path workspace;

    private AbstractFilesystem buildFs(InMemoryStore store) {
        return new RemoteFilesystemSpec(store)
                .toFilesystem(workspace, "agent-a", List::of, () -> "user-1");
    }

    // -----------------------------------------------------------------------
    //  load(): local-only, cold-start restore when local file is absent
    // -----------------------------------------------------------------------

    @Test
    void load_restoresFromRemote_whenLocalFilesMissing() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s1.jsonl");

        // Machine A writes and flushes (mirrors to remote)
        SessionTree writer = new SessionTree(context, workspace, fs);
        writer.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hello", null));
        writer.flush();
        awaitMirror();

        // Delete local files — simulate cold-start on a new machine
        Files.deleteIfExists(context);
        Files.deleteIfExists(writer.getLogFile());

        // Machine B: load() alone should restore from remote (cold-start path)
        SessionTree reader = new SessionTree(context, workspace, fs);
        reader.load();

        assertEquals(1, reader.size(), "load() should restore from remote when local is absent");
        assertTrue(Files.isRegularFile(context), "local file should be recreated");
    }

    @Test
    void load_readsLocalOnly_whenLocalFileExists() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s1b.jsonl");

        // Write two entries; mirror first entry to remote, keep second local-only
        SessionTree t1 = new SessionTree(context, workspace, fs);
        t1.append(new SessionEntry.MessageEntry(null, null, null, "USER", "entry-1", null));
        t1.flush();
        awaitMirror(); // remote has entry-1

        SessionTree t2 = new SessionTree(context, workspace, fs);
        t2.load(); // local has entry-1
        t2.append(new SessionEntry.MessageEntry(null, null, null, "ASSISTANT", "entry-2", null));
        t2.flush(); // local has entry-1+2; async mirror not awaited

        // A new SessionTree.load() on the same local file should see both entries immediately
        // (local has them) WITHOUT touching remote.
        SessionTree t3 = new SessionTree(context, workspace, fs);
        t3.load();
        assertEquals(2, t3.size(), "load() should read both entries from local cache");
    }

    // -----------------------------------------------------------------------
    //  syncFromRemote(): cross-machine handoff
    // -----------------------------------------------------------------------

    @Test
    void syncFromRemote_mergesRemoteAheadEntries_intoStaleLocal() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s2.jsonl");

        // Round 1 — Machine A: write, flush, mirror to remote
        SessionTree machineA = new SessionTree(context, workspace, fs);
        machineA.append(new SessionEntry.MessageEntry(null, null, null, "USER", "round-1", null));
        machineA.flush();
        awaitMirror(); // remote: round-1

        // Simulate machine B having a stale local: only round-1 on disk.
        // Machine A then writes round-2 and mirrors.
        SessionTree machineA2 = new SessionTree(context, workspace, fs);
        machineA2.load();
        machineA2.append(
                new SessionEntry.MessageEntry(null, null, null, "ASSISTANT", "round-2", null));
        machineA2.flush();
        awaitMirror(); // remote: round-1 + round-2

        // Truncate local to only round-1 (simulate stale machine B)
        List<String> lines = Files.readAllLines(context);
        Files.writeString(context, lines.get(0) + "\n");

        // Machine B: load() sees local (stale, round-1 only)
        SessionTree machineB = new SessionTree(context, workspace, fs);
        machineB.load();
        assertEquals(1, machineB.size(), "load() should only see local (stale) content");

        // syncFromRemote() picks up round-2 from remote
        machineB.syncFromRemote();
        assertEquals(2, machineB.size(), "syncFromRemote() should union-merge round-2 from remote");
    }

    @Test
    void syncFromRemote_preservesLocalOnlyEntries_notYetPushed() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s3.jsonl");

        // Write entry-1 and mirror
        SessionTree t1 = new SessionTree(context, workspace, fs);
        t1.append(new SessionEntry.MessageEntry(null, null, null, "USER", "entry-1", null));
        t1.flush();
        awaitMirror(); // remote: entry-1

        // Write entry-2 locally but don't wait for mirror (still in-flight)
        SessionTree t2 = new SessionTree(context, workspace, fs);
        t2.load();
        t2.append(new SessionEntry.MessageEntry(null, null, null, "ASSISTANT", "entry-2", null));
        t2.flush(); // local: entry-1+2; remote may still only have entry-1

        // Load a fresh tree — local has entry-1+2
        SessionTree t3 = new SessionTree(context, workspace, fs);
        t3.load();
        assertEquals(2, t3.size());

        // syncFromRemote() — remote has entry-1 only; local-only entry-2 must survive
        t3.syncFromRemote();
        assertEquals(2, t3.size(), "local-only entry-2 must survive union-merge");
        assertTrue(
                t3.getMessageEntries().stream().anyMatch(m -> "entry-2".equals(m.getContent())),
                "entry-2 must be present after sync");
    }

    @Test
    void syncFromRemote_isNoOp_whenFilesystemIsNull() throws Exception {
        Path context = workspace.resolve("agents/agent-a/sessions/s4.jsonl");
        SessionTree tree = new SessionTree(context, workspace, null);
        tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "local", null));
        tree.flush();

        SessionTree reader = new SessionTree(context, workspace, null);
        reader.load();
        reader.syncFromRemote(); // must not throw
        assertEquals(1, reader.size());
    }

    // -----------------------------------------------------------------------
    //  flush(): local write is synchronous, remote mirror is async
    // -----------------------------------------------------------------------

    @Test
    void flush_localWriteCompletesImmediately() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs = buildFs(store);
        Path context = workspace.resolve("agents/agent-a/sessions/s5.jsonl");

        SessionTree tree = new SessionTree(context, workspace, fs);
        tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hi", null));
        tree.flush();

        assertTrue(
                Files.isRegularFile(context),
                "local context file must exist immediately after flush()");
        assertTrue(
                Files.isRegularFile(tree.getLogFile()),
                "local log file must exist immediately after flush()");
    }

    // -----------------------------------------------------------------------
    //  Helper
    // -----------------------------------------------------------------------

    private static void awaitMirror() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(300);
    }
}
