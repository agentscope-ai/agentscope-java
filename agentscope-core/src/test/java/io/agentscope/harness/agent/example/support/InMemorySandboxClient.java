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
package io.agentscope.harness.agent.example.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only {@link SandboxClient} that allocates local temp directories as sandboxes. */
public class InMemorySandboxClient implements SandboxClient<SandboxClientOptions> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final AtomicInteger createCount = new AtomicInteger(0);
    private final AtomicInteger resumeCount = new AtomicInteger(0);
    private final Path baseDir;

    public InMemorySandboxClient() {
        try {
            this.baseDir = Files.createTempDirectory("agentscope-inmemory-sandbox-");
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to create base temp dir for InMemorySandboxClient", e);
        }
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            SandboxClientOptions options) {
        createCount.incrementAndGet();
        String sessionId = UUID.randomUUID().toString();
        Path workspaceDir = baseDir.resolve(sessionId);
        try {
            Files.createDirectories(workspaceDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create workspace dir", e);
        }

        InMemorySandboxState state = new InMemorySandboxState(sessionId, workspaceDir.toString());
        WorkspaceSpec spec = workspaceSpec != null ? workspaceSpec.copy() : new WorkspaceSpec();
        spec.setRoot(workspaceDir.toString());
        state.setWorkspaceSpec(spec);

        return new InMemorySandbox(state, DEFAULT_TIMEOUT_SECONDS);
    }

    @Override
    public Sandbox resume(SandboxState sessionState) {
        resumeCount.incrementAndGet();
        InMemorySandboxState state = (InMemorySandboxState) sessionState;
        return new InMemorySandbox(state, DEFAULT_TIMEOUT_SECONDS);
    }

    @Override
    public void delete(Sandbox session) {
        // no-op
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            InMemorySandboxState s = (InMemorySandboxState) state;
            return MAPPER.writeValueAsString(new StateDto(s.getSessionId(), s.getWorkspaceRoot()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            StateDto dto = MAPPER.readValue(json, StateDto.class);
            InMemorySandboxState state =
                    new InMemorySandboxState(dto.sessionId(), dto.workspaceRoot());
            state.setWorkspaceRootReady(true);
            return state;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize sandbox state", e);
        }
    }

    public int getCreateCount() {
        return createCount.get();
    }

    public int getResumeCount() {
        return resumeCount.get();
    }

    public void resetCounts() {
        createCount.set(0);
        resumeCount.set(0);
    }

    record StateDto(String sessionId, String workspaceRoot) {}
}
