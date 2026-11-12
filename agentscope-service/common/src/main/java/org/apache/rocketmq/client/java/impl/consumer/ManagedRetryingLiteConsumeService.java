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
package org.apache.rocketmq.client.java.impl.consumer;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.ConsumeResultSuspend;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.java.hook.MessageInterceptor;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.misc.ClientId;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.MoreExecutors;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.SettableFuture;

/** Retains the SDK FIFO head unacknowledged while short capacity waits release callback threads. */
final class ManagedRetryingLiteConsumeService extends LiteFifoConsumeService
        implements AutoCloseable {
    private final ScheduledExecutorService timer;
    private final Set<Retry> waiting = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    ManagedRetryingLiteConsumeService(
            ClientId id,
            String group,
            MessageListener listener,
            ExecutorService workers,
            MessageInterceptor interceptor,
            ScheduledExecutorService scheduler,
            boolean accelerator) {
        super(id, group, listener, workers, interceptor, scheduler, accelerator);
        timer = scheduler;
    }

    @Override
    public ListenableFuture<ConsumeResult> consume(MessageViewImpl view, Duration delay) {
        var retry = new Retry(view);
        waiting.add(retry);
        retry.result.addListener(
                () -> {
                    waiting.remove(retry);
                    retry.cancelPending();
                },
                MoreExecutors.directExecutor());
        retry.schedule(delay);
        return retry.result;
    }

    private final class Retry {
        final MessageViewImpl view;
        final SettableFuture<ConsumeResult> result = SettableFuture.create();
        Future<?> pending;

        Retry(MessageViewImpl view) {
            this.view = view;
        }

        synchronized void schedule(Duration delay) {
            if (result.isDone()) return;
            if (closed) {
                result.cancel(false);
                return;
            }
            try {
                pending =
                        timer.schedule(
                                this::attempt, Math.max(0, delay.toNanos()), TimeUnit.NANOSECONDS);
            } catch (RuntimeException stopped) {
                result.setException(stopped);
            }
        }

        synchronized void attempt() {
            if (result.isDone()) return;
            if (closed) {
                result.cancel(false);
                return;
            }
            try {
                var attempt = ManagedRetryingLiteConsumeService.super.consume(view, Duration.ZERO);
                pending = attempt;
                Futures.addCallback(
                        attempt,
                        new FutureCallback<ConsumeResult>() {
                            @Override
                            public void onSuccess(ConsumeResult value) {
                                if (value instanceof ConsumeResultSuspend suspended)
                                    schedule(suspended.getSuspendTime());
                                else result.set(value);
                            }

                            @Override
                            public void onFailure(Throwable failure) {
                                result.setException(failure);
                            }
                        },
                        MoreExecutors.directExecutor());
            } catch (RuntimeException stopped) {
                result.setException(stopped);
            }
        }

        synchronized void cancelPending() {
            if (pending != null) pending.cancel(false);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (var retry : waiting) retry.result.cancel(false);
    }
}
