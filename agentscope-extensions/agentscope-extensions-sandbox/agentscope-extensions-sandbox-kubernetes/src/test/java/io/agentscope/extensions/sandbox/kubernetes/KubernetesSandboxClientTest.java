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
package io.agentscope.extensions.sandbox.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Test;

class KubernetesSandboxClientTest {

    private static WorkspaceSpec spec() {
        return new WorkspaceSpec();
    }

    @Test
    void initStateDefaultsToOwnedClaim() {
        KubernetesSandboxClient client = new KubernetesSandboxClient();

        KubernetesSandboxState state = client.initState(spec(), null, client.merge(null));

        assertTrue(state.isClaimOwned());
        assertTrue(state.getClaimName().startsWith("as-sbx-"));
        assertFalse(state.isWorkspaceRootReady());
    }

    @Test
    void initStateHonorsClaimOwnedFalseFromDefaultOptions() {
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setClaimOwned(false);
        KubernetesSandboxClient client = new KubernetesSandboxClient(options);

        KubernetesSandboxState state = client.initState(spec(), null, client.merge(null));

        assertFalse(state.isClaimOwned());
    }

    @Test
    void callLevelClaimOwnedFalseOverridesExplicitDefault() {
        KubernetesSandboxClientOptions defaults = new KubernetesSandboxClientOptions();
        defaults.setClaimOwned(true);
        KubernetesSandboxClient client = new KubernetesSandboxClient(defaults);

        KubernetesSandboxClientOptions callOptions = new KubernetesSandboxClientOptions();
        callOptions.setClaimOwned(false);

        KubernetesSandboxState state = client.initState(spec(), null, client.merge(callOptions));

        assertFalse(state.isClaimOwned());
    }

    @Test
    void unsetCallLevelClaimOwnedKeepsExplicitDefault() {
        KubernetesSandboxClientOptions defaults = new KubernetesSandboxClientOptions();
        defaults.setClaimOwned(true);
        KubernetesSandboxClient client = new KubernetesSandboxClient(defaults);

        KubernetesSandboxClientOptions merged = client.merge(new KubernetesSandboxClientOptions());

        assertEquals(Boolean.TRUE, merged.getClaimOwned());
    }

    @Test
    void configuredClaimOwnershipOverridesPersistedFlagOnResume() {
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setClaimOwned(false);
        KubernetesSandboxClient client = new KubernetesSandboxClient(options);

        // Persisted with claimOwned=true before the option was configured.
        KubernetesSandboxState persisted = new KubernetesSandboxState();
        persisted.setClaimOwned(true);
        client.applyConfiguredClaimOwnership(persisted, client.merge(null));
        assertFalse(persisted.isClaimOwned());

        // Explicit true also wins over a persisted false.
        KubernetesSandboxClientOptions enabling = new KubernetesSandboxClientOptions();
        enabling.setClaimOwned(true);
        KubernetesSandboxClient enablingClient = new KubernetesSandboxClient(enabling);
        KubernetesSandboxState keptAlive = new KubernetesSandboxState();
        keptAlive.setClaimOwned(false);
        enablingClient.applyConfiguredClaimOwnership(keptAlive, enablingClient.merge(null));
        assertTrue(keptAlive.isClaimOwned());
    }

    @Test
    void unsetClaimOwnershipKeepsPersistedFlagOnResume() {
        KubernetesSandboxClient client =
                new KubernetesSandboxClient(new KubernetesSandboxClientOptions());

        KubernetesSandboxState persisted = new KubernetesSandboxState();
        persisted.setClaimOwned(false);
        client.applyConfiguredClaimOwnership(persisted, client.merge(null));

        assertFalse(persisted.isClaimOwned());
    }

    @Test
    void specClaimOwnedPassthroughToOptions() {
        KubernetesFilesystemSpec spec = new KubernetesFilesystemSpec().claimOwned(false);
        KubernetesSandboxClientOptions options =
                (KubernetesSandboxClientOptions) spec.clientOptions();
        assertEquals(Boolean.FALSE, options.getClaimOwned());

        KubernetesFilesystemSpec defaultSpec = new KubernetesFilesystemSpec();
        KubernetesSandboxClientOptions defaultOptions =
                (KubernetesSandboxClientOptions) defaultSpec.clientOptions();
        assertNull(defaultOptions.getClaimOwned());
    }
}
