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
import io.agentscope.examples.harness.sandbox.support.InMemorySandboxClient;
import io.agentscope.examples.harness.sandbox.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.RuntimeContext;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Example: sandbox filesystem with {@link IsolationScope} (in-process {@link InMemorySandboxClient}
 * simulates create/resume without Docker).
 */
public final class SandboxFilesystemIsolationScopeExample {

    public static void main(String[] args) throws Exception {
        Model model = FixedReplyModel.done();
        Path workspace = Files.createTempDirectory("harness-sandbox-isolation-example-");
        System.out.println("Control workspace: " + workspace.toAbsolutePath());

        sessionScopeSameSessionResumes(workspace, model);
        sessionScopeDifferentSessionCreatesTwo(workspace, model);
        userScopeSameUserResumesAcrossSessions(workspace, model);
        userScopeDifferentUsersGetTwoSandboxes(workspace, model);
        agentScopeEveryoneSharesOneSandbox(workspace, model);

        System.out.println("Sandbox isolation example finished successfully.");
    }

    static void sessionScopeSameSessionResumes(Path workspace, Model model) throws Exception {
        Files.createDirectories(workspace);
        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.SESSION);
        InMemorySandboxClient client = spec.getClient();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(spec)
                        .build();

        agent.call(userMsg("hello"), ctx("session-1", null)).block();
        if (client.getCreateCount() != 1 || client.getResumeCount() != 0) {
            throw new IllegalStateException("expected 1 create, 0 resume after first call");
        }
        agent.call(userMsg("hello again"), ctx("session-1", null)).block();
        if (client.getCreateCount() != 1 || client.getResumeCount() != 1) {
            throw new IllegalStateException("expected 1 create, 1 resume for same session");
        }
        System.out.println("[sandbox] SESSION: same session resumes: OK");
    }

    static void sessionScopeDifferentSessionCreatesTwo(Path workspace, Model model)
            throws Exception {
        Files.createDirectories(workspace);
        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.SESSION);
        InMemorySandboxClient client = spec.getClient();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(spec)
                        .build();

        agent.call(userMsg("call from session-DifferentSessionCreatesTwo-1"), ctx("session-DifferentSessionCreatesTwo-1", "alice")).block();
        agent.call(userMsg("call from session-DifferentSessionCreatesTwo-2"), ctx("session-DifferentSessionCreatesTwo-2", "alice")).block();
        if (client.getCreateCount() != 2 || client.getResumeCount() != 0) {
            throw new IllegalStateException("expected 2 creates for distinct sessions");
        }
        System.out.println("[sandbox] SESSION: different sessions get new sandboxes: OK");
    }

    static void userScopeSameUserResumesAcrossSessions(Path workspace, Model model)
            throws Exception {
        Files.createDirectories(workspace);
        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.USER);
        InMemorySandboxClient client = spec.getClient();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(spec)
                        .build();

        agent.call(userMsg("session A"), ctx("session-a", "alice")).block();
        if (client.getCreateCount() != 1) {
            throw new IllegalStateException("expected 1 create");
        }
        agent.call(userMsg("session B"), ctx("session-b", "alice")).block();
        if (client.getCreateCount() != 1 || client.getResumeCount() != 1) {
            throw new IllegalStateException("same user should resume across sessions");
        }
        System.out.println("[sandbox] USER: same user resumes across sessions: OK");
    }

    static void userScopeDifferentUsersGetTwoSandboxes(Path workspace, Model model)
            throws Exception {
        Files.createDirectories(workspace);
        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.USER);
        InMemorySandboxClient client = spec.getClient();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(spec)
                        .build();

        agent.call(userMsg("hi from alice2"), ctx("s1", "alice2")).block();
        agent.call(userMsg("hi from bob2"), ctx("s2", "bob2")).block();
        if (client.getCreateCount() != 2) {
            throw new IllegalStateException("each user should get a new sandbox");
        }
        System.out.println("[sandbox] USER: different users are isolated: OK");
    }

    static void agentScopeEveryoneSharesOneSandbox(Path workspace, Model model) throws Exception {
        Files.createDirectories(workspace);
        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.AGENT);
        InMemorySandboxClient client = spec.getClient();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("shared-assistant")
                        .model(model)
                        .workspace(workspace)
                        .filesystem(spec)
                        .build();

        agent.call(userMsg("alice says hi"), ctx("s1", "alice")).block();
        agent.call(userMsg("bob says hi"), ctx("s2", "bob")).block();
        agent.call(userMsg("charlie says hi"), ctx("s3", "charlie")).block();
        if (client.getCreateCount() != 1 || client.getResumeCount() != 2) {
            throw new IllegalStateException("AGENT scope: 1 create, 2 resume");
        }
        System.out.println("[sandbox] AGENT: all callers share one sandbox: OK");
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
