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
package io.agentscope.extensions.model.dashscope;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@DisplayName("DashScope Non-Streaming Blocking Behavior Tests")
class DashScopeNonStreamingBlockingBehaviorTest {

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("DashScopeChatModel - Should be NON-BLOCKING in non-streaming mode")
    void testDashScopeChatModelNonBlocking() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey("test-key").modelName("qwen-max").stream(false)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        CountDownLatch latch = new CountDownLatch(1);
        String currentThreadName = Thread.currentThread().getName();
        AtomicReference<String> streamThreadName = new AtomicReference<>();
        model.stream(messages, null, null)
                .subscribe(
                        response -> {
                            streamThreadName.set(Thread.currentThread().getName());
                            latch.countDown();
                        },
                        error -> latch.countDown());
        latch.await(3, TimeUnit.SECONDS);
        assertNotNull(streamThreadName.get());
        assertNotEquals(
                currentThreadName,
                streamThreadName.get(),
                "DashScopeChatModel should be NON-BLOCKING");
    }
}
