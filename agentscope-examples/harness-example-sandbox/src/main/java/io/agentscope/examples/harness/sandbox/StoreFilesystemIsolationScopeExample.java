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
package io.agentscope.examples.harness.sandbox;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.examples.harness.sandbox.support.FixedReplyModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.StoreFilesystemSpec;
import io.agentscope.harness.agent.store.InMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Example: {@link StoreFilesystemSpec} with in-memory key-value store and different {@link
 * IsolationScope} namespaces.
 */
public final class StoreFilesystemIsolationScopeExample {

    public static void main(String[] args) throws Exception {
        Model model = FixedReplyModel.done();
        Path workspace = Files.createTempDirectory("harness-store-isolation-example-");
        System.out.println("Workspace: " + workspace.toAbsolutePath());

        sessionScopeIsolated(model, workspace);
        sessionScopeSharedWithinSession(model, workspace);
        userScopeSharedAcrossSessions(model, workspace);
        userScopeIsolatedByUser(model, workspace);
        agentScopeSharedByAllCallers(model, workspace);

        System.out.println("Store isolation example finished successfully.");
    }

    static void sessionScopeIsolated(Model model, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(
                                new StoreFilesystemSpec(store)
                                        .isolationScope(IsolationScope.SESSION))
                        .build();

        agent.call(userMsg("from session-1"), ctx("session-1", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("MEMORY.md", "session-1 notes");
        if (store.get(List.of("agents", "assistant", "sessions", "session-1"), "/MEMORY.md")
                == null) {
            throw new IllegalStateException("data should exist under session-1");
        }
        if (store.get(List.of("agents", "assistant", "sessions", "session-2"), "/MEMORY.md")
                != null) {
            throw new IllegalStateException("session-2 namespace should be empty");
        }
        System.out.println("[store] SESSION: per-session namespace: OK");
    }

    static void sessionScopeSharedWithinSession(Model model, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(
                                new StoreFilesystemSpec(store)
                                        .isolationScope(IsolationScope.SESSION))
                        .build();

        agent.call(userMsg("call 1"), ctx("session-1", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("MEMORY.md", "shared memory");
        agent.call(userMsg("call 2"), ctx("session-1", "alice")).block();
        if (store.get(List.of("agents", "assistant", "sessions", "session-1"), "/MEMORY.md")
                == null) {
            throw new IllegalStateException("MEMORY under session-1");
        }
        System.out.println("[store] SESSION: same session reuses key: OK");
    }

    static void userScopeSharedAcrossSessions(Model model, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(
                                new StoreFilesystemSpec(store).isolationScope(IsolationScope.USER))
                        .build();

        agent.call(userMsg("hi from session-a"), ctx("session-a", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("MEMORY.md", "alice's memory");
        if (store.get(List.of("agents", "assistant", "users", "alice"), "/MEMORY.md") == null) {
            throw new IllegalStateException("data under user alice");
        }
        agent.call(userMsg("hi from session-b"), ctx("session-b", "alice")).block();
        if (store.get(List.of("agents", "assistant", "users", "alice"), "/MEMORY.md") == null) {
            throw new IllegalStateException("alice's key still present");
        }
        System.out.println("[store] USER: one namespace per user: OK");
    }

    static void userScopeIsolatedByUser(Model model, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(
                                new StoreFilesystemSpec(store).isolationScope(IsolationScope.USER))
                        .build();

        agent.call(userMsg("alice writes"), ctx("s1", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("MEMORY.md", "alice's data");
        if (store.get(List.of("agents", "assistant", "users", "bob"), "/MEMORY.md") != null) {
            throw new IllegalStateException("bob should not see alice's data");
        }
        System.out.println("[store] USER: users are isolated: OK");
    }

    static void agentScopeSharedByAllCallers(Model model, Path workspace) throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("shared-assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(
                                new StoreFilesystemSpec(store).isolationScope(IsolationScope.AGENT))
                        .build();

        agent.call(userMsg("alice"), ctx("s1", "alice")).block();
        agent.getWorkspaceManager().writeUtf8WorkspaceRelative("MEMORY.md", "shared knowledge");
        if (store.get(List.of("agents", "shared-assistant", "shared"), "/MEMORY.md") == null) {
            throw new IllegalStateException("shared namespace");
        }
        agent.call(userMsg("bob"), ctx("s2", "bob")).block();
        if (store.get(List.of("agents", "shared-assistant", "shared"), "/MEMORY.md") == null) {
            throw new IllegalStateException("data still in shared");
        }
        System.out.println("[store] AGENT: shared key for all callers: OK");
    }

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
