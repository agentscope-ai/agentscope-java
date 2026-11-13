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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OverlayAtomicTextFileTest {
    @TempDir Path directory;
    final RuntimeContext rc = RuntimeContext.empty();

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t", "持久事实 😀"})
    void inheritsRawContentFromReadOnlyLowerAndWritesOnlyUpper(String content) {
        var lower = mock(AbstractFilesystem.class);
        when(lower.exists(rc, "MEMORY.md")).thenReturn(true);
        when(lower.downloadFiles(rc, List.of("MEMORY.md")))
                .thenReturn(
                        List.of(
                                FileDownloadResponse.success(
                                        "MEMORY.md", content.getBytes(StandardCharsets.UTF_8))));
        var upper = new LocalFilesystem(directory);
        AtomicTextFile file =
                new OverlayFilesystem(upper, lower).atomicTextFile(rc, "MEMORY.md", true);
        assertEquals(content, file.read());
        assertFalse(file.compareAndSet("different", "new fact"));
        assertTrue(file.compareAndSet(content, "new fact"));
        assertEquals("new fact", file.read());
        assertEquals("new fact", upper.atomicTextFile(rc, "MEMORY.md", false).read());
        verify(lower, never()).atomicTextFile(any(), anyString(), anyBoolean());
        verify(lower, never()).read(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    void lowerDownloadFailureIsNotTreatedAsAnAbsentFile() {
        var lower = mock(AbstractFilesystem.class);
        when(lower.exists(rc, "MEMORY.md")).thenReturn(true);
        when(lower.downloadFiles(rc, List.of("MEMORY.md")))
                .thenReturn(List.of(FileDownloadResponse.fail("MEMORY.md", "unavailable")));
        var upper = new LocalFilesystem(directory);
        AtomicTextFile file =
                new OverlayFilesystem(upper, lower).atomicTextFile(rc, "MEMORY.md", true);
        assertThrows(IllegalStateException.class, file::read);
        assertThrows(IllegalStateException.class, () -> file.compareAndSet(null, "new fact"));
        assertFalse(upper.exists(rc, "MEMORY.md"));
    }
}
