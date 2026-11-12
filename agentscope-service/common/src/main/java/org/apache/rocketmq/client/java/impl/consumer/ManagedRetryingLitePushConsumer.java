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

/** SDK 5.2.2 adapter: short Resume waits retry the retained FIFO head without broker parking. */
public class ManagedRetryingLitePushConsumer extends ManagedObservedLitePushConsumer {
    private final LitePushConsumerBuilderImpl builder;
    private ManagedRetryingLiteConsumeService retries;

    public ManagedRetryingLitePushConsumer(LitePushConsumerBuilderImpl builder) {
        super(builder);
        this.builder = builder;
    }

    @Override
    protected ConsumeService createConsumeService() {
        retries =
                new ManagedRetryingLiteConsumeService(
                        getClientId(),
                        getConsumerGroup(),
                        builder.messageListener,
                        getConsumptionExecutor(),
                        this,
                        getScheduler(),
                        builder.enableFifoConsumeAccelerator);
        return retries;
    }

    @Override
    public void close() {
        if (retries != null) retries.close();
        super.close();
    }
}
