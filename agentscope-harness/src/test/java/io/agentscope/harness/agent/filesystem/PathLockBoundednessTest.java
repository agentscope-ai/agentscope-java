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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathLockBoundednessTest {

    private static final int PATHS_PER_BATCH = 128;

    @Test
    void skillUploadsDoNotRetainMoreLocksForEachNewPath(@TempDir Path root) throws Exception {
        SkillBox box = new SkillBox(new Toolkit());
        box.setWorkDir(root);
        uploadBatch(box, 0);
        int retained = retainedLocks(box, "FILE_LOCKS");

        uploadBatch(box, 1);

        assertEquals(retained, retainedLocks(box, "FILE_LOCKS"));
    }

    private void uploadBatch(SkillBox box, int batch) throws Exception {
        Map<String, String> resources = new LinkedHashMap<>();
        for (int i = 0; i < PATHS_PER_BATCH; i++) {
            resources.put("file-" + i + ".txt", "text");
        }
        resources.put("binary.bin", "base64:AQID");
        AgentSkill skill = new AgentSkill("batch-" + batch, "Resources", "# Skill", resources);
        box.registerSkill(skill);
        box.uploadSkillFiles();
        Path uploaded = box.getUploadDir().resolve(skill.getSkillId());
        assertEquals("text", Files.readString(uploaded.resolve("file-127.txt")));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new byte[] {1, 2, 3}, Files.readAllBytes(uploaded.resolve("binary.bin")));
    }

    @Test
    void workspaceOperationsDoNotRetainMoreLocksForEachNewPath(@TempDir Path root)
            throws Exception {
        try (WorkspaceManager manager = new WorkspaceManager(root)) {
            writeWorkspaceBatch(manager, root, 0);
            int retained = retainedLocks(manager, "pathLocks");

            writeWorkspaceBatch(manager, root, 1);

            assertEquals(retained, retainedLocks(manager, "pathLocks"));
        }
    }

    private void writeWorkspaceBatch(WorkspaceManager manager, Path root, int batch)
            throws Exception {
        RuntimeContext context = RuntimeContext.empty();
        for (int i = 0; i < PATHS_PER_BATCH; i++) {
            String path = "notes/" + batch + "-" + i + ".md";
            manager.appendUtf8WorkspaceRelative(context, path, "first");
            manager.appendUtf8WorkspaceRelative(context, path, " second");
            assertEquals("first second", Files.readString(root.resolve(path)));
            String session = "session-" + batch + "-" + i;
            TaskRecord record = new TaskRecord("task", "child", "parent", session, "child-session");
            manager.writeTaskRecord(context, "parent", session, record);
            assertEquals(
                    "task",
                    manager.readTaskRecord(context, "parent", session, "task")
                            .orElseThrow()
                            .getTaskId());
        }
    }

    @Test
    void fileEditsDoNotRetainMoreLocksForEachNewPath(@TempDir Path root) throws Exception {
        LocalFilesystem filesystem = new LocalFilesystem(root);
        editBatch(filesystem, root, 0);
        int retained = retainedLocks(filesystem, "fileLocks");

        editBatch(filesystem, root, 1);

        assertEquals(retained, retainedLocks(filesystem, "fileLocks"));
    }

    private void editBatch(LocalFilesystem filesystem, Path root, int batch) throws Exception {
        for (int i = 0; i < PATHS_PER_BATCH; i++) {
            String name = "file-" + batch + "-" + i + ".txt";
            Files.writeString(root.resolve(name), "before");
            assertTrue(
                    filesystem
                            .edit(RuntimeContext.empty(), name, "before", "after", false)
                            .isSuccess());
            assertEquals("after", Files.readString(root.resolve(name)));
        }
    }

    // 锁保留量无法通过文件内容观察；只在测试侧检查存储，不为诊断增加生产 API。
    private int retainedLocks(Object owner, String fieldName) throws ReflectiveOperationException {
        Field registryField = owner.getClass().getDeclaredField(fieldName);
        registryField.setAccessible(true);
        Object registry = registryField.get(owner);
        if (registry instanceof Map<?, ?> map) {
            return map.size();
        }
        for (Field field : registry.getClass().getDeclaredFields()) {
            if (field.getType() == ReentrantLock[].class) {
                field.setAccessible(true);
                return ((ReentrantLock[]) field.get(registry)).length;
            }
        }
        throw new AssertionError("Unrecognized lock storage: " + registry.getClass());
    }
}
