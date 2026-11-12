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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.java.exception.ForbiddenException;
import org.apache.rocketmq.client.java.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

class ManagedMqMetricsTest {
    @Test
    void publicationFailureRetainsBrokerIdentityWithoutExposingErrorTextOrHighCardinalityTags() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ManagedMqMetrics(registry, "TOOL_EXECUTE", "tools", "producer");
            var failure =
                    new ManagedTransportException(
                            "secret SQL or payload",
                            new TooManyRequestsException(
                                    42900, "request-1", "private broker detail"));
            metrics.publishFailure(failure, "command-1");
            var detail = ManagedMqMetrics.failure(failure);
            assertEquals("42900", detail.responseCode());
            assertEquals("request-1", detail.requestId());
            assertEquals("throttled", detail.category());
            assertFalse(detail.toString().contains("private"));
            assertFalse(detail.toString().contains("secret"));
            assertEquals(1, metrics.snapshot().get("publish.failure_total"));
            assertEquals(1, metrics.snapshot().get("publish.throttled_total"));
            assertTrue(
                    registry.getMeters().stream()
                            .allMatch(meter -> meter.getId().getTags().size() == 3));
        } finally {
            registry.close();
        }
    }

    @Test
    void brokerInflightRejectionIsNotConfusedWithAuthenticationOrRateLimits() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ManagedMqMetrics(registry, "TURN_RESUMES", "resumes", "brain");
            metrics.receiveFailure(
                    new RuntimeException(
                            new ForbiddenException(
                                    40300,
                                    "request",
                                    "The client has too many unacked messages. Unacked count:"
                                            + " 137")));
            metrics.receiveFailure(new ForbiddenException(40300, "request", "Access denied"));
            metrics.receiveFailure(
                    new TooManyRequestsException(42900, "request", "Hit flow control"));
            metrics.receiveFailure(new IllegalStateException("connection unavailable"));
            assertEquals(4, metrics.snapshot().get("receive.failures_total"));
            for (String kind :
                    new String[] {"inflight_rejected", "forbidden", "throttled", "other_failure"})
                assertEquals(1, metrics.snapshot().get("receive." + kind + "_total"));
        } finally {
            registry.close();
        }
    }

    @Test
    void throttleDiagnosticsExtractOnlyNumericThresholdAndDirection() {
        var failure =
                new TooManyRequestsException(
                        42900,
                        null,
                        "requestId=camel-request, ClusterName private send topic secret-topic"
                            + " messages flow control, flow limit threshold is 3000, remainMs 154"
                            + " token=secret");
        var detail = ManagedMqMetrics.failure(failure);
        assertEquals("camel-request", detail.requestId());
        assertEquals(3000L, detail.flowLimit());
        assertEquals("send", detail.flowDirection());
        assertFalse(detail.toString().contains("secret"));
        assertFalse(detail.toString().contains("private"));
        assertEquals(
                null,
                ManagedMqMetrics.failure(
                                new TooManyRequestsException(
                                        42900,
                                        "id",
                                        "flow limit threshold is 12345678901234567890"))
                        .flowLimit());
    }

    @Test
    void timingSeparatesDeliveryFromCallbackAndAckAndNeverCountsClockSkewAsLatency() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ManagedMqMetrics(registry, "TURN_RESUMES", "resumes", "brain");
            metrics.millis("born_to_delivery", 577000);
            metrics.millis("delivery_to_receive", 8);
            metrics.millis("receive_to_callback", 12);
            metrics.millis("receive_to_ack", 6000);
            metrics.millis("delivery_to_receive", -5);
            assertEquals(
                    577,
                    registry.get("managed.mq.born_to_delivery")
                            .timer()
                            .totalTime(TimeUnit.SECONDS));
            assertEquals(1, registry.get("managed.mq.delivery_to_receive").timer().count());
            assertEquals(1, registry.get("managed.mq.clock_skew").counter().count());
            assertEquals(
                    6,
                    registry.get("managed.mq.receive_to_ack").timer().totalTime(TimeUnit.SECONDS));
            assertTrue(
                    registry.getMeters().stream()
                            .allMatch(meter -> meter.getId().getTags().size() == 3));
        } finally {
            registry.close();
        }
    }

    @Test
    void activeAndUnacknowledgedCacheAreIndependentAndSnapshotsAreImmutable() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ManagedMqMetrics(registry, "TURN_RESUMES", "resumes", "brain");
            metrics.gauge("cache.messages", 512);
            metrics.addGauge("callback.active", 1);
            metrics.count("callback.suspend", 1);
            metrics.addGauge("callback.active", -1);
            var before = metrics.snapshot();
            assertEquals(512, before.get("cache.messages"));
            assertEquals(0, before.get("callback.active"));
            assertFalse(before.containsKey("ack.success_total"));
            metrics.count("ack.success", 1);
            metrics.gauge("cache.messages", 511);
            assertEquals(512, before.get("cache.messages"));
            assertEquals(511, registry.get("managed.mq.cache.messages").gauge().value());
            assertEquals(1, registry.get("managed.mq.ack.success").counter().count());
        } finally {
            registry.close();
        }
    }
}
