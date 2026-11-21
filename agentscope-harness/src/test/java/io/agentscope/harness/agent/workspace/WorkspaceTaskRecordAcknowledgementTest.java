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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTaskRecordAcknowledgementTest {

    private static final String TASK_PATH = "agents/agent-a/tasks/s-1.json";

    @TempDir Path workspace;

    @Test
    void rejectedUploadDoesNotMarkOrphanAsFailedOrAdvanceIndex() throws Exception {
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("s-1").build();
        NamespaceFactory namespace = context -> List.of(context.getUserId());
        String rejectedError = "backend rejected task upload";
        LocalFilesystem backend =
                new LocalFilesystem(workspace, false, 10, namespace) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
                        var file = files.get(0);
                        if (new String(file.getValue(), StandardCharsets.UTF_8)
                                .contains(rejectedError)) {
                            return List.of(FileUploadResponse.fail(file.getKey(), rejectedError));
                        }
                        return super.uploadFiles(context, files);
                    }
                };

        try (WorkspaceIndex index = WorkspaceIndex.open(workspace);
                WorkspaceManager manager =
                        new WorkspaceManager(workspace, backend, index, namespace)) {
            assertNotNull(index);
            TaskRecord task = new TaskRecord("t1", "agent-a", "parent", "s-1", null);
            task.setStatus(TaskStatus.RUNNING);
            manager.writeTaskRecord(alice, "agent-a", "s-1", task);
            Path persisted = workspace.resolve("alice").resolve(TASK_PATH);
            byte[] original = Files.readAllBytes(persisted);
            assertEquals(original.length, indexedSize());

            assertFalse(
                    manager.failOrphanedTask(
                            alice, "agent-a", "s-1", "t1", Instant.now(), rejectedError));
            assertArrayEquals(original, Files.readAllBytes(persisted));
            assertEquals(original.length, indexedSize());
            assertEquals(
                    TaskStatus.RUNNING,
                    manager.readTaskRecord(alice, "agent-a", "s-1", "t1")
                            .orElseThrow()
                            .getStatus());

            assertTrue(
                    manager.failOrphanedTask(
                            alice, "agent-a", "s-1", "t1", Instant.now(), "retry succeeded"));
            assertEquals(
                    TaskStatus.FAILED,
                    manager.readTaskRecord(alice, "agent-a", "s-1", "t1")
                            .orElseThrow()
                            .getStatus());
            assertEquals(Files.size(persisted), indexedSize());
        }
    }

    private long indexedSize() throws Exception {
        Path indexFile = workspace.resolve(".index/workspace.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + indexFile);
                var query =
                        connection.prepareStatement(
                                "SELECT size_bytes FROM files WHERE path = ?")) {
            query.setString(1, TASK_PATH);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }
}
