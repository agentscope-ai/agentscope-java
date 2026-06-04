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
package io.agentscope.harness.agent.filesystem.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BaseSandboxFilesystemTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void edit_usesDownloadReplaceUploadWithoutShellExecution() {
        InMemorySandboxFilesystem fs = new InMemorySandboxFilesystem();
        fs.put("/workspace/note.txt", "hello\nworld\nhello");

        EditResult result = fs.edit(RT, "/workspace/note.txt", "world", "there", false);

        assertTrue(result.isSuccess());
        assertEquals("/workspace/note.txt", result.path());
        assertEquals(1, result.occurrences());
        assertEquals("hello\nthere\nhello", fs.contentOf("/workspace/note.txt"));
        assertEquals(1, fs.downloadCalls.get());
        assertEquals(1, fs.uploadCalls.get());
        assertEquals(0, fs.executeCalls.get());
    }

    @Test
    void edit_returnsNotFoundWhenDownloadFails() {
        InMemorySandboxFilesystem fs = new InMemorySandboxFilesystem();

        EditResult result = fs.edit(RT, "/workspace/missing.txt", "old", "new", false);

        assertFalse(result.isSuccess());
        assertEquals("Error: File '/workspace/missing.txt' not found", result.error());
        assertEquals(1, fs.downloadCalls.get());
        assertEquals(0, fs.uploadCalls.get());
        assertEquals(0, fs.executeCalls.get());
    }

    private static final class InMemorySandboxFilesystem extends BaseSandboxFilesystem {
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final AtomicInteger executeCalls = new AtomicInteger();
        private final AtomicInteger downloadCalls = new AtomicInteger();
        private final AtomicInteger uploadCalls = new AtomicInteger();

        @Override
        public String id() {
            return "in-memory";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            executeCalls.incrementAndGet();
            throw new AssertionError("edit must not call execute()");
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            uploadCalls.incrementAndGet();
            List<FileUploadResponse> responses = new ArrayList<>(files.size());
            for (Map.Entry<String, byte[]> file : files) {
                this.files.put(file.getKey(), file.getValue());
                responses.add(FileUploadResponse.success(file.getKey()));
            }
            return responses;
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            downloadCalls.incrementAndGet();
            List<FileDownloadResponse> responses = new ArrayList<>(paths.size());
            for (String path : paths) {
                byte[] content = files.get(path);
                if (content == null) {
                    responses.add(FileDownloadResponse.fail(path, "No such file or directory"));
                } else {
                    responses.add(FileDownloadResponse.success(path, content));
                }
            }
            return responses;
        }

        void put(String path, String content) {
            files.put(path, content.getBytes(StandardCharsets.UTF_8));
        }

        String contentOf(String path) {
            byte[] content = files.get(path);
            return content == null ? null : new String(content, StandardCharsets.UTF_8);
        }
    }
}
