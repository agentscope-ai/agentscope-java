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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link CompositeFilesystem#uploadFiles} faithfully propagates each backend's
 * per-file response (success or failure) instead of wrapping every result in success.
 */
class CompositeFilesystemTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @Test
    void uploadFiles_propagatesBackendFailure() {
        AbstractFilesystem failingBackend = mock(AbstractFilesystem.class);
        when(failingBackend.uploadFiles(any(), anyList()))
                .thenReturn(List.of(FileUploadResponse.fail("/notes.md", "disk full")));

        AbstractFilesystem defaultBackend = mock(AbstractFilesystem.class);

        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("/memories/", failingBackend));

        List<FileUploadResponse> resp =
                composite.uploadFiles(
                        CTX,
                        List.of(
                                Map.entry(
                                        "/memories/notes.md",
                                        "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, resp.size());
        FileUploadResponse r = resp.get(0);
        assertFalse(r.isSuccess(), "backend failure must propagate to caller");
        assertEquals(
                "/memories/notes.md",
                r.path(),
                "response path must be the caller's view, not the backend-internal path");
        assertEquals("disk full", r.error(), "backend error message must propagate verbatim");
    }

    @Test
    void uploadFiles_propagatesBackendSuccess() {
        AbstractFilesystem backend = mock(AbstractFilesystem.class);
        when(backend.uploadFiles(any(), anyList()))
                .thenReturn(List.of(FileUploadResponse.success("/notes.md")));

        AbstractFilesystem defaultBackend = mock(AbstractFilesystem.class);

        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("/memories/", backend));

        List<FileUploadResponse> resp =
                composite.uploadFiles(
                        CTX,
                        List.of(
                                Map.entry(
                                        "/memories/notes.md",
                                        "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, resp.size());
        FileUploadResponse r = resp.get(0);
        assertTrue(r.isSuccess());
        assertEquals("/memories/notes.md", r.path());
    }
}
