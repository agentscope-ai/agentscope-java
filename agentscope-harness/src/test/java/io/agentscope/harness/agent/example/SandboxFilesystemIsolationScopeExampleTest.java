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
package io.agentscope.harness.agent.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.example.support.InMemorySandboxClient;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Example: Sandbox filesystem mode with different {@link IsolationScope} levels.
 *
 * <h2>Context</h2>
 * <p>In sandbox mode, each agent call acquires a sandbox session (e.g. a Docker container or,
 * in this test, a local temp directory). The {@code IsolationScope} controls which calls
 * <em>resume</em> the same sandbox (sharing accumulated state) versus which ones
 * <em>create</em> a fresh sandbox.
 *
 * <p>Three scopes are demonstrated:
 * <ul>
 *   <li>{@link IsolationScope#SESSION} – calls with the same session ID resume the same sandbox.
 *       Calls from a different session get a brand-new sandbox.</li>
 *   <li>{@link IsolationScope#USER} – calls from the same user (any session) resume the same
 *       sandbox. Calls from a different user get a fresh sandbox.</li>
 *   <li>{@link IsolationScope#AGENT} – all calls share one sandbox regardless of user or
 *       session.</li>
 * </ul>
 *
 * <p>This test uses {@link InMemorySandboxClient} (no Docker required) and a Mockito-stubbed
 * {@link Model} that immediately returns a terminal text response so the ReAct loop exits in one
 * step. The assertions count {@link InMemorySandboxClient#getCreateCount()} and
 * {@link InMemorySandboxClient#getResumeCount()} to verify isolation behaviour.
 */
class SandboxFilesystemIsolationScopeExampleTest {

    @TempDir Path workspace;

    // -------------------------------------------------------------------------
    // Scenario A: SESSION scope
    // -------------------------------------------------------------------------

    /**
     * SESSION scope — same session ID → sandbox is resumed on the second call.
     *
     * <p>Call 1: no persisted state → {@code create} is called (creates fresh sandbox).
     * Call 2: state was persisted for "session-1" → {@code resume} is called (same sandbox).
     */
    @Test
    void sessionScope_sameSession_resumesSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.SESSION);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sandboxDistributed(
                                SandboxDistributedOptions.builder()
                                        .requireDistributed(false)
                                        .build())
                        .build();

        // First call — no persisted state → create
        agent.call(userMsg("hello"), ctx("session-1", null)).block();
        assertEquals(1, client.getCreateCount(), "first call should create a fresh sandbox");
        assertEquals(0, client.getResumeCount());

        // Second call — same session → resume
        agent.call(userMsg("hello again"), ctx("session-1", null)).block();
        assertEquals(1, client.getCreateCount(), "second call should NOT create a new sandbox");
        assertEquals(1, client.getResumeCount(), "second call should resume the existing sandbox");
    }

    /**
     * SESSION scope — different session ID → each call creates a fresh sandbox.
     */
    @Test
    void sessionScope_differentSession_createsFreshSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.SESSION);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sandboxDistributed(
                                SandboxDistributedOptions.builder()
                                        .requireDistributed(false)
                                        .build())
                        .build();

        agent.call(userMsg("call from session-1"), ctx("session-2-1", "alice")).block();
        agent.call(userMsg("call from session-2"), ctx("session-2-2", "alice")).block();

        assertEquals(
                2, client.getCreateCount(), "each distinct session should create its own sandbox");
        assertEquals(0, client.getResumeCount());
    }

    // -------------------------------------------------------------------------
    // Scenario B: USER scope
    // -------------------------------------------------------------------------

    /**
     * USER scope — same user, different session IDs → both calls share the same sandbox.
     *
     * <p>Call 1: no persisted state for user "alice" → {@code create}.
     * Call 2: state exists for "alice" (different sessionId) → {@code resume}.
     */
    @Test
    void userScope_sameUser_differentSessions_resumesSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.USER);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sandboxDistributed(
                                SandboxDistributedOptions.builder()
                                        .requireDistributed(false)
                                        .build())
                        .build();

        agent.call(userMsg("session A"), ctx("session-a", "alice")).block();
        assertEquals(1, client.getCreateCount());
        assertEquals(0, client.getResumeCount());

        // Different session, same user → should resume Alice's sandbox
        agent.call(userMsg("session B"), ctx("session-b", "alice")).block();
        assertEquals(1, client.getCreateCount(), "same user → should NOT create a new sandbox");
        assertEquals(1, client.getResumeCount(), "same user → should resume existing sandbox");
    }

    /**
     * USER scope — different users → each user gets an independent sandbox.
     */
    @Test
    void userScope_differentUsers_createsFreshSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.USER);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sandboxDistributed(
                                SandboxDistributedOptions.builder()
                                        .requireDistributed(false)
                                        .build())
                        .build();

        agent.call(userMsg("hi from alice2"), ctx("s1", "alice2")).block();
        agent.call(userMsg("hi from bob2"), ctx("s2", "bob2")).block();

        assertEquals(2, client.getCreateCount(), "each user should get their own fresh sandbox");
        assertEquals(0, client.getResumeCount());
    }

    // -------------------------------------------------------------------------
    // Scenario C: AGENT scope
    // -------------------------------------------------------------------------

    /**
     * AGENT scope — all calls share a single sandbox regardless of user or session.
     *
     * <p>Call 1: no state → {@code create}.
     * Call 2: state exists (agentId key) → {@code resume} — even with a different user and session.
     */
    @Test
    void agentScope_allCallsShareOneSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.AGENT);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("shared-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sandboxDistributed(
                                SandboxDistributedOptions.builder()
                                        .requireDistributed(false)
                                        .build())
                        .build();

        // Different users, different sessions — all share one AGENT-scoped sandbox
        agent.call(userMsg("alice says hi"), ctx("s1", "alice")).block();
        assertEquals(1, client.getCreateCount());

        agent.call(userMsg("bob says hi"), ctx("s2", "bob")).block();
        assertEquals(1, client.getCreateCount(), "second call should NOT create a new sandbox");
        assertEquals(
                1, client.getResumeCount(), "second call should resume the shared agent sandbox");

        agent.call(userMsg("charlie says hi"), ctx("s3", "charlie")).block();
        assertEquals(1, client.getCreateCount());
        assertEquals(
                2, client.getResumeCount(), "third call should also resume the shared sandbox");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Model stubModel(String text) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(text).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
