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

/* Copyright 2024-2026 the original author or authors. Licensed under Apache-2.0. */
package io.agentscope.extensions.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FeishuChannelCallbackTest {
    @Test
    void authenticatesEventAndRetriesSameMessageAfterPersistenceFailure() {
        String id = UUID.randomUUID().toString();
        var channel =
                FeishuChannel.fromProperties(
                        id,
                        ChannelConfig.of(id, "agent"),
                        Map.of(
                                "appId",
                                "app",
                                "appSecret",
                                "secret",
                                "verificationToken",
                                "verify"));
        AtomicInteger calls = new AtomicInteger();
        channel.init(
                new Gateway() {
                    public void bindMainAgent(HarnessAgent agent) {}

                    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
                        return calls.incrementAndGet() == 1
                                ? Mono.error(new IllegalStateException("control plane unavailable"))
                                : Mono.empty();
                    }
                });
        channel.start();
        try {
            String body =
                    """
                    {"header":{"tenant_key":"org","event_id":"same-event","token":"verify"},"event":{
                    "sender":{"sender_id":{"open_id":"human"},"sender_type":"user"},
                    "message":{"message_id":"same-message","chat_id":"room","chat_type":"p2p",
                    "message_type":"text","content":"{\\"text\\":\\"hello\\"}"}}}
                    """;
            var controller = new FeishuCallbackController();
            assertEquals(
                    401,
                    controller
                            .callback(id, null, null, null, body.replace("verify", "forged"))
                            .block(Duration.ofSeconds(3))
                            .getStatusCode()
                            .value());
            assertEquals(0, calls.get());
            assertEquals(
                    503,
                    controller
                            .callback(id, null, null, null, body)
                            .block(Duration.ofSeconds(3))
                            .getStatusCode()
                            .value());
            assertEquals(
                    200,
                    controller
                            .callback(id, null, null, null, body)
                            .block(Duration.ofSeconds(3))
                            .getStatusCode()
                            .value());
            assertEquals(2, calls.get());
        } finally {
            channel.stop();
        }
    }
}
