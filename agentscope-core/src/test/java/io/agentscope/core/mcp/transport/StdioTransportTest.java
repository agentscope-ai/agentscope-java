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

package io.agentscope.core.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.mcp.message.JsonRpcRequest;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class StdioTransportTest {

    @Mock private BufferedReader mockReader;

    @Mock private PrintWriter mockWriter;

    public StdioTransportTest() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testStdioTransportCreation() throws Exception {
        StdioTransport transport = new StdioTransport();
        assertNotNull(transport);
        // Verify it's connected by default
        Field connectedField = StdioTransport.class.getDeclaredField("connected");
        connectedField.setAccessible(true);
        assertTrue((boolean) connectedField.get(transport));
    }

    @Test
    void testSendMessageWhenConnected() throws Exception {
        StdioTransport transport = new StdioTransport();
        JsonRpcRequest msg = new JsonRpcRequest(Optional.of("1"), "test", Optional.empty());
        // Should not throw
        assertDoesNotThrow(() -> transport.send(msg));
    }

    @Test
    void testSendMessageWhenDisconnected() throws Exception {
        StdioTransport transport = new StdioTransport();
        // Disconnect
        Field connectedField = StdioTransport.class.getDeclaredField("connected");
        connectedField.setAccessible(true);
        connectedField.set(transport, false);

        JsonRpcRequest msg = new JsonRpcRequest(Optional.of("1"), "test", Optional.empty());
        assertThrows(TransportException.class, () -> transport.send(msg));
    }

    @Test
    void testTransportIsInstance() {
        StdioTransport transport = new StdioTransport();
        assertTrue(transport instanceof Transport);
    }
}
