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
package io.agentscope.harness.coding.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Centralized Micrometer metrics for the coding agent.
 *
 * <p>Metrics are exposed via Spring Boot Actuator ({@code /actuator/prometheus}) and can be
 * scraped by Prometheus or shipped to an OTel collector via the Micrometer OTel bridge.
 *
 * <p>Key metrics:
 *
 * <ul>
 *   <li>{@code coding_agent.webhook.received} — total GitHub webhooks received (tagged by event)
 *   <li>{@code coding_agent.webhook.duplicate} — deduplicated webhook deliveries
 *   <li>{@code coding_agent.dispatch.total} — total agent dispatches (tagged by agent_id)
 *   <li>{@code coding_agent.dispatch.errors} — failed dispatches
 *   <li>{@code coding_agent.model.calls} — LLM calls across all threads
 *   <li>{@code coding_agent.findings.added} — reviewer findings added
 *   <li>{@code coding_agent.review.published} — PR reviews published to GitHub
 *   <li>{@code coding_agent.dispatch.duration} — end-to-end dispatch latency
 * </ul>
 */
public class CodingAgentMetrics {

    private final Counter webhookReceived;
    private final Counter webhookDuplicate;
    private final Counter dispatchTotal;
    private final Counter dispatchErrors;
    private final Counter modelCalls;
    private final Counter findingsAdded;
    private final Counter reviewsPublished;
    private final Timer dispatchDuration;

    public CodingAgentMetrics(MeterRegistry registry) {
        this.webhookReceived =
                Counter.builder("coding_agent.webhook.received")
                        .description("Total GitHub webhooks received")
                        .register(registry);
        this.webhookDuplicate =
                Counter.builder("coding_agent.webhook.duplicate")
                        .description("Duplicate webhook deliveries skipped")
                        .register(registry);
        this.dispatchTotal =
                Counter.builder("coding_agent.dispatch.total")
                        .description("Total agent run dispatches")
                        .register(registry);
        this.dispatchErrors =
                Counter.builder("coding_agent.dispatch.errors")
                        .description("Agent dispatch failures")
                        .register(registry);
        this.modelCalls =
                Counter.builder("coding_agent.model.calls")
                        .description("Total LLM model calls across all threads")
                        .register(registry);
        this.findingsAdded =
                Counter.builder("coding_agent.findings.added")
                        .description("Reviewer findings added")
                        .register(registry);
        this.reviewsPublished =
                Counter.builder("coding_agent.review.published")
                        .description("PR reviews published to GitHub")
                        .register(registry);
        this.dispatchDuration =
                Timer.builder("coding_agent.dispatch.duration")
                        .description("End-to-end agent dispatch latency")
                        .register(registry);
    }

    public void recordWebhookReceived() {
        webhookReceived.increment();
    }

    public void recordWebhookDuplicate() {
        webhookDuplicate.increment();
    }

    public void recordDispatch() {
        dispatchTotal.increment();
    }

    public void recordDispatchError() {
        dispatchErrors.increment();
    }

    public void recordModelCall() {
        modelCalls.increment();
    }

    public void recordFindingAdded() {
        findingsAdded.increment();
    }

    public void recordReviewPublished() {
        reviewsPublished.increment();
    }

    public Timer getDispatchDuration() {
        return dispatchDuration;
    }
}
