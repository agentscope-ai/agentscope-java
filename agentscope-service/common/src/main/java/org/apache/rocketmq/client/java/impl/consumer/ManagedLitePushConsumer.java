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

import apache.rocketmq.v2.ChangeInvisibleDurationRequest;
import apache.rocketmq.v2.ChangeInvisibleDurationResponse;
import apache.rocketmq.v2.Code;
import io.agentcore.managed.server.runtime.managed.RocketMqTurnReceipt;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.client.java.rpc.RpcFuture;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.MoreExecutors;

/**
 * Narrow SDK 5.2.2 bridge for durable Turn ACKs. Same-package access is intentional: the public
 * LitePushConsumer API has no ACK operation. Keep SDK-version contract tests with this adapter.
 */
public final class ManagedLitePushConsumer extends ManagedObservedLitePushConsumer {
    public enum Ack {
        ACKNOWLEDGED,
        EXPIRED,
        RETRY
    }

    private final BiConsumer<MessageView, Duration> suspended;
    private final LitePushConsumerBuilderImpl builder;

    public ManagedLitePushConsumer(
            LitePushConsumerBuilderImpl builder, BiConsumer<MessageView, Duration> suspended) {
        super(builder);
        this.builder = builder;
        this.suspended = suspended;
    }

    @Override
    protected ConsumeService createConsumeService() {
        return new ManagedLiteFifoConsumeService(
                getClientId(),
                getConsumerGroup(),
                builder.messageListener,
                getConsumptionExecutor(),
                this,
                getScheduler(),
                builder.enableFifoConsumeAccelerator,
                suspended);
    }

    @Override
    RpcFuture<ChangeInvisibleDurationRequest, ChangeInvisibleDurationResponse>
            changeInvisibleDuration(MessageViewImpl view, Duration delay) {
        return trackReceipt(view, super.changeInvisibleDuration(view, delay));
    }

    static RpcFuture<ChangeInvisibleDurationRequest, ChangeInvisibleDurationResponse> trackReceipt(
            MessageViewImpl view,
            RpcFuture<ChangeInvisibleDurationRequest, ChangeInvisibleDurationResponse> rpc) {
        // Persist the capability returned by Suspend, not just the pre-Suspend receive handle.
        // The batch observer runs only after this transformation and every cached Suspend settles.
        return new RpcFuture<>(
                rpc.getContext(),
                rpc.getRequest(),
                Futures.transform(
                        rpc,
                        response -> {
                            if (response.getStatus().getCode() == Code.OK
                                    && !response.getReceiptHandle().isEmpty())
                                view.setReceiptHandle(response.getReceiptHandle());
                            return response;
                        },
                        MoreExecutors.directExecutor()));
    }

    /** ACK through this replica's independently authenticated client, with the original routing. */
    public static Ack acknowledge(LitePushConsumer client, RocketMqTurnReceipt receipt) {
        if (!(client instanceof ConsumerImpl consumer)) return Ack.RETRY;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            var view = receipt.restore();
            // Cached successors can have their own Suspend operations in the same pop batch.
            // Shorten the queue suspension before ACKing its terminal head, otherwise a successful
            // ACK alone can leave those successors invisible until the original long timeout.
            var wake =
                    consumer.changeInvisibleDuration(view, Duration.ofMillis(50))
                            .get(6, TimeUnit.SECONDS);
            if (wake.getStatus().getCode() == Code.INVALID_RECEIPT_HANDLE) return Ack.EXPIRED;
            if (wake.getStatus().getCode() != Code.OK) return Ack.RETRY;
            if (!wake.getReceiptHandle().isEmpty()) view.setReceiptHandle(wake.getReceiptHandle());
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return Ack.RETRY;
            var response = consumer.ackMessage(view).get(remaining, TimeUnit.NANOSECONDS);
            if (response.getStatus().getCode() == Code.INVALID_RECEIPT_HANDLE
                    || response.getEntriesList().stream()
                            .anyMatch(e -> e.getStatus().getCode() == Code.INVALID_RECEIPT_HANDLE))
                return Ack.EXPIRED;
            return response.getStatus().getCode() == Code.OK
                            && response.getEntriesCount() == 1
                            && response.getEntries(0).getMessageId().equals(receipt.messageId())
                            && response.getEntries(0).getStatus().getCode() == Code.OK
                    ? Ack.ACKNOWLEDGED
                    : Ack.RETRY;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Ack.RETRY;
        } catch (Exception unavailable) {
            return Ack.RETRY;
        }
    }
}
