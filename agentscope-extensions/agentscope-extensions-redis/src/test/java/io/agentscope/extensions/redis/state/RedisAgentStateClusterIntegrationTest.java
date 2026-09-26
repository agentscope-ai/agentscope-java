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
package io.agentscope.extensions.redis.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.model.PortBinding;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisClusterClient;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.util.JedisClusterCRC16;

/** Integration verification for agent-state behavior against a real Redis Cluster. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisAgentStateClusterIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final String IMAGE = "redis:7-alpine";
    private static final int DEFAULT_BASE_PORT = 17000;
    private static final int NODE_COUNT = 3;
    private static final int MASTER_COUNT = 3;

    private GenericContainer<?> container;
    private RedisClusterClient jedisCluster;
    private String preStartSkipReason;
    private int basePort;

    @BeforeAll
    void startCluster() throws Exception {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            preStartSkipReason =
                    "Docker is unavailable; real Redis Cluster integration test skipped";
            return;
        }
        basePort = Integer.getInteger("agentscope.redis.cluster.base-port", DEFAULT_BASE_PORT);
        preStartSkipReason = findUnavailableFixedPort();
        if (preStartSkipReason != null) {
            return;
        }

        int[] ports = new int[NODE_COUNT];
        PortBinding[] bindings = new PortBinding[NODE_COUNT];
        for (int i = 0; i < NODE_COUNT; i++) {
            ports[i] = basePort + i;
            bindings[i] = PortBinding.parse(ports[i] + ":" + ports[i]);
        }

        container =
                new GenericContainer<>(DockerImageName.parse(IMAGE))
                        .withCreateContainerCmdModifier(cmd -> cmd.withPortBindings(bindings))
                        .withCommand("sh", "-c", buildBootScript())
                        .withStartupTimeout(Duration.ofSeconds(90))
                        .waitingFor(Wait.forListeningPorts(ports));
        try {
            container.start();
            awaitClusterReady();
            jedisCluster = RedisClusterClient.create(Set.of(new HostAndPort(HOST, basePort)));
        } catch (Throwable failure) {
            try {
                container.stop();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @BeforeEach
    void requirePreStartEnvironment() {
        assumeTrue(preStartSkipReason == null, preStartSkipReason);
    }

    @AfterAll
    void stopCluster() {
        try {
            if (jedisCluster != null) {
                jedisCluster.close();
            }
        } finally {
            if (container != null) {
                container.stop();
            }
        }
    }

    @Test
    void v1SaveAndCasUseOneSlotInRealCluster() {
        String prefix = "it:cluster:save-cas:";
        String user = "cas-user";
        String sessionId = "cas-sessionId";
        String stateKey = "payload";
        String payloadKey = prefix + "{" + user + "/" + sessionId + "}:" + stateKey;
        String versionKey = payloadKey + ":ver";
        String markerKey = prefix + "{" + user + "/" + sessionId + "}:_keys";

        int expectedSlot = JedisClusterCRC16.getSlot(payloadKey);
        assertEquals(expectedSlot, JedisClusterCRC16.getSlot(versionKey));
        assertEquals(expectedSlot, JedisClusterCRC16.getSlot(markerKey));

        RedisAgentStateStore store = newStore(prefix, RedisAgentStateStore.KeyLayoutVersion.V1);
        store.save(user, sessionId, stateKey, new TestState("first"));
        VersionedState<TestState> first =
                store.getVersioned(user, sessionId, stateKey, TestState.class);
        assertEquals(new TestState("first"), first.value());
        assertEquals(1L, first.version());

        long secondVersion =
                store.saveIfVersion(
                        user, sessionId, stateKey, new TestState("second"), first.version());

        assertEquals(2L, secondVersion);
        assertEquals(
                new VersionedState<>(new TestState("second"), 2L),
                store.getVersioned(user, sessionId, stateKey, TestState.class));
        assertTrue(jedisCluster.exists(payloadKey));
        assertTrue(jedisCluster.exists(versionKey));
        assertTrue(jedisCluster.sismember(markerKey, stateKey));
    }

    @Test
    void jedisListingReturnsSessionsFromAllClusterMasters() {
        String prefix = "it:cluster:listing:";
        String user = "listing-user";
        RedisAgentStateStore store = newStore(prefix, RedisAgentStateStore.KeyLayoutVersion.V1);
        Set<String> expectedSessionIds = sessionIdsCoveringEveryMaster(prefix, user);

        for (String session : expectedSessionIds) {
            store.save(user, session, "profile", new TestState(session));
        }

        String markerPattern = prefix + "{" + user + "/*}:_keys";
        List<Set<String>> markersByMaster = scanMasters(markerPattern);
        Set<String> expectedMarkers = new HashSet<>();
        for (String session : expectedSessionIds) {
            expectedMarkers.add(prefix + "{" + user + "/" + session + "}:_keys");
        }

        assertEquals(MASTER_COUNT, markersByMaster.size());
        assertTrue(
                markersByMaster.stream().allMatch(markers -> markers.size() == 1),
                "test fixture must place exactly one marker key on every master");
        assertEquals(
                expectedMarkers,
                markersByMaster.stream().collect(HashSet<String>::new, Set::addAll, Set::addAll));

        Set<String> listed = store.listSessionIds(user);
        assertEquals(expectedSessionIds, listed);
    }

    private RedisAgentStateStore newStore(
            String prefix, RedisAgentStateStore.KeyLayoutVersion layoutVersion) {
        return RedisAgentStateStore.builder()
                .jedisClient(jedisCluster)
                .keyPrefix(prefix)
                .keyLayoutVersion(layoutVersion)
                .build();
    }

    private Set<String> sessionIdsCoveringEveryMaster(String prefix, String user) {
        boolean[] covered = new boolean[MASTER_COUNT];
        Set<String> sessions = new LinkedHashSet<>();
        for (int candidate = 0; sessions.size() < MASTER_COUNT; candidate++) {
            String session = "session-" + candidate;
            String marker = prefix + "{" + user + "/" + session + "}:_keys";
            int master = masterIndexForSlot(JedisClusterCRC16.getSlot(marker));
            if (!covered[master]) {
                sessions.add(session);
                covered[master] = true;
            }
        }
        return sessions;
    }

    private int masterIndexForSlot(int slot) {
        if (slot <= 5460) {
            return 0;
        }
        if (slot <= 10922) {
            return 1;
        }
        return 2;
    }

    private List<Set<String>> scanMasters(String pattern) {
        List<Set<String>> results = new ArrayList<>();
        for (int port = basePort; port < basePort + NODE_COUNT; port++) {
            try (Jedis node = new Jedis(HOST, port)) {
                if ("master".equals(node.role().get(0))) {
                    results.add(scanNode(node, pattern));
                }
            }
        }
        return results;
    }

    private Set<String> scanNode(Jedis node, String pattern) {
        Set<String> keys = new HashSet<>();
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(pattern).count(1000);
        do {
            ScanResult<String> result = node.scan(cursor, params);
            keys.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return keys;
    }

    /** Wait for complete slot coverage and final master roles before exposing the fixture to tests. */
    private void awaitClusterReady() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (hasReadyClusterViewOnEveryMaster()) {
                return;
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Redis Cluster did not reach cluster_state:ok");
    }

    /**
     * Guard against the interval where one node reports a healthy cluster but other masters have
     * not received the complete slot map from {@code redis-cli --cluster create} yet.
     */
    private boolean hasReadyClusterViewOnEveryMaster() throws Exception {
        for (int port = basePort; port < basePort + NODE_COUNT; port++) {
            Container.ExecResult role =
                    container.execInContainer("redis-cli", "-p", Integer.toString(port), "role");
            if (role.getExitCode() != 0 || !role.getStdout().startsWith("master\n")) {
                return false;
            }

            Container.ExecResult clusterInfo =
                    container.execInContainer(
                            "redis-cli", "-p", Integer.toString(port), "cluster", "info");
            String info = clusterInfo.getStdout();
            if (clusterInfo.getExitCode() != 0
                    || !info.contains("cluster_state:ok")
                    || !info.contains("cluster_slots_assigned:16384")
                    || !info.contains("cluster_slots_ok:16384")
                    || !info.contains("cluster_known_nodes:" + NODE_COUNT)
                    || !info.contains("cluster_size:" + MASTER_COUNT)) {
                return false;
            }
        }
        return true;
    }

    private String findUnavailableFixedPort() {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int port = basePort; port < basePort + NODE_COUNT; port++) {
                ServerSocket socket = null;
                try {
                    socket = new ServerSocket();
                    socket.setReuseAddress(false);
                    socket.bind(new InetSocketAddress("0.0.0.0", port));
                    sockets.add(socket);
                } catch (IOException failure) {
                    closePreflightSocket(socket);
                    return "Required fixed Redis Cluster host port "
                            + port
                            + " is unavailable: "
                            + failure.getMessage();
                }
            }
            return null;
        } finally {
            for (ServerSocket socket : sockets) {
                closePreflightSocket(socket);
            }
        }
    }

    private void closePreflightSocket(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort close of preflight sockets; no container has started yet.
        }
    }

    /**
     * Build a three-master cluster whose container and host ports are identical.
     *
     * <p>Redis advertises these ports in MOVED responses, so identical mappings let the host-side
     * Jedis client follow redirects without address rewriting.
     */
    private String buildBootScript() {
        StringBuilder nodes = new StringBuilder();
        for (int port = basePort; port < basePort + NODE_COUNT; port++) {
            if (!nodes.isEmpty()) {
                nodes.append(' ');
            }
            nodes.append(HOST).append(':').append(port);
        }
        return "for p in $(seq "
                + basePort
                + " "
                + (basePort + NODE_COUNT - 1)
                + "); do "
                + "mkdir -p /tmp/redis-$p; "
                + "redis-server --port $p --dir /tmp/redis-$p --cluster-enabled yes "
                + "--cluster-config-file nodes.conf --cluster-node-timeout 10000 "
                + "--cluster-announce-ip 127.0.0.1 --cluster-announce-port $p "
                + "--cluster-announce-bus-port $((p+10000)) --daemonize yes "
                + "--appendonly no --protected-mode no; "
                + "done; "
                + "sleep 1; redis-cli --cluster create "
                + nodes
                + " --cluster-replicas 0 --cluster-yes; tail -f /dev/null";
    }

    record TestState(String value) implements State {}
}
