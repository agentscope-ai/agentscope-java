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
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression for issue #2266: WriteResult/EditResult must report the absolute on-disk path
 * after namespace isolation, not the caller-supplied logical path.
 */
class LocalFilesystemWritePathTest {

    private static final NamespaceFactory USER_NS = rc -> List.of("web-user");

    @Test
    void write_returnsAbsolutePathIncludingNamespace(@TempDir Path workspace) throws Exception {
        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("web-user").build();

        WriteResult result = fs.write(rc, "hello_world.py", "print('hi')\n");
        assertTrue(result.isSuccess(), () -> "write failed: " + result.error());

        Path expected =
                workspace
                        .resolve("web-user")
                        .resolve("hello_world.py")
                        .toAbsolutePath()
                        .normalize();
        assertEquals(expected.toString(), result.path());
        assertTrue(Files.exists(Path.of(result.path())));
        assertEquals("print('hi')\n", Files.readString(expected));
    }

    @Test
    void edit_returnsAbsolutePathIncludingNamespace(@TempDir Path workspace) throws Exception {
        Path nsFile = workspace.resolve("web-user").resolve("notes.txt");
        Files.createDirectories(nsFile.getParent());
        Files.writeString(nsFile, "hello\n");

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("web-user").build();

        EditResult result = fs.edit(rc, "notes.txt", "hello", "world", false);
        assertTrue(result.isSuccess(), () -> "edit failed: " + result.error());
        assertEquals(nsFile.toAbsolutePath().normalize().toString(), result.path());
        assertEquals("world\n", Files.readString(nsFile));
    }

    @Test
    void move_returnsAbsoluteDestinationPath(@TempDir Path workspace) throws Exception {
        Path src = workspace.resolve("web-user").resolve("a.txt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "x");

        LocalFilesystem fs =
                new LocalFilesystem(workspace, LocalFsMode.ROOTED, PathPolicy.empty(), 10, USER_NS);
        RuntimeContext rc = RuntimeContext.builder().userId("web-user").build();

        WriteResult result = fs.move(rc, "a.txt", "b.txt");
        assertTrue(result.isSuccess(), () -> "move failed: " + result.error());

        Path expected = workspace.resolve("web-user").resolve("b.txt").toAbsolutePath().normalize();
        assertEquals(expected.toString(), result.path());
        assertTrue(Files.exists(expected));
    }
}
