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

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class KubernetesTimeoutOptionsTest {

    @Test
    void callOptionsOverrideEveryKubernetesTimeout() throws Exception {
        KubernetesSandboxClientOptions defaults = new KubernetesSandboxClientOptions();
        defaults.setSandboxReadyTimeoutSeconds(101);
        defaults.setCleanupTimeoutSeconds(102);
        defaults.setRequestTimeoutSeconds(103);
        defaults.setPerAttemptTimeoutSeconds(104);
        defaults.setPortForwardTimeoutSeconds(105);

        KubernetesSandboxClientOptions call = new KubernetesSandboxClientOptions();
        call.setSandboxReadyTimeoutSeconds(201);
        call.setCleanupTimeoutSeconds(202);
        call.setRequestTimeoutSeconds(203);
        call.setPerAttemptTimeoutSeconds(204);
        call.setPortForwardTimeoutSeconds(205);

        KubernetesSandboxClient client = new KubernetesSandboxClient(defaults);
        Method merge =
                KubernetesSandboxClient.class.getDeclaredMethod(
                        "merge", KubernetesSandboxClientOptions.class);
        merge.setAccessible(true);
        KubernetesSandboxClientOptions merged =
                (KubernetesSandboxClientOptions) merge.invoke(client, call);

        assertEquals(201, merged.getSandboxReadyTimeoutSeconds());
        assertEquals(202, merged.getCleanupTimeoutSeconds());
        assertEquals(203, merged.getRequestTimeoutSeconds());
        assertEquals(204, merged.getPerAttemptTimeoutSeconds());
        assertEquals(205, merged.getPortForwardTimeoutSeconds());
    }
}
