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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.apache.rocketmq.client.apis.consumer.ConsumeResultSuspend;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.java.hook.MessageInterceptor;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.misc.ClientId;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.MoreExecutors;

/** Observes the SDK's Suspend boundary after every RPC for the same cached Lite FIFO batch. */
class ManagedLiteFifoConsumeService extends LiteFifoConsumeService {
    private final BiConsumer<MessageView, Duration> suspended;

    ManagedLiteFifoConsumeService(
            ClientId clientId,
            String group,
            MessageListener listener,
            ExecutorService workers,
            MessageInterceptor interceptor,
            ScheduledExecutorService scheduler,
            boolean accelerator,
            BiConsumer<MessageView, Duration> suspended) {
        super(clientId, group, listener, workers, interceptor, scheduler, accelerator);
        this.suspended = suspended;
    }

    @Override
    protected void consumeIteratively(ProcessQueue pq, Iterator<MessageViewImpl> iterator) {
        var head = getNextValidMessage(pq, iterator);
        if (head == null) return;
        var rest = new AtomicReference<>(iterator);
        var finished =
                Futures.transformAsync(
                        consume(head),
                        result -> {
                            List<ListenableFuture<Void>> erased = new ArrayList<>();
                            erased.add(pq.eraseFifoMessage(head, result));
                            if (!(result instanceof ConsumeResultSuspend)) return erased.get(0);
                            List<MessageViewImpl> otherTopics = new ArrayList<>();
                            iterator.forEachRemaining(
                                    view -> {
                                        if (head.getLiteTopic().equals(view.getLiteTopic())) {
                                            erased.add(pq.eraseFifoMessage(view, result));
                                        } else otherTopics.add(view);
                                    });
                            rest.set(otherTopics.iterator());
                            // A batch can contain T2/T3 as well as T1. Observe only after
                            // ALL Suspend
                            // RPCs settle; an ACK racing a later cached-message Suspend
                            // could re-block FIFO.
                            return Futures.transform(
                                    Futures.allAsList(erased),
                                    ignored -> {
                                        suspended.accept(
                                                head,
                                                ((ConsumeResultSuspend) result).getSuspendTime());
                                        return (Void) null;
                                    },
                                    MoreExecutors.directExecutor());
                        },
                        MoreExecutors.directExecutor());
        finished.addListener(
                () -> consumeIteratively(pq, rest.get()), MoreExecutors.directExecutor());
    }
}
