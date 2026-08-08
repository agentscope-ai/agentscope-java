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
package io.agentscope.extensions.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.PortBinding;
import io.agentscope.core.state.State;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.store.RedisStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * End-to-end tests against a real Redis Cluster (6 nodes: 3 masters + 3 replicas).
 *
 * <p>These verify the things that only a real cluster can prove:
 *
 * <ol>
 *   <li>multi-key Lua {@code EVAL} (SAVE_SCRIPT / PUT_SCRIPT) does not raise {@code CROSSSLOT}
 *       thanks to the hash-tag key layout;
 *   <li>{@code listSessionIds} returns sessions living on <em>every</em> master node, not just the
 *       one the SCAN happens to land on — for both the Lettuce and the Jedis adapter.
 * </ol>
 *
 * <p><strong>Topology.</strong> A single {@code redis:7-alpine} container runs six
 * {@code redis-server} processes on fixed ports 7000-7005, each announcing itself as
 * {@code 127.0.0.1:<port>}. The container publishes 7000-7005 to the same host ports, so the test
 * JVM (on the host) reaches every node via {@code 127.0.0.1:<port>} regardless of OS. This avoids
 * the Linux-only {@code host} network mode and runs on Windows / macOS Docker Desktop too. Bus
 * ports 17000-17005 stay inside the container where gossip needs them.
 *
 * <p>Tests are skipped (not failed) when Docker or the fixed ports are unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisClusterIntegrationTest {

    private static final String IMAGE = "redis:7-alpine";
    private static final int BASE_PORT = 7000;
    private static final int NODE_COUNT = 6;

    /** Boot six redis-server instances, form a 3-master + 3-replica cluster, then stay alive. */
    private static final String BOOT_SCRIPT =
            "for p in 7000 7001 7002 7003 7004 7005; do redis-server --port $p --cluster-enabled"
                + " yes --cluster-config-file nodes-$p.conf --cluster-node-timeout 10000"
                + " --cluster-announce-ip 127.0.0.1 --cluster-announce-port $p"
                + " --cluster-announce-bus-port $((p+10000)) --daemonize yes --appendonly no"
                + " --protected-mode no; done; sleep 1; redis-cli --cluster create 127.0.0.1:7000"
                + " 127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 127.0.0.1:7004 127.0.0.1:7005"
                + " --cluster-replicas 1 --cluster-yes; sleep 1; tail -f /dev/null";

    private GenericContainer<?> node;
    private RedisClusterClient lettuceClient;
    private RedisAgentStateStore stateStore;
    private redis.clients.jedis.RedisClusterClient jedisCluster;
    private RedisAgentStateStore jedisStateStore;
    private RedisStore redisStore;

    @BeforeAll
    void startCluster() {
        try {
            PortBinding[] portBindings = new PortBinding[NODE_COUNT];
            int[] ports = new int[NODE_COUNT];
            for (int i = 0; i < NODE_COUNT; i++) {
                ports[i] = BASE_PORT + i;
                portBindings[i] = PortBinding.parse(ports[i] + ":" + ports[i]);
            }
            node =
                    new GenericContainer<>(IMAGE)
                            .withCreateContainerCmdModifier(
                                    cmd -> cmd.withPortBindings(portBindings))
                            .withCommand("sh", "-c", BOOT_SCRIPT)
                            .waitingFor(Wait.forListeningPorts(ports));
            node.start();
            System.out.println("IT: container started, waiting for cluster_state:ok");
            awaitClusterReady();
            System.out.println("IT: cluster ready");

            lettuceClient = RedisClusterClient.create(RedisURI.create("127.0.0.1", BASE_PORT));
            stateStore =
                    RedisAgentStateStore.builder()
                            .lettuceClusterClient(lettuceClient)
                            .keyPrefix("it:session:")
                            .build();

            Set<redis.clients.jedis.HostAndPort> jedisSeeds = new HashSet<>();
            jedisSeeds.add(new redis.clients.jedis.HostAndPort("127.0.0.1", BASE_PORT));
            jedisCluster = redis.clients.jedis.RedisClusterClient.create(jedisSeeds);
            jedisStateStore =
                    RedisAgentStateStore.builder()
                            .jedisClient(jedisCluster)
                            .keyPrefix("it:jedis:")
                            .build();
            redisStore = new RedisStore(jedisCluster, "it:store:");
        } catch (Throwable t) {
            System.err.println("=== RedisClusterIntegrationTest skipped due to: ===");
            t.printStackTrace(System.err);
            Assumptions.assumeTrue(false, "Redis Cluster unavailable: " + t);
        }
    }

    private void awaitClusterReady() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            Container.ExecResult r =
                    node.execInContainer(
                            "redis-cli", "-p", String.valueOf(BASE_PORT), "cluster", "info");
            if (r.getStdout().contains("cluster_state:ok")) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("cluster did not reach cluster_state:ok");
    }

    @AfterAll
    void stopCluster() {
        if (stateStore != null) {
            stateStore.close();
        }
        if (jedisStateStore != null) {
            jedisStateStore.close();
        }
        // jedisStateStore shares jedisCluster with redisStore; closing either closes the client,
        // so only close once here.
        if (jedisCluster != null) {
            jedisCluster.close();
        }
        if (node != null) {
            node.stop();
        }
    }

    /** Minimal State payload for serialization round-trips. */
    public record TestState(String value) implements State {}

    @Test
    void multiKeyEvalWorksAcrossSlots() {
        // 12 users/sessions hash to different slots; SAVE_SCRIPT (3-key EVAL) must not CROSSSLOT.
        for (int i = 0; i < 12; i++) {
            stateStore.save("u" + i, "s" + i, "k", new TestState("v" + i));
        }
        for (int i = 0; i < 12; i++) {
            var v = stateStore.get("u" + i, "s" + i, "k", TestState.class);
            assertTrue(v.isPresent(), "missing state for u" + i);
            assertEquals("v" + i, v.get().value());
        }
    }

    @Test
    void listSessionIdsAggregatesAcrossMasterNodes() {
        String user = "lister";
        for (int i = 0; i < 12; i++) {
            stateStore.save(user, "batch" + i, "k", new TestState("x"));
        }
        Set<String> ids = stateStore.listSessionIds(user);
        assertEquals(
                12,
                ids.size(),
                "Lettuce: listSessionIds must return every session across all masters");
    }

    /**
     * Same as {@link #listSessionIdsAggregatesAcrossMasterNodes()} but driven through the Jedis
     * adapter, verifying the cluster-wide SCAN fix in {@code JedisClientAdapter}.
     */
    @Test
    void jedisClusterListSessionIdsAggregatesAcrossNodes() {
        String user = "jlister";
        for (int i = 0; i < 12; i++) {
            jedisStateStore.save(user, "batch" + i, "k", new TestState("x"));
        }
        Set<String> ids = jedisStateStore.listSessionIds(user);
        assertEquals(
                12,
                ids.size(),
                "Jedis: listSessionIds must return every session across all masters");
    }

    @Test
    void redisStorePutSearchDeleteInCluster() {
        List<String> ns = List.of("cluster", "ns");
        redisStore.put(ns, "k1", mapOf("name", "alpha"));
        redisStore.put(ns, "k2", mapOf("name", "beta"));
        List<StoreItem> items = redisStore.search(ns, 10, 0);
        assertEquals(2, items.size(), "search should find both items");
        redisStore.delete(ns, "k1");
        List<StoreItem> after = redisStore.search(ns, 10, 0);
        assertEquals(1, after.size(), "one item should remain after delete");
        assertEquals("k2", after.get(0).key());
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
