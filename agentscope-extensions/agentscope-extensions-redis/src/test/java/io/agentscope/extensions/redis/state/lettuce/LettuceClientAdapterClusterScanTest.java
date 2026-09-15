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
package io.agentscope.extensions.redis.state.lettuce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that {@link LettuceClientAdapter#findKeysByPattern} iterates over every master node in
 * a Redis Cluster and aggregates the results, instead of only scanning the default node (which
 * would silently miss keys living on other shards). Uses mocks so it runs without Docker.
 */
class LettuceClientAdapterClusterScanTest {

    @SuppressWarnings("unchecked")
    @Test
    void findKeysByPatternScansEveryMasterNode() throws Exception {
        // three masters (each holding distinct keys) + one replica (must be skipped)
        RedisClusterNode masterA = node("A", true);
        RedisClusterNode masterB = node("B", true);
        RedisClusterNode masterC = node("C", true);
        RedisClusterNode replica = node("R", false);

        Partitions partitions = new Partitions();
        partitions.addPartition(masterA);
        partitions.addPartition(masterB);
        partitions.addPartition(masterC);
        partitions.addPartition(replica);
        // Partitions.iterator() reads a cached view; refresh it so the masters are visible.
        partitions.updateCache();

        StatefulRedisClusterConnection<String, String> conn =
                mock(StatefulRedisClusterConnection.class);
        when(conn.getPartitions()).thenReturn(partitions);

        RedisCommands<String, String> cmdA = stubNode(conn, "A", List.of("k1", "k2"));
        RedisCommands<String, String> cmdB = stubNode(conn, "B", List.of("k3"));
        RedisCommands<String, String> cmdC = stubNode(conn, "C", List.of("k4", "k5"));

        LettuceClientAdapter adapter = newAdapter(conn);
        Set<String> keys = adapter.findKeysByPattern("prefix:*");

        // every master must be contacted, the replica must not
        verify(conn).getConnection("A");
        verify(conn).getConnection("B");
        verify(conn).getConnection("C");
        verify(conn, never()).getConnection("R");
        verify(cmdA).scan(any(ScanCursor.class), any(ScanArgs.class));

        assertEquals(
                Set.of("k1", "k2", "k3", "k4", "k5"),
                keys,
                "SCAN must aggregate keys from every master node, not just the default node");
    }

    private static RedisClusterNode node(String id, boolean master) {
        RedisClusterNode n = mock(RedisClusterNode.class);
        when(n.is(RedisClusterNode.NodeFlag.MASTER)).thenReturn(master);
        when(n.getNodeId()).thenReturn(id);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> stubNode(
            StatefulRedisClusterConnection<String, String> conn, String nodeId, List<String> keys) {
        StatefulRedisConnection<String, String> nodeConn = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> cmd = mock(RedisCommands.class);
        when(conn.getConnection(nodeId)).thenReturn(nodeConn);
        when(nodeConn.sync()).thenReturn(cmd);
        KeyScanCursor<String> cur = mock(KeyScanCursor.class);
        when(cur.getKeys()).thenReturn(new ArrayList<>(keys));
        when(cur.isFinished()).thenReturn(true);
        when(cmd.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(cur);
        return cmd;
    }

    private static LettuceClientAdapter newAdapter(
            StatefulRedisClusterConnection<String, String> conn) throws Exception {
        Constructor<LettuceClientAdapter> ctor =
                LettuceClientAdapter.class.getDeclaredConstructor(
                        RedisCommands.class,
                        RedisAdvancedClusterCommands.class,
                        StatefulRedisClusterConnection.class,
                        AutoCloseable.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                null,
                Mockito.mock(RedisAdvancedClusterCommands.class),
                conn,
                (AutoCloseable) () -> {});
    }
}
