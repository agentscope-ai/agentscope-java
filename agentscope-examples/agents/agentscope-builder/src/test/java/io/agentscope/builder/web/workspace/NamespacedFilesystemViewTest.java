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
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link NamespacedFilesystemView}: per-tenant prefixing yields physical isolation,
 * malicious paths are rejected before reaching the delegate, and a per-call factory varies the
 * scope correctly.
 */
class NamespacedFilesystemViewTest {

    @TempDir Path tmp;

    private static NamespaceFactory namespaceOf(String ownerId, String agentId) {
        return rc -> List.of("users", ownerId, "agents", agentId);
    }

    @Test
    void prefixesReadsAndWritesIntoNamespacedSubtree() {
        LocalFilesystem backend = new LocalFilesystem(tmp, true, 10, null);
        AbstractFilesystem alice =
                new NamespacedFilesystemView(backend, namespaceOf("alice", "research-bot"));

        WriteResult write = alice.write(RuntimeContext.empty(), "/AGENTS.md", "hello-from-alice");
        assertThat(write.isSuccess()).isTrue();

        // Physical layout reflects the namespace: backend rooted at tmp, with
        // /users/alice/agents/research-bot/AGENTS.md
        Path expected = tmp.resolve("users/alice/agents/research-bot/AGENTS.md");
        assertThat(Files.exists(expected)).isTrue();

        ReadResult read = alice.read(RuntimeContext.empty(), "/AGENTS.md", 0, 100);
        assertThat(read.isSuccess()).isTrue();
        assertThat(read.fileData().content()).contains("hello-from-alice");
    }

    @Test
    void differentNamespacesAreIsolated() {
        LocalFilesystem backend = new LocalFilesystem(tmp, true, 10, null);
        AbstractFilesystem alice =
                new NamespacedFilesystemView(backend, namespaceOf("alice", "bot"));
        AbstractFilesystem bob = new NamespacedFilesystemView(backend, namespaceOf("bob", "bot"));

        alice.write(RuntimeContext.empty(), "/secret.txt", "alice-only");
        ReadResult bobRead = bob.read(RuntimeContext.empty(), "/secret.txt", 0, 100);
        // Bob's view does not see Alice's file (returns failure / non-existent)
        assertThat(bobRead.isSuccess()).isFalse();
    }

    @Test
    void rejectsPathTraversalAttempts() {
        LocalFilesystem backend = new LocalFilesystem(tmp, true, 10, null);
        AbstractFilesystem alice =
                new NamespacedFilesystemView(backend, namespaceOf("alice", "bot"));

        assertThatThrownBy(() -> alice.write(RuntimeContext.empty(), "/../../etc/passwd", "pwned"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
    }

    @Test
    void factoryIsInvokedPerCallSoNamespaceCanVary() {
        LocalFilesystem backend = new LocalFilesystem(tmp, true, 10, null);
        String[] currentAgent = {"a"};
        NamespaceFactory dynamic = rc -> List.of("users", "alice", "agents", currentAgent[0]);
        AbstractFilesystem view = new NamespacedFilesystemView(backend, dynamic);

        view.write(RuntimeContext.empty(), "/AGENTS.md", "for-a");
        currentAgent[0] = "b";
        view.write(RuntimeContext.empty(), "/AGENTS.md", "for-b");

        assertThat(Files.exists(tmp.resolve("users/alice/agents/a/AGENTS.md"))).isTrue();
        assertThat(Files.exists(tmp.resolve("users/alice/agents/b/AGENTS.md"))).isTrue();
    }

    @Test
    void lsScopedRootReturnsNamespaceContents() throws Exception {
        LocalFilesystem backend = new LocalFilesystem(tmp, true, 10, null);
        AbstractFilesystem alice =
                new NamespacedFilesystemView(backend, namespaceOf("alice", "bot"));

        alice.write(RuntimeContext.empty(), "/notes.md", "n");
        alice.write(RuntimeContext.empty(), "/skills/k.md", "k");

        LsResult result = alice.ls(RuntimeContext.empty(), "/");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.entries()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void rejectsBlankNamespaceSegment() {
        LocalFilesystem backend = new LocalFilesystem(tmp, true, 10, null);
        AbstractFilesystem bad =
                new NamespacedFilesystemView(backend, rc -> List.of("users", "", "agents", "x"));

        assertThatThrownBy(() -> bad.write(RuntimeContext.empty(), "/AGENTS.md", "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null or blank");
    }
}
