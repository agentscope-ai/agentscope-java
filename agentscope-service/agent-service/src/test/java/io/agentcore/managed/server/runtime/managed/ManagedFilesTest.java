/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.web.managed.AgentSessionInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedFilesTest {
    @TempDir private Path managedCheckpointRoot;

    private final ManagedScope scope = new ManagedScope("t", "n", "files-" + UUID.randomUUID());
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] content = "document-content".getBytes(StandardCharsets.UTF_8);
    private DataSource source;
    private ManagedAdmissionStore admission;
    private ManagedJournalStore journal;
    private ManagedFiles files;

    protected DataSource database() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:files-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @BeforeEach
    void setup() {
        source = database();
        ManagedAdmissionStore.initialize(source);
        admission = ProjectionTestSupport.admission(source, mapper);
        journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        files = journal.files();
        admission.createSession(scope, "{}");
    }

    private String upload(String actor) {
        return (String)
                files.upload(scope, actor, "key", "document.txt", "text/plain", content)
                        .get("file_id");
    }

    @Test
    void replicasReadOriginalBytesAndRetriesAreScopedByActorAndContent() {
        String id = upload("first");
        assertEquals(id, upload("first"));
        assertArrayEquals(
                content,
                ProjectionTestSupport.journal(
                                source,
                                mapper,
                                new ManagedCheckpointObjects(mapper, managedCheckpointRoot))
                        .files()
                        .content(scope, id));
        assertEquals(1, ProjectionTestSupport.project(journal).publicHead(scope));
        assertFalse(files.metadata(scope, id).containsKey("content"));
        assertFalse(
                ProjectionTestSupport.project(journal)
                        .publicEvents(scope, 0, 10)
                        .get(0)
                        .payload()
                        .contains("document-content"));
        assertNotEquals(id, upload("second"));
        assertEquals(
                "file_content_conflict",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        files.upload(
                                                scope,
                                                "first",
                                                "key",
                                                "renamed.txt",
                                                "text/plain",
                                                content))
                        .code());
        var other = new ManagedScope("other-tenant", "n", scope.sessionId());
        admission.createSession(other, "{}");
        assertEquals(
                "file_not_found",
                assertThrows(ManagedStateException.class, () -> files.content(other, id)).code());
        assertEquals(1, ((List<?>) files.list(scope, 0, 1).get("data")).size());
        assertTrue((Boolean) files.list(scope, 0, 1).get("has_more"));
        assertFalse((Boolean) files.list(scope, 1, 1).get("has_more"));
    }

    @Test
    void inputKeepsImmutableReferencesAndHarnessRuntimeResolvesNativeContent() {
        String id = upload("first");
        var input =
                Map.<String, Object>of(
                        "input",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        List.of(Map.of("type", "file", "file_id", id)))));
        files.validateInput(scope, input);
        var resolved = files.resolveInput(scope, input);
        assertTrue(input.toString().contains("file_id"));
        assertFalse(resolved.toString().contains("file_id"));
        assertEquals(1, AgentSessionInput.messages(resolved, "turn").size());
        var other = new ManagedScope("t", "different-namespace", scope.sessionId());
        admission.createSession(other, "{}");
        assertThrows(ManagedStateException.class, () -> files.validateInput(other, input));
    }

    @Test
    void publicationFailureRollsBackBytesAndClosureRejectsNewFiles() {
        var unavailable = spy(journal);
        doThrow(new IllegalStateException("Event write failed"))
                .when(unavailable)
                .resourceEvent(any(), anyString(), anyString(), any());
        var transactional = new ManagedFiles(source, unavailable);
        assertThrows(
                IllegalStateException.class,
                () ->
                        transactional.upload(
                                scope, "actor", "failed", "document.txt", "text/plain", content));
        assertTrue(((List<?>) files.list(scope, 0, 10).get("data")).isEmpty());
        String id = upload("first");
        new ManagedLifecycles(source).stage(scope, "deleted", "actor", "delete");
        assertThrows(ManagedStateException.class, () -> upload("new-actor"));
        files.discardDeletedContent(scope);
        assertEquals(
                "file_content_removed",
                assertThrows(ManagedStateException.class, () -> files.content(scope, id)).code());
        assertEquals(id, files.metadata(scope, id).get("file_id"));
    }

    @Test
    void publicMediaReferencesMatchScopedContentAndNeverExposeBase64() throws Exception {
        String id = upload("first");
        var source =
                Map.of(
                        "type",
                        "base64",
                        "media_type",
                        "text/plain",
                        "data",
                        Base64.getEncoder().encodeToString(content));
        var payload =
                Map.<String, Object>of(
                        "item",
                        Map.of(
                                "content",
                                List.of(Map.of("type", "data", "id", id, "source", source))));
        String projected = mapper.writeValueAsString(files.publicPayload(scope, payload));
        assertTrue(projected.contains("\"type\":\"file\""));
        assertTrue(projected.contains(id));
        assertFalse(projected.contains("base64"));
        assertFalse(projected.contains((String) source.get("data")));
        var unknown = new ManagedScope("other-tenant", "n", scope.sessionId());
        admission.createSession(unknown, "{}");
        String hidden = mapper.writeValueAsString(files.publicPayload(unknown, payload));
        assertTrue(hidden.contains("content_omitted"));
        assertFalse(hidden.contains("download_url"));
        assertFalse(hidden.contains((String) source.get("data")));
        var forged =
                Map.<String, Object>of(
                        "content",
                        List.of(
                                Map.of(
                                        "type",
                                        "data",
                                        "id",
                                        id,
                                        "source",
                                        Map.of(
                                                "type",
                                                "base64",
                                                "media_type",
                                                "text/plain",
                                                "data",
                                                "eA=="))));
        assertTrue(
                mapper.writeValueAsString(files.publicPayload(scope, forged))
                        .contains("content_omitted"));
        String imageBytes =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+yAaQAAAAASUVORK5CYII=";
        String image =
                (String)
                        files.upload(
                                        scope,
                                        "actor",
                                        "image",
                                        "image.png",
                                        "image/png",
                                        Base64.getDecoder().decode(imageBytes))
                                .get("file_id");
        var legacyMedia =
                Map.<String, Object>of(
                        "content",
                        List.of(
                                Map.of(
                                        "type",
                                        "image",
                                        "source",
                                        Map.of(
                                                "type",
                                                "base64",
                                                "media_type",
                                                "image/png",
                                                "data",
                                                imageBytes))));
        assertTrue(
                mapper.writeValueAsString(files.publicPayload(scope, legacyMedia)).contains(image));
    }

    @Test
    void oversizedUploadIsRejectedBeforeAnyFileFact() {
        assertEquals(
                "file_too_large",
                assertThrows(
                                ManagedStateException.class,
                                () ->
                                        files.upload(
                                                scope,
                                                "actor",
                                                "large",
                                                "large.txt",
                                                "text/plain",
                                                new byte[ManagedFiles.MAX_BYTES + 1]))
                        .code());
        assertEquals(0, ProjectionTestSupport.project(journal).publicHead(scope));
    }
}
