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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

class KubernetesSandboxClientClientReuseTest {

    @Test
    void reusesOneClientForTheTemplateConfig() {
        Config config = new ConfigBuilder().withMasterUrl("http://127.0.0.1:1").build();
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setKubernetesConfig(config);
        KubernetesSandboxClient client = new KubernetesSandboxClient(options);

        KubernetesClient first = client.resolveClient(options);
        KubernetesClient second = client.resolveClient(options);

        assertSame(first, second);
        first.close();
    }

    @Test
    void returnsCallerSuppliedClientAsIs() {
        KubernetesClient supplied = mock(KubernetesClient.class);
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setKubernetesClient(supplied);
        KubernetesSandboxClient client = new KubernetesSandboxClient();

        assertSame(supplied, client.resolveClient(options));
    }
}
