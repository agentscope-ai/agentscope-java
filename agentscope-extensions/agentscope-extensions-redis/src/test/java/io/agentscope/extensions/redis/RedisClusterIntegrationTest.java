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
import java.util.Arrays;
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
 * {@code redis-server} processes on consecutive ports starting at {@code CLUSTER_BASE_PORT}
 * (default 7000), each announcing itself as {@code 127.0.0.1:<port>}. The container publishes
 * those ports to the same host ports, so the test JVM (on the host) reaches every node via
 * {@code 127.0.0.1:<port>} regardless of OS. This avoids the Linux-only {@code host} network mode
 * and runs on Windows / macOS Docker Desktop too. Bus ports (base + 10000 .. base + 10005) stay
 * inside the container where gossip needs them.
 *
 * <p><strong>Startup and skip semantics.</strong> If the fixed host ports are occupied the test
 * retries on alternate port segments (base + 100, base + 200) before giving up. When all attempts
 * fail:
 *
 * <ul>
 *   <li>with {@code REQUIRE_CLUSTER_IT=1} (e.g. the dedicated CI job) the suite <em>fails</em> —
 *       the cluster code path must never silently go unverified;
 *   <li>otherwise the suite is skipped, but with a loud error banner so the skip is visible in
 *       the log instead of a quiet {@code Tests run: 0}.
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisClusterIntegrationTest {

    private static final String IMAGE = "redis:7-alpine";
    private static final int NODE_COUNT = 6;

    /** When {@code 1}/{@code true}: a cluster startup failure fails the suite instead of skipping. */
    private static final String REQUIRE_CLUSTER_IT_ENV = "REQUIRE_CLUSTER_IT";

    /** First port of the six-node cluster (default 7000). */
    private static final String BASE_PORT_ENV = "CLUSTER_BASE_PORT";

    private static final int DEFAULT_BASE_PORT = 7000;

    /** Alternate port segments tried (offsets from the configured base) when ports are taken. */
    private static final int[] PORT_RETRY_OFFSETS = {0, 100, 200};

    private int basePort = DEFAULT_BASE_PORT;

    /** Boot six redis-server instances, form a 3-master + 3-replica cluster, then stay alive. */
    private static String bootScript(int basePort) {
        StringBuilder ports = new StringBuilder();
        StringBuilder seeds = new StringBuilder();
        for (int i = 0; i < NODE_COUNT; i++) {
            ports.append(i == 0 ? "" : " ").append(basePort + i);
            seeds.append(" 127.0.0.1:").append(basePort + i);
        }
        return "for p in "
                + ports
                + "; do redis-server --port $p --cluster-enabled"
                + " yes --cluster-config-file nodes-$p.conf --cluster-node-timeout 10000"
                + " --cluster-announce-ip 127.0.0.1 --cluster-announce-port $p"
                + " --cluster-announce-bus-port $((p+10000)) --daemonize yes --appendonly no"
                + " --protected-mode no; done; sleep 1; redis-cli --cluster create"
                + seeds
                + " --cluster-replicas 1 --cluster-yes; sleep 1; tail -f /dev/null";
    }

    private GenericContainer<?> node;
    private RedisClusterClient lettuceClient;
    private RedisAgentStateStore stateStore;
    private redis.clients.jedis.RedisClusterClient jedisCluster;
    private RedisAgentStateStore jedisStateStore;
    private RedisStore redisStore;

    @BeforeAll
    void startCluster() {
        boolean required = isClusterRequired();
        int configuredBase = configuredBasePort();
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < PORT_RETRY_OFFSETS.length; attempt++) {
            int candidate = configuredBase + PORT_RETRY_OFFSETS[attempt];
            try {
                startClusterOn(candidate);
                basePort = candidate;
                wireClients();
                return;
            } catch (Throwable t) {
                lastFailure = t;
                System.err.println(
                        "IT: cluster startup failed on port segment " + candidate + ": " + t);
                stopNodeQuietly();
            }
        }
        if (required) {
            // REQUIRE_CLUSTER_IT=1: the cluster code path must be verified; a silent skip here is
            // exactly the failure mode this suite exists to prevent. Fail loudly instead.
            throw new IllegalStateException(
                    "REQUIRE_CLUSTER_IT is set but the Redis Cluster could not be started on any"
                            + " port segment (base "
                            + configuredBase
                            + ", retries +100/+200); fix the environment or unset the variable",
                    lastFailure);
        }
        System.err.println("================================================================");
        System.err.println("!!! ERROR: Redis Cluster integration tests will be SKIPPED. !!!");
        System.err.println("!!! THE CLUSTER CODE PATH (CROSSSLOT guards, multi-master SCAN)  !!!");
        System.err.println("!!! IS NOT VERIFIED BY THIS RUN. Do not treat this run as       !!!");
        System.err.println("!!! covering the cluster behavior.                              !!!");
        System.err.println("!!! Cause: " + lastFailure);
        System.err.println("!!! Re-run with REQUIRE_CLUSTER_IT=1 to make this a hard        !!!");
        System.err.println("!!! failure, and/or point CLUSTER_BASE_PORT at a free port.     !!!");
        System.err.println("================================================================");
        lastFailure.printStackTrace(System.err);
        Assumptions.assumeTrue(false, "Redis Cluster unavailable: " + lastFailure);
    }

    /**
     * Boot the container and wait for the cluster to form on the given base port. Throws on any
     * failure (image pull, port conflicts, cluster formation timeout) so the caller can retry on
     * another segment.
     */
    private void startClusterOn(int candidateBase) throws Exception {
        PortBinding[] portBindings = new PortBinding[NODE_COUNT];
        int[] ports = new int[NODE_COUNT];
        for (int i = 0; i < NODE_COUNT; i++) {
            ports[i] = candidateBase + i;
            portBindings[i] = PortBinding.parse(ports[i] + ":" + ports[i]);
        }
        node =
                new GenericContainer<>(IMAGE)
                        // Declare the ports as exposed *and* bind them: some Docker versions
                        // (e.g. 28.x on CI) ignore bindings that are only present in
                        // HostConfig.PortBindings but missing from Config.ExposedPorts, and
                        // Testcontainers' built-in "wait until mapped ports are visible"
                        // check only runs when ExposedPorts is non-empty. This mirrors what
                        // Testcontainers' addFixedExposedPort does internally.
                        .withExposedPorts(Arrays.stream(ports).boxed().toArray(Integer[]::new))
                        .withCreateContainerCmdModifier(cmd -> cmd.withPortBindings(portBindings))
                        .withCommand("sh", "-c", bootScript(candidateBase))
                        .waitingFor(Wait.forListeningPorts(ports));
        node.start();
        System.out.println(
                "IT: container started on base port "
                        + candidateBase
                        + ", waiting for cluster_state:ok");
        awaitClusterReady();
        System.out.println("IT: cluster ready");
    }

    /** Build the Lettuce/Jedis clients and the three stores against the started cluster. */
    private void wireClients() {
        lettuceClient = RedisClusterClient.create(RedisURI.create("127.0.0.1", basePort));
        stateStore =
                RedisAgentStateStore.builder()
                        .lettuceClusterClient(lettuceClient)
                        .keyPrefix("agentscope:session:")
                        .build();

        Set<redis.clients.jedis.HostAndPort> jedisSeeds = new HashSet<>();
        jedisSeeds.add(new redis.clients.jedis.HostAndPort("127.0.0.1", basePort));
        jedisCluster = redis.clients.jedis.RedisClusterClient.create(jedisSeeds);
        jedisStateStore =
                RedisAgentStateStore.builder()
                        .jedisClient(jedisCluster)
                        .keyPrefix("agentscope:jedis:")
                        .build();
        redisStore = new RedisStore(jedisCluster, "agentscope:store:");
    }

    private static boolean isClusterRequired() {
        String v = System.getenv(REQUIRE_CLUSTER_IT_ENV);
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()));
    }

    private static int configuredBasePort() {
        String v = System.getenv(BASE_PORT_ENV);
        if (v == null || v.isBlank()) {
            return DEFAULT_BASE_PORT;
        }
        return Integer.parseInt(v.trim());
    }

    private void stopNodeQuietly() {
        if (node != null) {
            try {
                node.stop();
            } catch (Throwable cleanupFailure) {
                System.err.println("IT: container cleanup failed: " + cleanupFailure);
            }
            node = null;
        }
    }

    private void awaitClusterReady() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            Container.ExecResult r =
                    node.execInContainer(
                            "redis-cli", "-p", String.valueOf(basePort), "cluster", "info");
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
            stateStore.save(
                    "user-" + (1000 + i), "session-" + i, "agent:profile", new TestState("v" + i));
        }
        for (int i = 0; i < 12; i++) {
            var v =
                    stateStore.get(
                            "user-" + (1000 + i), "session-" + i, "agent:profile", TestState.class);
            assertTrue(v.isPresent(), "missing state for u" + i);
            assertEquals("v" + i, v.get().value());
        }
    }

    @Test
    void listSessionIdsAggregatesAcrossMasterNodes() {
        String user = "user-lister";
        for (int i = 0; i < 12; i++) {
            stateStore.save(user, "session-batch-" + i, "agent:profile", new TestState("x"));
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
        String user = "user-jlister";
        for (int i = 0; i < 12; i++) {
            jedisStateStore.save(user, "session-batch-" + i, "agent:profile", new TestState("x"));
        }
        Set<String> ids = jedisStateStore.listSessionIds(user);
        assertEquals(
                12,
                ids.size(),
                "Jedis: listSessionIds must return every session across all masters");
    }

    @Test
    void redisStorePutSearchDeleteInCluster() {
        List<String> ns = List.of("tenant-1", "agent-workspace");
        redisStore.put(ns, "doc-001", mapOf("name", "alpha"));
        redisStore.put(ns, "doc-002", mapOf("name", "beta"));
        List<StoreItem> items = redisStore.search(ns, 10, 0);
        assertEquals(2, items.size(), "search should find both items");
        redisStore.delete(ns, "doc-001");
        List<StoreItem> after = redisStore.search(ns, 10, 0);
        assertEquals(1, after.size(), "one item should remain after delete");
        assertEquals("doc-002", after.get(0).key());
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
