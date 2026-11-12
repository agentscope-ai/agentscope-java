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

import apache.rocketmq.v2.MessageQueue;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.rocketmq.client.java.message.MessageIdCodec;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.route.MessageQueueImpl;

/** Private, short-lived ACK capability. Never expose this value in APIs or logs. */
public record RocketMqTurnReceipt(
        String messageId,
        String topic,
        String liteTopic,
        String handle,
        String queue,
        long offset,
        long bornAt,
        int attempt) {
    public static RocketMqTurnReceipt capture(MessageViewImpl view) {
        return new RocketMqTurnReceipt(
                view.getMessageId().toString(),
                view.getTopic(),
                view.getLiteTopic().orElseThrow(),
                view.getReceiptHandle(),
                Base64.getEncoder()
                        .encodeToString(view.getMessageQueue().toProtobuf().toByteArray()),
                view.getOffset(),
                view.getBornTimestamp(),
                view.getDeliveryAttempt());
    }

    public MessageViewImpl restore() {
        try {
            var routing =
                    new MessageQueueImpl(MessageQueue.parseFrom(Base64.getDecoder().decode(queue)));
            return new MessageViewImpl(
                    MessageIdCodec.getInstance().decode(messageId),
                    topic,
                    new byte[0],
                    null,
                    null,
                    liteTopic,
                    null,
                    null,
                    List.of(),
                    Map.of(),
                    "managed-turn-ack",
                    bornAt,
                    attempt,
                    routing,
                    handle,
                    offset,
                    false,
                    null);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid private Turn receipt");
        }
    }

    public String version(String group) {
        return ManagedScope.fingerprint(group, topic, messageId, handle);
    }

    @Override
    public String toString() {
        return "RocketMqTurnReceipt[messageId=" + messageId + ", capability=redacted]";
    }
}
