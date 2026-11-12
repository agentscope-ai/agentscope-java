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
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Listener;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Subscription;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

/** Owns one SDK callback pool. Harness execution capacity is managed independently by HarnessRuntime. */
public final class ManagedConsumerLoop implements AutoCloseable {
    @FunctionalInterface
    public interface Handler extends Listener, AutoCloseable {
        @Override
        default void close() {}
    }

    /** Bounds unacknowledged callbacks before receiving; facts must still commit before return. */
    public static Handler bounded(int callbacks, Handler delegate) {
        var capacity =
                new ManagedReceiveCapacity(new Semaphore(callbacks), Math.min(callbacks, 64));
        return new Handler() {
            @Override
            public ManagedReceiveCapacity receiveCapacity(Channel channel) {
                return capacity;
            }

            @Override
            public void consume(ManagedPushDelivery delivery) throws Exception {
                delegate.consume(delivery);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
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
