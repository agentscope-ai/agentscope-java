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

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.util.List;
import java.util.Objects;

/** One local SDK callback invocation. It contains no receipt handle or remote ACK operation. */
public record ManagedPushDelivery(
        String deliveryId,
        String consumerId,
        Channel channel,
        String topic,
        String consumerGroup,
        String brokerMessageId,
        int attempt,
        Message message,
        ManagedDeliveryHandoff handoff) {
    public ManagedPushDelivery(
            String deliveryId,
            String consumerId,
            Channel channel,
            String topic,
            String consumerGroup,
            String brokerMessageId,
            int attempt,
            Message message) {
        this(
                deliveryId,
                consumerId,
                channel,
                topic,
                consumerGroup,
                brokerMessageId,
                attempt,
                message,
                new ManagedDeliveryHandoff());
    }

    public ManagedPushDelivery {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(handoff, "handoff");
        ManagedTurnLanes.validate(channel, message.depth());
        for (String value : List.of(deliveryId, consumerId, topic, consumerGroup, brokerMessageId))
            ManagedScope.requireText(value, "Push delivery identity");
        if (attempt < 0) throw new IllegalArgumentException("Invalid delivery attempt");
    }
}
