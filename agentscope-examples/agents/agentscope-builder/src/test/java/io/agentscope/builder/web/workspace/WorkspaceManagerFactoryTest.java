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
package io.agentscope.builder.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerFactoryTest {

    private static Path agentScopeBase() {
        return Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .resolve(".agentscope");
    }

    private static WorkspaceManagerFactory factory(Path root) {
        return new WorkspaceManagerFactory(root, 5, new InMemoryStore());
    }

    @Test
    void blankWorkspacePathFallsBackToAgentIdUnderAgentscopeBase(@TempDir Path root) {
        WorkspaceManagerFactory factory = factory(root);

        Path resolved = factory.resolveAgentDataPath(null, "my-agent");

        assertThat(resolved).isEqualTo(agentScopeBase().resolve("my-agent").normalize());
    }

    @Test
    void blankStringWorkspacePathFallsBackToAgentId(@TempDir Path root) {
        WorkspaceManagerFactory factory = factory(root);

        Path resolved = factory.resolveAgentDataPath("   ", "my-agent");

        assertThat(resolved).isEqualTo(agentScopeBase().resolve("my-agent").normalize());
    }

    @Test
    void relativeWorkspacePathResolvesUnderAgentscopeBase(@TempDir Path root) {
        WorkspaceManagerFactory factory = factory(root);

        Path resolved = factory.resolveAgentDataPath("projects/alpha", "fallback");

        assertThat(resolved).isEqualTo(agentScopeBase().resolve("projects/alpha").normalize());
    }

    @Test
    void relativePathAlreadyUnderAgentscopeBaseIsNotDoublePrefixed(@TempDir Path root) {
        WorkspaceManagerFactory factory = factory(root);

        Path resolved = factory.resolveAgentDataPath(".agentscope/foo", "fallback");

        assertThat(resolved).isEqualTo(agentScopeBase().resolve("foo").normalize());
    }

    @Test
    void absoluteWorkspacePathIsReturnedAsIsNormalized(@TempDir Path root, @TempDir Path other) {
        WorkspaceManagerFactory factory = factory(root);

        Path resolved = factory.resolveAgentDataPath(other.toString() + "/agent-x", "fallback");

        assertThat(resolved).isEqualTo(other.resolve("agent-x").normalize());
    }

    @Test
    void absoluteWorkspacePathWithDotSegmentsIsNormalized(@TempDir Path root, @TempDir Path other) {
        WorkspaceManagerFactory factory = factory(root);

        Path withDots = other.resolve("a").resolve("..").resolve("b");
        Path resolved = factory.resolveAgentDataPath(withDots.toString(), "fallback");

        assertThat(resolved).isEqualTo(other.resolve("b").normalize());
    }

    @Test
    void blankBothInputsThrows(@TempDir Path root) {
        WorkspaceManagerFactory factory = factory(root);

        assertThatThrownBy(() -> factory.resolveAgentDataPath(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.resolveAgentDataPath("", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullBaseStoreThrows(@TempDir Path root) {
        assertThatThrownBy(() -> new WorkspaceManagerFactory(root, 5, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Cross-instance visibility: a write through one {@link WorkspaceManagerFactory} must be
     * readable through a second factory pointed at the same {@link BaseStore}, proving that the
     * remote routes are persisted through the store and are not pinned to per-pod local disk.
     */
    @Test
    void remoteRouteIsVisibleAcrossFactoryInstances(@TempDir Path rootA, @TempDir Path rootB) {
        BaseStore sharedStore = new InMemoryStore();

        WorkspaceManagerFactory factoryA = new WorkspaceManagerFactory(rootA, 5, sharedStore);
        WorkspaceManager managerA = factoryA.forAgent("alice", "agent-1");
        AbstractFilesystem fsA = managerA.getFilesystem();

        RuntimeContext ctx = RuntimeContext.empty();
        WriteResult write = fsA.write(ctx, "memory/foo.md", "hello-from-pod-A");
        assertThat(write.isSuccess()).as("write through pod A").isTrue();

        WorkspaceManagerFactory factoryB = new WorkspaceManagerFactory(rootB, 5, sharedStore);
        WorkspaceManager managerB = factoryB.forAgent("alice", "agent-1");
        AbstractFilesystem fsB = managerB.getFilesystem();

        ReadResult read = fsB.read(ctx, "memory/foo.md", 0, Integer.MAX_VALUE);
        assertThat(read.isSuccess()).as("read through pod B").isTrue();
        assertThat(read.fileData().content()).isEqualTo("hello-from-pod-A");
    }
}
