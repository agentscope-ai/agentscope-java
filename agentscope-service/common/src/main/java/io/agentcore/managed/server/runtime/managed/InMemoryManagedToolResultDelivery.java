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

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Explicit test delivery backend; never selected by production dependency failure. */
public final class InMemoryManagedToolResultDelivery implements ManagedToolResultDelivery {
    private final Map<String, Message> values = new HashMap<>();

    @Override
    public synchronized void publish(ManagedScope scope, String task, Message message) {
        String key = ManagedToolResultDelivery.key(scope, task);
        var old = values.get(key);
        if (!scope.messageGroup().equals(message.messageGroup()))
            throw new IllegalArgumentException("Wrong result Scope");
        if (old != null
                && (!old.id().equals(message.id()) || !Arrays.equals(old.body(), message.body())))
            throw new ManagedStateException(
                    "tool_result_conflict", "Mailbox result identity was reused");
        values.put(key, message);
        notifyAll();
    }

    @Override
    public synchronized Optional<Message> await(ManagedScope scope, String task, Duration block) {
        String key = ManagedToolResultDelivery.key(scope, task);
        if (!values.containsKey(key) && !block.isZero()) {
            try {
                wait(ManagedMessageBus.positiveMillis(block));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new ManagedTransportException("Result wait interrupted", stopped);
            }
        }
        return Optional.ofNullable(values.get(key));
    }
}
