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
