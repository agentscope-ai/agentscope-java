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
package io.agentscope.extensions.redis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.harness.agent.session.StoreSessionStorage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.UnifiedJedis;

/** Real Redis Lua CAS and journal fencing, with an isolated namespace per invocation. */
@EnabledIfEnvironmentVariable(named = "AGENTSCOPE_TEST_REDIS_URL", matches = ".+")
class RedisSessionStorageIntegrationTest {
    @Test
    void independentClientsRaceCasAndRecoverTheFencedCommittedPrefix() throws Exception {
        String prefix = "regression:" + UUID.randomUUID() + ":";
        String url = System.getenv("AGENTSCOPE_TEST_REDIS_URL");
        try (var firstClient = new UnifiedJedis(url);
                var secondClient = new UnifiedJedis(url)) {
            var firstStore = new RedisStore(firstClient, prefix);
            var secondStore = new RedisStore(secondClient, prefix);
            try {
                var start = new CountDownLatch(1);
                var firstCas =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    await(start);
                                    return firstStore.putIfVersion(
                                            List.of("cas"), "key", Map.of("value", "first"), 0);
                                });
                var secondCas =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    await(start);
                                    return secondStore.putIfVersion(
                                            List.of("cas"), "key", Map.of("value", "second"), 0);
                                });
                start.countDown();
                assertNotEquals(
                        firstCas.get(10, TimeUnit.SECONDS), secondCas.get(10, TimeUnit.SECONDS));
                assertEquals(1, firstStore.get(List.of("cas"), "key").version());
                var first =
                        new JournalSessionLog(
                                new StoreSessionStorage(firstStore, List.of("tenant")), "session");
                var second =
                        new JournalSessionLog(
                                new StoreSessionStorage(secondStore, List.of("tenant")), "session");
                var writer = first.acquire("run-a", Duration.ofMinutes(1));
                assertThrows(
                        SessionLogException.class,
                        () -> second.acquire("run-b", Duration.ofMinutes(1)));
                var event =
                        new SessionEvent(
                                1, "event", 1, 1, "run/start", "run-a", "turn", true, "{}");
                first.commit(writer, "batch", 0, List.of(event));
                first.commit(writer, "batch", 0, List.of(event));
                assertEquals(List.of(event), second.readAfter(0, 10));
                second.sealWriter("run-a");
                assertThrows(
                        SessionLogException.class,
                        () -> first.renew(writer, Duration.ofMinutes(1)));
                assertThrows(
                        SessionLogException.class,
                        () -> first.acquire("run-a", Duration.ofMinutes(1)));
                second.release(second.acquire("run-b", Duration.ofMinutes(1)));
                assertEquals(
                        0,
                        new JournalSessionLog(
                                        new StoreSessionStorage(secondStore, List.of("other")),
                                        "session")
                                .head()
                                .seq());
            } finally {
                for (String key : firstClient.keys(prefix + "*")) firstClient.del(key);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
