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
package io.agentscope.builder.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.channel.weixin.WeixinInboxMessage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcWeixinStateStoreTest {
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcWeixinStateStore store;
    private String schema;
    private DriverManagerDataSource admin;

    @BeforeEach
    void setUp() {
        String url = System.getenv("WEIXIN_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            dataSource =
                    new DriverManagerDataSource(
                            "jdbc:h2:mem:"
                                    + UUID.randomUUID()
                                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                            "sa",
                            "");
        } else {
            String user = System.getenv("WEIXIN_TEST_DB_USER");
            String password = System.getenv("WEIXIN_TEST_DB_PASSWORD");
            admin = new DriverManagerDataSource(url, user, password);
            schema = "weixin_test_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(admin).execute("create schema " + schema);
            dataSource =
                    new DriverManagerDataSource(
                            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                            user,
                            password);
        }
        jdbc = new JdbcTemplate(dataSource);
        WeixinStateSchema.initialize(dataSource);
        store = newStore();
    }

    @AfterEach
    void cleanDatabase() {
        if (admin != null && schema != null)
            new JdbcTemplate(admin).execute("drop schema " + schema + " cascade");
    }

    private JdbcWeixinStateStore newStore() {
        return new JdbcWeixinStateStore(
                dataSource, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void persistsCursorContextAndAccountLease() {
        var lease = store.acquireLease("account", "holder-a", 60_000).orElseThrow();
        assertEquals("", store.loadCursor("account"));
        assertTrue(store.acceptBatch("account", lease, "cursor-2", List.of()));
        assertTrue(store.saveContextToken("account", lease, "peer", "context-1"));
        assertEquals("cursor-2", newStore().loadCursor("account"));
        assertEquals("context-1", newStore().loadContextToken("account", "peer"));
        assertTrue(store.acquireLease("account", "holder-b", 60_000).isEmpty());
        assertTrue(store.renewLease("account", lease, 60_000));
        store.releaseLease("account", lease);
        var next = store.acquireLease("account", "holder-a", 60_000).orElseThrow();
        assertTrue(next.generation() > lease.generation());
        assertFalse(store.renewLease("account", lease, 60_000));
        assertFalse(store.saveContextToken("account", lease, "peer", "stale"));
        assertFalse(store.acceptBatch("account", lease, "lost", List.of()));
        store.releaseLease("account", lease);
        assertTrue(store.isLeaseCurrent("account", next));
        assertNull(store.loadContextToken("account", "missing-peer"));
    }

    @Test
    void acceptsMessagesDurablyBeforeAdvancingCursor() {
        var lease = store.acquireLease("account", "holder", 60_000).orElseThrow();
        var messages =
                List.of(
                        new WeixinInboxMessage("message-2", "{}"),
                        new WeixinInboxMessage("message-1", "{}"));
        assertTrue(store.acceptBatch("account", lease, "next", messages));
        assertTrue(store.acceptBatch("account", lease, "next", messages));
        assertEquals("next", store.loadCursor("account"));
        var restarted = newStore();
        var first = restarted.claimMessages("account", lease, 1, 60_000).get(0);
        assertEquals("message-2", first.messageId());
        assertTrue(restarted.claimMessages("account", lease, 1, 60_000).isEmpty());
        assertTrue(restarted.completeMessage("account", lease, first));
        assertNull(
                jdbc.queryForObject(
                        "select payload from builder_weixin_inbox where message_id = 'message-2'",
                        String.class));
        assertEquals(
                "message-1",
                restarted.claimMessages("account", lease, 1, 60_000).get(0).messageId());
    }

    @Test
    void rollsBackBothMessagesAndCursorWhenBatchCannotBeSaved() {
        var lease = store.acquireLease("account", "holder", 60_000).orElseThrow();
        assertThrows(
                RuntimeException.class,
                () ->
                        store.acceptBatch(
                                "account",
                                lease,
                                "next",
                                List.of(
                                        new WeixinInboxMessage("valid", "{}"),
                                        new WeixinInboxMessage("x".repeat(513), "{}"))));
        assertEquals("", store.loadCursor("account"));
        assertTrue(store.claimMessages("account", lease, 1, 60_000).isEmpty());
    }

    @Test
    void expiredOwnerCannotRenewOrCompleteAndSuccessorRecoversImmediately() {
        var old = store.acquireLease("account", "holder", 60_000).orElseThrow();
        store.acceptBatch("account", old, "next", List.of(new WeixinInboxMessage("message", "{}")));
        var oldClaim = store.claimMessages("account", old, 1, 60_000).get(0);
        jdbc.update("update builder_weixin_lease set expires_at = 0");
        assertFalse(store.renewLease("account", old, 60_000));
        var next = store.acquireLease("account", "holder", 60_000).orElseThrow();
        assertTrue(next.generation() > old.generation());
        var claim = store.claimMessages("account", next, 1, 60_000).get(0);
        assertFalse(store.completeMessage("account", old, oldClaim));
        assertFalse(store.completeMessage("account", next, oldClaim));
        assertTrue(store.completeMessage("account", next, claim));
    }

    @Test
    void retriedClaimRejectsLateCompletionFromTheSameLease() {
        var lease = store.acquireLease("account", "holder", 60_000).orElseThrow();
        store.acceptBatch(
                "account", lease, "next", List.of(new WeixinInboxMessage("message", "{}")));
        var old = store.claimMessages("account", lease, 1, 60_000).get(0);
        jdbc.update("update builder_weixin_inbox set claim_until = 0");
        var next = store.claimMessages("account", lease, 1, 60_000).get(0);
        assertNotEquals(old.claimId(), next.claimId());
        assertFalse(store.failMessage("account", lease, old));
        assertFalse(store.completeMessage("account", lease, old));
        assertTrue(store.completeMessage("account", lease, next));
    }

    @Test
    void onlyOneReplicaCanAcquireAnAccount() {
        var first = CompletableFuture.supplyAsync(() -> store.acquireLease("account", "a", 60_000));
        var second =
                CompletableFuture.supplyAsync(
                        () -> newStore().acquireLease("account", "b", 60_000));
        assertNotEquals(first.join().isPresent(), second.join().isPresent());
    }

    @Test
    void upgradesExistingLeaseSchemaWithoutLosingCursor() {
        jdbc.execute("alter table builder_weixin_lease drop column generation");
        jdbc.update("insert into builder_weixin_cursor values ('account', 'saved', 1)");
        WeixinStateSchema.initialize(dataSource);
        WeixinStateSchema.initialize(dataSource);
        assertEquals("saved", store.loadCursor("account"));
        assertEquals(1, store.acquireLease("account", "holder", 60_000).orElseThrow().generation());
    }

    @Test
    void completedTombstonesArePurgedAfterRetentionButPendingMessagesRemain() {
        var lease = store.acquireLease("account", "holder", 60_000).orElseThrow();
        store.acceptBatch(
                "account",
                lease,
                "next",
                List.of(
                        new WeixinInboxMessage("done", "{}"),
                        new WeixinInboxMessage("pending", "{}")));
        var claim = store.claimMessages("account", lease, 1, 60_000).get(0);
        assertTrue(store.completeMessage("account", lease, claim));
        jdbc.update("update builder_weixin_inbox set completed_at = 0 where status = 'COMPLETED'");
        store.acceptBatch("account", lease, "next", List.of());
        assertEquals(
                1, jdbc.queryForObject("select count(*) from builder_weixin_inbox", Integer.class));
        assertEquals(
                "pending", store.claimMessages("account", lease, 1, 60_000).get(0).messageId());
    }
}
