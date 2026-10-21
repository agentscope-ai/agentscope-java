/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.model;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.time.Duration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Demonstrates caller-owned stream/response schedulers shared by a model transport. */
public final class ModelTransportExample {
    private ModelTransportExample() {}

    public static void main(String[] args) {
        // Example capacities: choose these using the deployment's active-stream measurements.
        Scheduler reads = Schedulers.newBoundedElastic(128, 256, "app-model-reads");
        Scheduler responses = Schedulers.newBoundedElastic(32, 256, "app-model-responses");
        JdkHttpTransport transport =
                JdkHttpTransport.builder()
                        .streamScheduler(reads)
                        .responseScheduler(responses)
                        .config(
                                HttpTransportConfig.builder()
                                        .responsePrefetch(16)
                                        .responseTimeout(Duration.ofSeconds(60))
                                        .streamIdleTimeout(Duration.ofSeconds(30))
                                        .build())
                        .build();
        try {
            DashScopeChatModel model =
                    DashScopeChatModel.builder()
                            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                            .modelName("qwen-plus")
                            .stream(true)
                            .formatter(new DashScopeChatFormatter())
                            .httpTransport(transport)
                            .build();
            ReActAgent agent = ReActAgent.builder().name("assistant").model(model).build();
            agent.streamEvents("Explain thread pool isolation in one sentence.")
                    .ofType(TextBlockDeltaEvent.class)
                    .doOnNext(event -> System.out.print(event.getDelta()))
                    .blockLast();
            System.out.println();
        } finally {
            // Application shutdown: stop every user of the transport before disposing its pools.
            transport.close();
            reads.dispose();
            responses.dispose();
        }
    }
}
