/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.harness.agent.tools;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpInitializationOrderTest {
    @Test
    void initializesBeforeDiscoveringTools() {
        var client = mock(McpClientWrapper.class);
        var initialized = new AtomicBoolean();
        when(client.initialize()).thenReturn(Mono.fromRunnable(() -> initialized.set(true)));
        when(client.listTools())
                .thenAnswer(
                        ignored ->
                                initialized.get()
                                        ? Mono.just(List.of())
                                        : Mono.error(
                                                new IllegalStateException(
                                                        "Client not initialized")));

        McpServerRegistrar.registerClient(new Toolkit(), "workflow", new McpServerConfig(), client);

        var order = inOrder(client);
        order.verify(client).initialize();
        order.verify(client).listTools();
        order.verify(client).close();
    }
}
