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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KubernetesSandboxClientTest {

    @Test
    void claimWithoutLifecycleKeepsCompatibilityOwnershipDefault() {
        assertTrue(KubernetesSandboxClient.resolveClaimOwned(new KubernetesSandboxClientOptions()));
    }

    @Test
    void hardShutdownDelegatesDeletionToController() {
        KubernetesSandboxClientOptions shutdownOptions = new KubernetesSandboxClientOptions();
        shutdownOptions.setShutdownAfterSeconds(300L);
        assertFalse(KubernetesSandboxClient.resolveClaimOwned(shutdownOptions));
    }

    @Test
    void finishedOnlyTtlDoesNotImplicitlyDelegateDeletion() {
        KubernetesSandboxClientOptions finishedOptions = new KubernetesSandboxClientOptions();
        finishedOptions.setTtlSecondsAfterFinished(0);
        assertTrue(KubernetesSandboxClient.resolveClaimOwned(finishedOptions));
    }

    @Test
    void explicitClaimOwnershipOverridesLifecycleDefault() {
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setShutdownAfterSeconds(300L);
        options.setClaimOwned(true);

        assertTrue(KubernetesSandboxClient.resolveClaimOwned(options));
    }

    @Test
    void resumeAppliesExplicitOwnershipToPersistedState() {
        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setClaimOwned(true);
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setClaimOwned(false);

        KubernetesSandboxClient.applyConfiguredClaimOwnership(state, options);

        assertFalse(state.isClaimOwned());
    }

    @Test
    void lifecycleDoesNotRetainLegacyClaimThatLacksLifecycleSpec() {
        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setClaimOwned(true);
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setTtlSecondsAfterFinished(60);

        KubernetesSandboxClient.applyConfiguredClaimOwnership(state, options);

        assertTrue(state.isClaimOwned());
    }

    @Test
    void mergePreservesDefaultAndCallLifecycleOptions() {
        KubernetesSandboxClientOptions defaults = new KubernetesSandboxClientOptions();
        defaults.setShutdownAfterSeconds(600L);
        KubernetesSandboxClient client = new KubernetesSandboxClient(defaults);

        KubernetesSandboxClientOptions calls = new KubernetesSandboxClientOptions();
        calls.setTtlSecondsAfterFinished(30);
        calls.setClaimOwned(false);
        KubernetesSandboxClientOptions merged = client.merge(calls);

        assertEquals(600L, merged.getShutdownAfterSeconds());
        assertEquals(30, merged.getTtlSecondsAfterFinished());
        assertEquals(false, merged.getClaimOwned());
    }

    @Test
    void filesystemSpecExposesLifecycleOptions() {
        KubernetesFilesystemSpec spec =
                new KubernetesFilesystemSpec()
                        .claimOwned(false)
                        .shutdownAfterSeconds(900)
                        .ttlSecondsAfterFinished(10);

        KubernetesSandboxClientOptions options =
                (KubernetesSandboxClientOptions) spec.clientOptions();
        assertEquals(false, options.getClaimOwned());
        assertEquals(900L, options.getShutdownAfterSeconds());
        assertEquals(10, options.getTtlSecondsAfterFinished());
    }
}
