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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KubernetesSandboxStateSerdeTest {

    @Test
    void roundTripKubernetesState() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule())
                        .registerModule(new KubernetesHarnessSandboxJacksonModule());

        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("s1");
        state.setNamespace("ns1");
        state.setClaimName("claim-1");
        state.setSandboxName("sandbox-1");
        state.setWarmPoolName("pool-1");
        state.setPodName("pod-1");
        state.setPodIP("10.0.0.1");
        state.setWorkspaceRoot("/workspace");
        state.setFileApiBaseDir("/workspace");
        state.setClaimOwned(true);
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot("/tmp/host");
        state.setWorkspaceSpec(ws);

        String json = mapper.writeValueAsString(state);
        SandboxState read = mapper.readValue(json, SandboxState.class);
        Assertions.assertInstanceOf(KubernetesSandboxState.class, read);
        KubernetesSandboxState k = (KubernetesSandboxState) read;
        Assertions.assertEquals("ns1", k.getNamespace());
        Assertions.assertEquals("claim-1", k.getClaimName());
        Assertions.assertEquals("sandbox-1", k.getSandboxName());
        Assertions.assertEquals("pool-1", k.getWarmPoolName());
        Assertions.assertEquals("pod-1", k.getPodName());
        Assertions.assertEquals("10.0.0.1", k.getPodIP());
        Assertions.assertEquals("/workspace", k.getWorkspaceRoot());
        Assertions.assertEquals("/workspace", k.getFileApiBaseDir());
        Assertions.assertTrue(k.isClaimOwned());
    }
}
