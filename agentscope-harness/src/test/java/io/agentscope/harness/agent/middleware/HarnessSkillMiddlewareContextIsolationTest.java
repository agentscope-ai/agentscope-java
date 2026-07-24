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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessSkillMiddlewareContextIsolationTest {

    @TempDir Path workspace;

    @Test
    void usesCallContextWhenSharedActiveContextPointsToAnotherSession() throws IOException {
        RuntimeContext alice =
                RuntimeContext.builder().sessionId("alice-session").put("tenant", "alice").build();
        RuntimeContext bob =
                RuntimeContext.builder().sessionId("bob-session").put("tenant", "bob").build();
        NamespaceFactory namespaces =
                ctx -> List.of("tenants", ctx != null ? (String) ctx.get("tenant") : "anonymous");
        LocalFilesystem filesystem = new LocalFilesystem(workspace, false, 64, namespaces);
        writeSkill("alice", "alice-skill", "Alice private description", "Alice private body");
        writeSkill("bob", "bob-skill", "Bob private description", "Bob private body");

        // Reproduces the concurrent interleaving where session B overwrites ReActAgent.activeRc
        // immediately before session A builds its skill catalog.
        AtomicReference<RuntimeContext> sharedActiveContext = new AtomicReference<>(bob);
        WorkspaceSkillRepository repository =
                new WorkspaceSkillRepository(
                        filesystem, "skills", sharedActiveContext::get, "workspace", false);
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(List.of(repository), new Toolkit());

        String prompt = middleware.onSystemPrompt(null, alice, "").block();

        assertTrue(prompt.contains("Alice private description"));
        assertFalse(prompt.contains("Bob private description"));
        assertTrue(
                middleware.runtime().currentCatalog(alice).ids().stream()
                        .anyMatch(id -> id.startsWith("alice-skill_")));
        assertFalse(
                middleware.runtime().currentCatalog(alice).ids().stream()
                        .anyMatch(id -> id.startsWith("bob-skill_")));
    }

    private void writeSkill(String tenant, String name, String description, String body)
            throws IOException {
        Path directory =
                workspace.resolve("tenants").resolve(tenant).resolve("skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + body + "\n");
    }
}
