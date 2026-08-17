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
package io.agentscope.spring.boot.admin.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MetricsRecorderTest {

    @Test
    void accumulatesAcrossAxes() {
        MetricsRecorder r = new MetricsRecorder();
        r.record("alice", "qwen-plus", 100, 50);
        r.record("alice", "qwen-plus", 30, 20);
        r.record("bob", "gpt-4.1-mini", 5, 5);

        UsageStats global = r.globalSnapshot();
        assertThat(global.calls()).isEqualTo(3);
        assertThat(global.inputTokens()).isEqualTo(135);
        assertThat(global.outputTokens()).isEqualTo(75);
        assertThat(global.totalTokens()).isEqualTo(210);

        assertThat(r.snapshotByAgent()).containsOnlyKeys("alice", "bob");
        assertThat(r.snapshotByAgent().get("alice").totalTokens()).isEqualTo(200);
        assertThat(r.snapshotByAgent().get("alice").calls()).isEqualTo(2);

        assertThat(r.snapshotByModel()).containsOnlyKeys("qwen-plus", "gpt-4.1-mini");
        assertThat(r.snapshotByModel().get("gpt-4.1-mini").totalTokens()).isEqualTo(10);
    }

    @Test
    void clampsNegativeInputs() {
        MetricsRecorder r = new MetricsRecorder();
        r.record("a", "m", -100, -50);
        assertThat(r.globalSnapshot().totalTokens()).isZero();
        assertThat(r.globalSnapshot().calls()).isEqualTo(1);
    }

    @Test
    void ignoresBlankBucketKeys() {
        MetricsRecorder r = new MetricsRecorder();
        r.record(null, null, 10, 5);
        r.record("", " ", 10, 5);
        assertThat(r.snapshotByAgent()).isEmpty();
        assertThat(r.snapshotByModel()).isEmpty();
        assertThat(r.globalSnapshot().totalTokens()).isEqualTo(30);
    }

    @Test
    void concurrentRecordingIsLossless() throws Exception {
        MetricsRecorder r = new MetricsRecorder();
        int threads = 16;
        int per = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(
                    () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int n = 0; n < per; n++) {
                            r.record("worker", "m", 1, 1);
                        }
                    });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(r.globalSnapshot().calls()).isEqualTo((long) threads * per);
        assertThat(r.globalSnapshot().totalTokens()).isEqualTo(2L * threads * per);
    }

    @Test
    void resetClearsCounters() {
        MetricsRecorder r = new MetricsRecorder();
        r.record("a", "m", 1, 1);
        r.reset();
        assertThat(r.globalSnapshot().calls()).isZero();
        assertThat(r.snapshotByAgent()).isEmpty();
        assertThat(r.snapshotByModel()).isEmpty();
    }
}
