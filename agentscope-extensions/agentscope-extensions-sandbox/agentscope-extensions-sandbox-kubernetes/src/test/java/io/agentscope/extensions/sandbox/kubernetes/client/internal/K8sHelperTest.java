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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxNotFoundException;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;

class K8sHelperTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void missingClaimFailsWithoutWaitingForAWatch() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation resources = mock(MixedOperation.class);
        NonNamespaceOperation namespaced = mock(NonNamespaceOperation.class);
        Resource resource = mock(Resource.class);
        when(client.resources(SandboxClaim.class)).thenReturn(resources);
        when(resources.inNamespace("default")).thenReturn(namespaced);
        when(namespaced.withName("missing")).thenReturn(resource);
        when(resource.get()).thenReturn(null);

        K8sHelper helper = new K8sHelper(client);

        SandboxNotFoundException error =
                assertThrows(
                        SandboxNotFoundException.class,
                        () -> helper.resolveSandboxName("missing", "default", 30));
        assertTrue(error.getMessage().contains("missing"));
    }
}
