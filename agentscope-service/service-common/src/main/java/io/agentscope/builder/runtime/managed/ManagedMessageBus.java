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
package io.agentscope.builder.runtime.managed;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Blocking publication and SDK-owned push callbacks, kept off reactive event-loop threads. */
public interface ManagedMessageBus extends AutoCloseable {
    int MAX_BODY_BYTES = 512 * 1024;

    enum Channel {
        TURNS,
        TURN_RESUMES,
        TOOL_EVENTS,
        TOOL_EXECUTE,
        TOOL_CONTROL
    }

    record Message(String id, String messageGroup, byte[] body, int depth) {
        public Message(String id, String messageGroup, byte[] body) {
            this(id, messageGroup, body, 0);
        }

        public Message {
            ManagedTurnLanes.validate(Channel.TURNS, depth);
            ManagedScope.requireText(id, "message id");
            ManagedScope.requireText(messageGroup, "message group");
            if (messageGroup.getBytes(StandardCharsets.UTF_8).length >= 64)
                throw new IllegalArgumentException(
                        "Managed LiteTopic must be shorter than 64 bytes");
            Objects.requireNonNull(body, "body");
            if (body.length > MAX_BODY_BYTES)
                throw new IllegalArgumentException("Message requires a private payload reference");
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record Published(String brokerMessageId) {}

    @FunctionalInterface
    interface Listener {
        void consume(ManagedPushDelivery delivery) throws Exception;
    }

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    Published publish(Channel channel, Message message);

    Subscription subscribe(
            Channel channel,
            int callbackThreads,
            Listener listener,
            BiConsumer<ManagedPushDelivery, Exception> errors);

    boolean active(ManagedPushDelivery delivery);

    @Override
    void close();

    static long positiveMillis(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        long value = duration.toMillis();
        if (value <= 0) throw new IllegalArgumentException("Duration must be positive");
        return value;
    }
}
