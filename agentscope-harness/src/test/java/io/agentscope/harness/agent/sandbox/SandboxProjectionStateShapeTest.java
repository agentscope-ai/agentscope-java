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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the persisted-state shape contract: deployments that never engage the grant layer
 * keep the exact pre-grant JSON (the revocation manifest is omitted when null), and the
 * per-call runtime intent fields of a projection entry are never serialized — a tampered
 * persisted state can neither self-declare managed semantics nor inject destructive
 * revoke roots.
 */
@DisplayName("Sandbox projection persisted-state shape")
class SandboxProjectionStateShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("null manifest is omitted: grant-free persisted state keeps the pre-grant shape")
    void nullManifestIsOmittedFromPersistedState() throws Exception {
        TestState state = new TestState();
        state.setProjectedWorkspacePaths(null);
        String json = MAPPER.writeValueAsString(state);
        assertFalse(json.contains("projectedWorkspacePaths"), "null manifest must be omitted");
    }

    @Test
    @DisplayName("recorded manifest persists (grant deployments opted in)")
    void recordedManifestPersists() throws Exception {
        TestState state = new TestState();
        state.setProjectedWorkspacePaths(List.of(".skills-cache/u1/market/alpha/SKILL.md"));
        String json = MAPPER.writeValueAsString(state);
        assertTrue(json.contains("projectedWorkspacePaths"), "non-null manifest is persisted");
    }

    @Test
    @DisplayName("projection entry never serializes managed intent (marker, revoke roots)")
    void projectionEntryOmitsRuntimeIntent() throws Exception {
        WorkspaceProjectionEntry entry = new WorkspaceProjectionEntry();
        entry.setSourceRoot("/tmp/src");
        entry.setIncludeRoots(List.of(".skills-cache"));
        entry.setAuthorizationManaged(true);
        entry.setRevokeRoots(List.of(".skills-cache"));
        String json = MAPPER.writeValueAsString(entry);
        assertFalse(json.contains("authorizationManaged"), "marker is per-call runtime intent");
        assertFalse(json.contains("revokeRoots"), "revoke roots are per-call runtime intent");
        assertTrue(json.contains("sourceRoot"), "the persisted fields remain");
    }

    /** Minimal concrete state: only the base bookkeeping fields are under test. */
    private static final class TestState extends SandboxState {}
}
