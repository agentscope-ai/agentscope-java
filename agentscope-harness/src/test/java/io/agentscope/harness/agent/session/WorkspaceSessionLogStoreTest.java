package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.tool.SessionSearchTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSessionLogStoreTest {
    @TempDir Path directory;
    private final SessionKey key = new SessionKey("u", "agent", "session");

    private SessionEvent event() {
        return new SessionEvent(1, "e", 1, 1, "run/start", "r", "t", true, "{}");
    }

    @Test
    void localLogSurvivesIndependentBackendInstancesAndPreservesOwnership() {
        var first =
                new WorkspaceSessionLogStore(new LocalFilesystem(directory))
                        .open(key, RuntimeContext.empty());
        var writer = first.acquire("owner", Duration.ofMinutes(2));
        first.commit(writer, "batch", 0, List.of(event()));
        var second =
                new WorkspaceSessionLogStore(new LocalFilesystem(directory))
                        .open(key, RuntimeContext.empty());
        assertEquals(List.of(event()), second.readAfter(0, 10));
        assertThrows(
                SessionLogException.class, () -> second.acquire("other", Duration.ofMinutes(2)));
        first.release(writer);
        assertNotNull(second.acquire("other", Duration.ofMinutes(2)));
    }

    @Test
    void distributedWorkspaceRetainsItsRuntimeNamespace() {
        var backend = new InMemoryStore();
        var filesystem =
                new RemoteFilesystem(backend, rc -> List.of("tenant", (String) rc.get("tenant")));
        var store = new WorkspaceSessionLogStore(filesystem);
        var a = RuntimeContext.builder().put("tenant", "a").build();
        var b = RuntimeContext.builder().put("tenant", "b").build();
        var first = store.open(key, a);
        var writer = first.acquire("owner", Duration.ofMinutes(2));
        first.commit(writer, "batch", 0, List.of(event()));
        assertEquals(1, store.open(key, a).head().seq());
        assertEquals(0, store.open(key, b).head().seq());
    }

    @Test
    void agentFilesystemCannotOverwriteRuntimeJournal() {
        var filesystem = new LocalFilesystem(directory);
        assertThrows(
                SecurityException.class,
                () ->
                        filesystem.write(
                                RuntimeContext.empty(),
                                ".agentscope-runtime/head.json",
                                "corrupt"));
    }

    @Test
    void nativeDiscoveryReadsSharedBackendAndPreservesUserIsolation() {
        var backend = new InMemoryStore();
        var first =
                new WorkspaceSessionLogStore(
                        new RemoteFilesystem(backend, rc -> List.of("tenant")));
        var other =
                new WorkspaceSessionLogStore(
                        new RemoteFilesystem(backend, rc -> List.of("tenant")));
        var user = RuntimeContext.builder().userId("u").build();
        var unknown = RuntimeContext.builder().userId("other").build();
        assertTrue(first.list(user).isEmpty());
        first.open(key, user); // Reader opens must not create discoverable phantom sessions.
        assertTrue(other.list(user).isEmpty());
        var log = first.open(key, user);
        var writer = log.acquire("owner", Duration.ofMinutes(2));
        log.commit(writer, "batch", 0, List.of(event()));
        log.release(writer);
        assertEquals(List.of(key), other.list(user));
        assertTrue(other.list(unknown).isEmpty());
    }

    @Test
    void localHistorySearchUsesNativeMessagesWithoutTranscriptFiles() {
        var store = new WorkspaceSessionLogStore(new LocalFilesystem(directory));
        var rc = RuntimeContext.builder().userId("u").build();
        var log = store.open(key, rc);
        var writer = log.acquire("owner", Duration.ofMinutes(2));
        var message =
                Msg.builder()
                        .id("message-id")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Native history marker").build())
                        .build();
        var messageEvent =
                new SessionEvent(
                        1,
                        "message",
                        1,
                        1,
                        "message/user",
                        "run",
                        "turn",
                        true,
                        JsonUtils.getJsonCodec().toJson(Map.of("message", message)));
        log.commit(writer, "messages", 0, List.of(messageEvent));
        log.release(writer);
        var reader = new WorkspaceSessionLogStore(new LocalFilesystem(directory));
        assertEquals(List.of(key), reader.list(rc));
        var tool = new SessionSearchTool(reader);
        assertTrue(tool.sessionSearch(rc, "history marker", "agent", 10).contains("message-id"));
        assertTrue(
                tool.sessionHistory(rc, "agent", "session", 20).contains("Native history marker"));
        assertTrue(tool.sessionList(rc, "agent").contains("session"));
    }

    @Test
    void routedAndBakedFilesystemsPreserveTheNativeStorageCapability() {
        var backend = new InMemoryStore();
        var remote = new RemoteFilesystem(backend, rc -> List.of("tenant", rc.getUserId()));
        var rc = RuntimeContext.builder().userId("u").sessionId("session").build();
        var routed =
                new RoutedSandboxFilesystem(
                        mock(AbstractSandboxFilesystem.class), Map.of("agents/", remote));
        var writerLog = new WorkspaceSessionLogStore(routed).open(key, rc);
        var writer = writerLog.acquire("owner", Duration.ofMinutes(2));
        writerLog.commit(writer, "batch", 0, List.of(event()));
        writerLog.release(writer);
        var baked = new WorkspaceSessionLogStore(new BakedContextFilesystem(remote, rc));
        assertEquals(1, baked.open(key, RuntimeContext.empty()).head().seq());
        assertEquals(List.of(key), baked.list(rc));
        assertTrue(baked.list(RuntimeContext.builder().userId("another").build()).isEmpty());
    }

    @Test
    void sessionScopedDiscoveryDoesNotCrossTheCurrentNamespace() {
        var backend = new InMemoryStore();
        var store =
                new WorkspaceSessionLogStore(
                        new RemoteFilesystem(backend, rc -> List.of("session", rc.getSessionId())));
        var a = RuntimeContext.builder().userId("u").sessionId("a").build();
        var b = RuntimeContext.builder().userId("u").sessionId("b").build();
        var keyA = new SessionKey("u", "agent", "a");
        var keyB = new SessionKey("u", "agent", "b");
        for (var context : List.of(a, b)) {
            var log = store.open(new SessionKey("u", "agent", context.getSessionId()), context);
            var writer = log.acquire("owner", Duration.ofMinutes(2));
            log.commit(writer, "batch", 0, List.of(event()));
            log.release(writer);
        }
        assertEquals(List.of(keyA), store.list(a));
        assertEquals(List.of(keyB), store.list(b));
        assertEquals(1, store.open(keyB, b).head().seq());
    }
}
