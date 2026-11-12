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
package io.agentcore.managed.server.runtime.managed;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Shares transport continuation workers, never MCP sessions, headers or execution identities. */
public final class ManagedMcpHttpTransport {
    private static final AtomicInteger IDS = new AtomicInteger();
    // Process-lifetime daemon workers: closing one MCP client must not stop other clients.
    private static final ExecutorService WORKERS =
            Executors.newFixedThreadPool(
                    64,
                    task -> {
                        var thread = new Thread(task, "managed-mcp-http-" + IDS.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });

    private ManagedMcpHttpTransport() {}

    public static void configure(McpClientBuilder builder) {
        builder.customizeStreamableHttpClient(ManagedMcpHttpTransport::customize);
        builder.customizeSseClient(ManagedMcpHttpTransport::customize);
    }

    static void customize(HttpClient.Builder builder) {
        builder.executor(WORKERS);
    }
}
