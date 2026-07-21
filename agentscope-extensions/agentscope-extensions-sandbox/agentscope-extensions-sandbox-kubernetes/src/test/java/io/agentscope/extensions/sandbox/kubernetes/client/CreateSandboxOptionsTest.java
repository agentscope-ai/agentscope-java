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
package io.agentscope.extensions.sandbox.kubernetes.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.extensions.sandbox.kubernetes.KubernetesSandboxClient;
import io.agentscope.extensions.sandbox.kubernetes.KubernetesSandboxClientOptions;
import io.agentscope.extensions.sandbox.kubernetes.client.config.DirectConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.GatewayConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.LocalTunnelConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.SandboxConnectionConfig;
import org.junit.jupiter.api.Test;

class CreateSandboxOptionsTest {

    @Test
    void builder_defaults() {
        CreateSandboxOptions opts = CreateSandboxOptions.builder("pool-1").build();
        assertEquals("pool-1", opts.warmPool());
        assertEquals("default", opts.namespace());
        assertEquals(180, opts.sandboxReadyTimeoutSeconds());
        assertNull(opts.claimName());
        assertNull(opts.shutdownAfterSeconds());
    }

    @Test
    void builder_requiresWarmPool() {
        assertThrows(
                IllegalArgumentException.class, () -> CreateSandboxOptions.builder("").build());
    }

    @Test
    void toConnectionConfig_direct() {
        KubernetesSandboxClientOptions opts = new KubernetesSandboxClientOptions();
        opts.setApiUrl("http://router.example:8080");
        opts.setServerPort(8888);
        SandboxConnectionConfig cfg = KubernetesSandboxClient.toConnectionConfig(opts);
        assertEquals(DirectConnectionConfig.class, cfg.getClass());
        assertEquals("http://router.example:8080", ((DirectConnectionConfig) cfg).apiUrl());
    }

    @Test
    void toConnectionConfig_gateway() {
        KubernetesSandboxClientOptions opts = new KubernetesSandboxClientOptions();
        opts.setGatewayName("gw");
        opts.setGatewayNamespace("gw-ns");
        SandboxConnectionConfig cfg = KubernetesSandboxClient.toConnectionConfig(opts);
        assertEquals(GatewayConnectionConfig.class, cfg.getClass());
        assertEquals("gw", ((GatewayConnectionConfig) cfg).gatewayName());
        assertEquals("gw-ns", ((GatewayConnectionConfig) cfg).gatewayNamespace());
    }

    @Test
    void toConnectionConfig_localTunnel() {
        KubernetesSandboxClientOptions opts = new KubernetesSandboxClientOptions();
        opts.setNamespace("agents");
        SandboxConnectionConfig cfg = KubernetesSandboxClient.toConnectionConfig(opts);
        assertEquals(LocalTunnelConnectionConfig.class, cfg.getClass());
        assertEquals("agents", ((LocalTunnelConnectionConfig) cfg).routerNamespace());
    }
}
