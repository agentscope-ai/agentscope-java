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
package io.agentscope.extensions.jdbc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the actual 2.0.x workspace context/log and immutable transcript segment paths. User
 * isolation belongs to the store namespace, while workspace-relative paths are item keys.
 */
@DisplayName("JdbcStore journal key lengths")
class JdbcStoreJournalKeyLengthTest {

    private static final int ITEM_KEY_WIDTH = 2048;
    private static final String WRITER_ID = "321cf6ad".repeat(8);
    private static final List<String> NAMESPACE = List.of("agents", "user-1");
    private static final RuntimeContext RC = RuntimeContext.builder().userId("user-1").build();

    @TempDir Path workspace;

    private JdbcStore store;
    private RemoteFilesystem filesystem;
    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        DataSource ds = H2TestSupport.createDataSource("jdbc_store_journal_key_length");
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        store = JdbcStore.builder(ds).dialect(dialect).build();
        filesystem = new RemoteFilesystem(store, rc -> List.of("agents", rc.getUserId()));
        manager = new WorkspaceManager(workspace, filesystem, null);
    }

    @Test
    @DisplayName("workspace context and full-history log keys support CAS beyond 255 characters")
    void everyWorkspaceJournalKeyShapeIsStored() {
        String agentId = "agent_" + "a".repeat(120);
        String sessionId = "sess_" + "s".repeat(140);
        List<Path> paths =
                List.of(
                        manager.resolveSessionContextFile(RC, agentId, sessionId),
                        manager.resolveSessionLogFile(RC, agentId, sessionId));

        for (Path path : paths) {
            String key = workspace.relativize(path).toString().replace('\\', '/');
            assertKeyFitsWidenedColumn(key);
            assertTrue(filesystem.write(RC, key, "{\"seq\":1}").isSuccess());
            assertNotNull(store.get(NAMESPACE, key));
            assertEquals(key, store.get(NAMESPACE, key).key());
            assertEquals(1L, store.get(NAMESPACE, key).version());
            assertTrue(filesystem.edit(RC, key, "1", "2", false).isSuccess());
            assertEquals(2L, store.get(NAMESPACE, key).version());
            assertEquals("{\"seq\":2}", filesystem.read(RC, key, 0, 0).fileData().content());
        }
    }

    @Test
    @DisplayName("the actual transcript segment path exceeds the old item_key width")
    void transcriptSegmentKeyExceedsTheOldWidth() throws Exception {
        TranscriptRef ref =
                new TranscriptRef("tenant", "agent_" + "a".repeat(100), "sess_" + "s".repeat(100));
        assertTranscriptRoundTrip(ref);
    }

    @Test
    @DisplayName("255-character identity components still fit the widened item_key")
    void longIdentityComponentsFit() throws Exception {
        String widest = "x".repeat(255);
        assertTranscriptRoundTrip(new TranscriptRef(widest, widest, widest));
    }

    private void assertTranscriptRoundTrip(TranscriptRef ref) throws Exception {
        ObjectStoreTranscriptStore transcripts = new ObjectStoreTranscriptStore(filesystem, RC, "");
        String content = "{\"seq\":1}\n";
        String key =
                transcripts.appendSegment(
                        ref, 1L, 1L, WRITER_ID, content.getBytes(StandardCharsets.UTF_8));

        assertEquals(ref.prefix() + "/events/1-1-" + WRITER_ID + ".jsonl", key);
        assertKeyFitsWidenedColumn(key);
        assertNotNull(store.get(NAMESPACE, key));
        assertEquals(key, store.get(NAMESPACE, key).key());
        assertEquals(1L, store.get(NAMESPACE, key).version());
        try (var in = transcripts.readSegment(key)) {
            assertEquals(content, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void assertKeyFitsWidenedColumn(String key) {
        assertTrue(key.length() > 255, "regression key must exceed the old width: " + key.length());
        assertTrue(
                key.length() <= ITEM_KEY_WIDTH, "key must fit the widened width: " + key.length());
    }
}
