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

import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Listener;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Subscription;
import java.util.function.BiConsumer;

/** Owns one SDK callback pool. Harness execution capacity is managed independently by Brain. */
public final class ManagedConsumerLoop implements AutoCloseable {
    @FunctionalInterface
    public interface Handler extends Listener, AutoCloseable {
        @Override
        default void close() {}
    }

    private final ManagedMessageBus bus;
    private final Channel channel;
    private final int callbacks;
    private final Handler handler;
    private final BiConsumer<ManagedPushDelivery, Exception> errors;
    private Subscription subscription;

    public ManagedConsumerLoop(
            ManagedMessageBus bus,
            Channel channel,
            int callbacks,
            Handler handler,
            BiConsumer<ManagedPushDelivery, Exception> errors) {
        if (callbacks < 1) throw new IllegalArgumentException("Callback capacity must be positive");
        this.bus = bus;
        this.channel = channel;
        this.callbacks = callbacks;
        this.handler = handler;
        this.errors = errors;
    }

    public synchronized void start() {
        if (subscription != null)
            throw new IllegalStateException("Managed consumer already started");
        subscription = bus.subscribe(channel, callbacks, handler, errors);
    }

    @Override
    public synchronized void close() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
