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
package io.agentscope.examples.harness.sandbox.support;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.filesystem.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * {@link SandboxFilesystemSpec} backed by an {@link InMemorySandboxClient}.
 *
 * <p>Uses a local temp directory as the sandbox workspace. No Docker or external services are
 * required. The {@link InMemorySandboxClient} exposes create/resume counters to illustrate
 * {@link io.agentscope.harness.agent.IsolationScope} behaviour.
 */
public class InMemorySandboxFilesystemSpec extends SandboxFilesystemSpec {

    private final InMemorySandboxClient client;

    /**
     * Creates a spec backed by the given in-memory sandbox client.
     *
     * @param client the in-memory client to use for session create/resume
     */
    public InMemorySandboxFilesystemSpec(InMemorySandboxClient client) {
        this.client = client;
    }

    /**
     * Creates a spec with a new default {@link InMemorySandboxClient}.
     */
    public InMemorySandboxFilesystemSpec() {
        this(new InMemorySandboxClient());
    }

    /**
     * Returns the underlying {@link InMemorySandboxClient}.
     *
     * @return the client
     */
    public InMemorySandboxClient getClient() {
        return client;
    }

    @Override
    protected SandboxClient<?> createClient() {
        return client;
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return null;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return null;
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        WorkspaceSpec s = new WorkspaceSpec();
        s.setRoot("/workspace");
        return s;
    }
}
