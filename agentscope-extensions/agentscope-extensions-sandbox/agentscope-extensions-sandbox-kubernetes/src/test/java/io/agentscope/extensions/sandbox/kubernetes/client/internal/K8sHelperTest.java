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
package io.agentscope.extensions.sandbox.kubernetes.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaimSpec;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxNotFoundException;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class K8sHelperTest {

    @Test
    void keepsLegacyCreateSandboxClaimOverload() throws Exception {
        assertNotNull(
                K8sHelper.class.getMethod(
                        "createSandboxClaim",
                        String.class,
                        String.class,
                        String.class,
                        Map.class,
                        Long.class,
                        Map.class,
                        Map.class));
    }

    @Test
    void buildsControllerManagedLifecycle() {
        K8sHelper helper = new K8sHelper(mock(KubernetesClient.class));

        SandboxClaim claim =
                helper.buildSandboxClaim("claim-1", "pool-1", "agents", null, 300L, 0, null, null);

        SandboxClaimSpec.Lifecycle lifecycle = claim.getSpec().getLifecycle();
        assertEquals("Delete", lifecycle.getShutdownPolicy());
        assertEquals(0, lifecycle.getTtlSecondsAfterFinished());
        // The exact timestamp is deliberately not asserted; its presence proves the offset was
        // converted into the absolute RFC-3339 shutdownTime expected by agent-sandbox.
        org.junit.jupiter.api.Assertions.assertNotNull(lifecycle.getShutdownTime());

        JsonNode lifecycleJson = new ObjectMapper().valueToTree(claim.getSpec()).path("lifecycle");
        assertEquals("Delete", lifecycleJson.path("shutdownPolicy").asText());
        assertEquals(0, lifecycleJson.path("ttlSecondsAfterFinished").asInt());
    }

    @Test
    void omitsLifecycleWhenNoPolicyIsConfigured() {
        K8sHelper helper = new K8sHelper(mock(KubernetesClient.class));

        SandboxClaim claim =
                helper.buildSandboxClaim(
                        "claim-1", "pool-1", "agents", null, null, null, null, null);

        assertNull(claim.getSpec().getLifecycle());
    }

    @Test
    void missingClaimFailsImmediatelyInsteadOfWaitingForWatchTimeout() {
        K8sHelper helper = spy(new K8sHelper(mock(KubernetesClient.class)));
        doReturn(null).when(helper).getSandboxClaim("missing", "agents");

        assertTimeout(
                Duration.ofSeconds(1),
                () ->
                        assertThrows(
                                SandboxNotFoundException.class,
                                () -> helper.resolveSandboxName("missing", "agents", 30)));
    }
}
