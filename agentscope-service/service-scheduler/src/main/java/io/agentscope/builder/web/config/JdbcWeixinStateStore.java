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

import io.agentscope.extensions.channel.weixin.WeixinInboxClaim;
import io.agentscope.extensions.channel.weixin.WeixinInboxMessage;
import io.agentscope.extensions.channel.weixin.WeixinLease;
import io.agentscope.extensions.channel.weixin.WeixinStateStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Scheduler-owned PostgreSQL/H2 adapter. The account row serializes all fenced mutations. */
public final class JdbcWeixinStateStore implements WeixinStateStore {
    private static final long RETENTION_MS = Duration.ofDays(7).toMillis();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String nowSql;

    public JdbcWeixinStateStore(DataSource dataSource, TransactionTemplate transactions) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        try (var connection = dataSource.getConnection()) {
            nowSql =
                    "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                            ? "select clock_timestamp()"
                            : "select current_timestamp";
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot determine Weixin database clock", e);
        }
    }

    private long now() {
        return jdbc.queryForObject(nowSql, (rs, row) -> rs.getTimestamp(1).getTime());
    }

    @Override
    public String loadCursor(String accountId) {
        requireAccount(accountId);
        List<String> values =
                jdbc.query(
                        "select cursor_value from builder_weixin_cursor where account_id = ?",
                        (rs, row) -> rs.getString(1),
                        accountId);
        return values.isEmpty() ? "" : values.get(0);
    }

    @Override
    public String loadContextToken(String accountId, String peerId) {
        requireAccount(accountId);
        List<String> values =
                jdbc.query(
                        "select context_token from builder_weixin_context where account_id = ? and"
                                + " peer_id = ?",
                        (rs, row) -> rs.getString(1),
                        accountId,
                        peerId);
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public boolean saveContextToken(
            String accountId, WeixinLease lease, String peerId, String token) {
        return withLease(
                accountId,
                lease,
                false,
                timestamp -> {
                    if (peerId != null && token != null && !token.isBlank()) {
                        int updated =
                                jdbc.update(
                                        "update builder_weixin_context set context_token = ?,"
                                            + " updated_at = ? where account_id = ? and peer_id ="
                                            + " ?",
                                        token,
                                        timestamp,
                                        accountId,
                                        peerId);
                        if (updated == 0)
                            jdbc.update(
                                    "insert into builder_weixin_context (account_id, peer_id,"
                                            + " context_token, updated_at) values (?, ?, ?, ?)",
                                    accountId,
                                    peerId,
                                    token,
                                    timestamp);
                    }
                    return true;
                });
    }

    @Override
    public Optional<WeixinLease> acquireLease(String accountId, String holderId, long leaseMs) {
        requireAccount(accountId);
        if (holderId == null || holderId.isBlank() || leaseMs <= 0)
            throw new IllegalArgumentException("holderId and positive leaseMs are required");
        return transactions.execute(
                status -> {
                    // The bootstrap insert shares this transaction. ON CONFLICT keeps a concurrent
                    // first acquisition from aborting the transaction, and a concurrent
                    // removeAccount cannot delete a row this transaction has not committed yet —
                    // which is what used to leave lock() without a row to return.
                    jdbc.update(
                            "insert into builder_weixin_lease (account_id, holder_id, generation,"
                                + " acquired_at, expires_at) values (?, '', 0, 0, 0) on conflict do"
                                + " nothing",
                            accountId);
                    LeaseRow row = lock(accountId);
                    if (row == null) {
                        // Defensive: a missing row must back off like a lost lease, never throw.
                        return Optional.empty();
                    }
                    long timestamp = now();
                    if (row.expiresAt > timestamp && !row.holderId.equals(holderId))
                        return Optional.empty();
                    long generation = row.generation + (row.expiresAt <= timestamp ? 1 : 0);
                    WeixinLease lease = new WeixinLease(holderId, generation);
                    jdbc.update(
                            "update builder_weixin_lease set holder_id = ?, generation = ?,"
                                    + " acquired_at = ?, expires_at = ? where account_id = ?",
                            holderId,
                            generation,
                            timestamp,
                            timestamp + leaseMs,
                            accountId);
                    return Optional.of(lease);
                });
    }

    @Override
    public boolean renewLease(String accountId, WeixinLease lease, long leaseMs) {
        if (leaseMs <= 0) throw new IllegalArgumentException("leaseMs must be positive");
        return withLease(
                accountId,
                lease,
                false,
                timestamp -> {
                    jdbc.update(
                            "update builder_weixin_lease set expires_at = ? where account_id = ?",
                            timestamp + leaseMs,
                            accountId);
                    return true;
                });
    }

    @Override
    public boolean isLeaseCurrent(String accountId, WeixinLease lease) {
        requireAccount(accountId);
        if (lease == null) return false;
        List<LeaseRow> rows = readLease(accountId, false);
        return !rows.isEmpty() && rows.get(0).matches(lease) && rows.get(0).expiresAt > now();
    }

    @Override
    public void releaseLease(String accountId, WeixinLease lease) {
        requireAccount(accountId);
        // Keep the row: deleting it would reset the fencing generation.
        jdbc.update(
                "update builder_weixin_lease set expires_at = 0"
                        + " where account_id = ? and holder_id = ? and generation = ?",
                accountId,
                lease.holderId(),
                lease.generation());
    }

    @Override
    public boolean acceptBatch(
            String accountId,
            WeixinLease lease,
            String nextCursor,
            List<WeixinInboxMessage> messages) {
        List<WeixinInboxMessage> batch = List.copyOf(messages);
        return withLease(
                accountId,
                lease,
                false,
                timestamp -> {
                    jdbc.update(
                            "delete from builder_weixin_inbox where account_id = ?"
                                    + " and status in ('COMPLETED', 'ABANDONED')"
                                    + " and completed_at < ?",
                            accountId,
                            timestamp - RETENTION_MS);
                    for (WeixinInboxMessage message : batch) {
                        jdbc.update(
                                "insert into builder_weixin_inbox (account_id, message_id, payload,"
                                    + " status) values (?, ?, ?, 'PENDING') on conflict do nothing",
                                accountId,
                                message.messageId(),
                                message.payload());
                    }
                    if (nextCursor != null && !nextCursor.isBlank()) {
                        int updated =
                                jdbc.update(
                                        "update builder_weixin_cursor set cursor_value = ?,"
                                                + " updated_at = ? where account_id = ?",
                                        nextCursor,
                                        timestamp,
                                        accountId);
                        if (updated == 0)
                            jdbc.update(
                                    "insert into builder_weixin_cursor (account_id, cursor_value,"
                                            + " updated_at) values (?, ?, ?)",
                                    accountId,
                                    nextCursor,
                                    timestamp);
                    }
                    return true;
                });
    }

    @Override
    public List<WeixinInboxClaim> claimMessages(
            String accountId, WeixinLease lease, int limit, long claimMs) {
        if (limit <= 0 || claimMs <= 0)
            throw new IllegalArgumentException("positive claim bounds required");
        return withLease(
                accountId,
                lease,
                List.of(),
                timestamp -> {
                    List<PendingMessage> pending =
                            jdbc.query(
                                    "select message_id, payload, claim_generation, claim_until from"
                                        + " builder_weixin_inbox where account_id = ? and status in"
                                        + " ('PENDING', 'PROCESSING') order by inbox_order limit ?",
                                    (rs, row) ->
                                            new PendingMessage(
                                                    rs.getString(1),
                                                    rs.getString(2),
                                                    rs.getLong(3),
                                                    rs.getLong(4)),
                                    accountId,
                                    limit);
                    List<WeixinInboxClaim> claims = new ArrayList<>();
                    for (PendingMessage message : pending) {
                        if (message.generation == lease.generation()
                                && message.claimUntil > timestamp) break;
                        String claimId = UUID.randomUUID().toString();
                        jdbc.update(
                                "update builder_weixin_inbox set status = 'PROCESSING',"
                                        + " claim_generation = ?, claim_id = ?, claim_until = ?"
                                        + " where account_id = ? and message_id = ?",
                                lease.generation(),
                                claimId,
                                timestamp + claimMs,
                                accountId,
                                message.id);
                        claims.add(new WeixinInboxClaim(message.id, message.payload, claimId));
                    }
                    return List.copyOf(claims);
                });
    }

    @Override
    public boolean completeMessage(String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        return withLease(
                accountId,
                lease,
                false,
                timestamp ->
                        jdbc.update(
                                        "update builder_weixin_inbox set status = 'COMPLETED',"
                                            + " payload = null, completed_at = ?, claim_id = null,"
                                            + " claim_until = 0 where account_id = ? and message_id"
                                            + " = ? and status = 'PROCESSING' and claim_generation"
                                            + " = ? and claim_id = ? and claim_until > ?",
                                        timestamp,
                                        accountId,
                                        claim.messageId(),
                                        lease.generation(),
                                        claim.claimId(),
                                        timestamp)
                                == 1);
    }

    @Override
    public boolean failMessage(String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        return withLease(
                accountId,
                lease,
                false,
                timestamp ->
                        jdbc.update(
                                        "update builder_weixin_inbox set status = 'PENDING',"
                                            + " claim_id = null, claim_until = 0 where account_id ="
                                            + " ? and message_id = ? and status = 'PROCESSING' and"
                                            + " claim_generation = ? and claim_id = ? and"
                                            + " claim_until > ?",
                                        accountId,
                                        claim.messageId(),
                                        lease.generation(),
                                        claim.claimId(),
                                        timestamp)
                                == 1);
    }

    @Override
    public boolean abandonMessage(String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        return withLease(
                accountId,
                lease,
                false,
                timestamp ->
                        jdbc.update(
                                        "update builder_weixin_inbox set status = 'ABANDONED',"
                                            + " payload = null, completed_at = ?, claim_id = null,"
                                            + " claim_until = 0 where account_id = ? and message_id"
                                            + " = ? and status = 'PROCESSING' and claim_generation"
                                            + " = ? and claim_id = ? and claim_until > ?",
                                        timestamp,
                                        accountId,
                                        claim.messageId(),
                                        lease.generation(),
                                        claim.claimId(),
                                        timestamp)
                                == 1);
    }

    @Override
    public void removeAccount(String accountId) {
        requireAccount(accountId);
        // Retirement is explicit and one-way: the cursor is the provider consumer position, so
        // dropping it makes the next poll replay whatever the provider still holds for the
        // account. Callers use this when the account will not resume its provider session.
        transactions.executeWithoutResult(
                status -> {
                    jdbc.update("delete from builder_weixin_inbox where account_id = ?", accountId);
                    jdbc.update(
                            "delete from builder_weixin_cursor where account_id = ?", accountId);
                    jdbc.update(
                            "delete from builder_weixin_context where account_id = ?", accountId);
                    jdbc.update("delete from builder_weixin_lease where account_id = ?", accountId);
                });
    }

    private <T> T withLease(
            String accountId, WeixinLease lease, T rejected, Function<Long, T> action) {
        requireAccount(accountId);
        return transactions.execute(
                status -> {
                    LeaseRow row = lock(accountId);
                    long timestamp = now();
                    if (row == null || !row.matches(lease) || row.expiresAt <= timestamp)
                        return rejected;
                    return action.apply(timestamp);
                });
    }

    private LeaseRow lock(String accountId) {
        List<LeaseRow> rows = readLease(accountId, true);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<LeaseRow> readLease(String accountId, boolean lock) {
        return jdbc.query(
                "select holder_id, generation, expires_at from builder_weixin_lease"
                        + " where account_id = ?"
                        + (lock ? " for update" : ""),
                (rs, row) -> new LeaseRow(rs.getString(1), rs.getLong(2), rs.getLong(3)),
                accountId);
    }

    private static void requireAccount(String accountId) {
        if (accountId == null || accountId.isBlank())
            throw new IllegalArgumentException("accountId must not be blank");
    }

    private record LeaseRow(String holderId, long generation, long expiresAt) {
        boolean matches(WeixinLease lease) {
            return lease != null
                    && holderId.equals(lease.holderId())
                    && generation == lease.generation();
        }
    }

    private record PendingMessage(String id, String payload, long generation, long claimUntil) {}
}
