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
package io.agentscope.harness.agent.tools;

import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class McpConnectionLifecycleTest {
    @Test
    void requiredFailureHasSafeTypedIdentity() {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setTransport("invalid");
        cfg.setRequired(true);
        var error =
                Assertions.assertThrows(
                        McpConnectionException.class,
                        () -> McpServerRegistrar.register(new Toolkit(), Map.of("crm", cfg)));
        Assertions.assertEquals("crm", error.getServerName());
    }

    @Test
    void optionalFailureIsReportedWithoutAbortingBootstrap() {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setTransport("invalid");
        AtomicReference<McpConnectionException> observed = new AtomicReference<>();
        cfg.setConnectionFailureHandler(observed::set);
        McpServerRegistrar.register(new Toolkit(), Map.of("crm", cfg));
        Assertions.assertEquals("crm", observed.get().getServerName());
    }
}
