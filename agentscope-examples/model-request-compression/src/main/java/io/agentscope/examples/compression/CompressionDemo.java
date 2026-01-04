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
package io.agentscope.examples.compression;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.examples.compression.extra.CompressingOkHttpTransport;
import io.agentscope.examples.compression.extra.CompressionConfig;
import io.agentscope.examples.compression.extra.CompressionEncoding;

/**
 * HTTP Compression Example.
 *
 * <p>This example demonstrates how to add compression support for HTTP requests and responses.
 *
 * <h2>Use Case</h2>
 * <p>In LLM applications, HTTP message compression is very useful, especially in scenarios
 * with long conversations or long contexts. Compression can significantly reduce data
 * transfer volume, improve response speed, and lower bandwidth costs.
 *
 * <h2>Supported Compression Algorithms</h2>
 * <ul>
 *   <li><b>GZIP</b> - Universal support, good compression ratio, supported by all servers</li>
 *   <li><b>Brotli</b> - 20-26% better compression than GZIP, widely supported by modern browsers and CDNs</li>
 *   <li><b>Zstd</b> - Extremely fast compression/decompression, excellent ratio, ideal for high-performance scenarios</li>
 * </ul>
 *
 * <h2>How to Use Compression in Your AgentScope Application</h2>
 * <p>If you want to use HTTP message compression in your own AgentScope application,
 * you need to implement the classes in the {@code extra} package like this example:
 * <ul>
 *   <li>{@link io.agentscope.examples.compression.extra.CompressionEncoding} - Compression encoding enum</li>
 *   <li>{@link io.agentscope.examples.compression.extra.CompressionUtils} - Compression utility class</li>
 *   <li>{@link io.agentscope.examples.compression.extra.CompressionConfig} - Compression configuration</li>
 *   <li>{@link io.agentscope.examples.compression.extra.CompressingOkHttpTransport} - HTTP transport with compression support</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // 1. Create compression config
 * CompressionConfig compressionConfig = CompressionConfig.builder()
 *     .enableGzipCompression()  // Enable GZIP compression (request and response)
 *     .build();
 *
 * // 2. Create transport with compression support
 * CompressingOkHttpTransport transport = CompressingOkHttpTransport.builder()
 *     .compressionConfig(compressionConfig)
 *     .build();
 *
 * // 3. Use compression transport in your model
 * DashScopeChatModel model = DashScopeChatModel.builder()
 *     .apiKey("your-api-key")
 *     .modelName("qwen-max")
 *     .httpTransport(transport)
 *     .build();
 * }</pre>
 */
public class CompressionDemo {

    public static void main(String[] args) {
        // 1. Create compression config
        CompressionConfig compressionConfig =
                CompressionConfig.builder()
                        .requestCompression(CompressionEncoding.BROTLI)
                        .acceptEncoding(CompressionEncoding.GZIP)
                        .build();

        // 2. Create transport with the compression config
        CompressingOkHttpTransport transport =
                CompressingOkHttpTransport.builder().compressionConfig(compressionConfig).build();

        // 3. Create Model with CompressingTransport
        DashScopeChatModel model =
                DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        // Please ensure that your server supports message compression and
                        // decompression.
                        // If it doesn't, you can use a proxy that supports message compression and
                        // decompression,
                        // such as a gateway service.
                        .baseUrl(System.getenv("DASHSCOPE_BASE_URL"))
                        .modelName("qwen-max")
                        .httpTransport(transport) // use compressing transport
                        .build();

        // 4. Create ReActAgent
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("You are a helpful Assistant")
                        .model(model)
                        .memory(new InMemoryMemory())
                        .maxIters(10)
                        .build();

        // 5. Use Agent
        Msg userMessage =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("Hello! Please introduce yourself.")
                                        .build())
                        .build();

        Msg response = agent.call(userMessage).block();
        if (response == null) {
            System.err.println("Response is null");
        } else {
            System.out.println("Response: " + response.getFirstContentBlock());
        }
    }
}
