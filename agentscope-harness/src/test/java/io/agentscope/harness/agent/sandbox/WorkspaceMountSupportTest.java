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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.FileEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceMountSupportTest {

    @Test
    void tarExcludeArgs_topLevelAndNested() {
        BindMountEntry top = new BindMountEntry();
        top.setHostPath("/host/a");
        top.setReadOnly(false);
        DirEntry dir = new DirEntry();
        BindMountEntry inner = new BindMountEntry();
        inner.setHostPath("/host/b");
        inner.setReadOnly(true);
        dir.getChildren().put("inner", inner);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("mnt", top);
        spec.getEntries().put("d", dir);

        List<String> args = WorkspaceMountSupport.tarExcludeArgsForBindMounts(spec);
        assertEquals(List.of("--exclude=./mnt", "--exclude=./d/inner"), args);
    }

    @Test
    void hasBindMounts_falseForEmptyOrFileOnly() {
        assertFalse(WorkspaceMountSupport.hasBindMounts(null));
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("f", new FileEntry("x"));
        assertFalse(WorkspaceMountSupport.hasBindMounts(spec));
        BindMountEntry b = new BindMountEntry();
        b.setHostPath("/tmp");
        spec.getEntries().put("b", b);
        assertTrue(WorkspaceMountSupport.hasBindMounts(spec));
    }

    @Test
    void topLevelBindMounts_onlyDirectChildren() {
        DirEntry dir = new DirEntry();
        BindMountEntry nested = new BindMountEntry();
        nested.setHostPath("/x");
        dir.getChildren().put("nested", nested);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("dir", dir);
        assertTrue(WorkspaceMountSupport.topLevelBindMounts(spec).isEmpty());
    }

    @Test
    void containerMountPath_joinsRootAndKey() {
        assertEquals(
                "/workspace/repo", WorkspaceMountSupport.containerMountPath("/workspace", "repo"));
        assertEquals(
                "/workspace/repo", WorkspaceMountSupport.containerMountPath("/workspace/", "repo"));
    }
}
