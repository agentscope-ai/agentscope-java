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
package io.agentscope.extensions.durable.rocketmq;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.rocketmq.client.apis.message.MessageView;

import java.nio.file.Files;
import java.nio.file.Path;

/** Compiled into the test copy of the receiver immediately around its real ACK. */
final class AckGate {
    static void observe(JsonNode envelope, MessageView message, boolean acknowledged)
            throws InterruptedException {
        String kind;
        if ("EXECUTE".equals(envelope.path("kind").asText())) kind = "command";
        else if ("SUCCEEDED".equals(envelope.path("result").path("state").asText()))
            kind = "result";
        else return;
        System.out.printf(
                "%s %s %s %d %s%n",
                acknowledged ? "ACK" : "BEFORE",
                kind,
                message.getMessageId(),
                message.getDeliveryAttempt(),
                envelope.path("request").path("turnId").asText());
        while (!acknowledged && Files.exists(Path.of("/tmp/hold-" + kind))) Thread.sleep(50);
    }
}
