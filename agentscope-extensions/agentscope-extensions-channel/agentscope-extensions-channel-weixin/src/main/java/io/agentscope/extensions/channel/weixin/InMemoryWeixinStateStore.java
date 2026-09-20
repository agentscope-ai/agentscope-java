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
package io.agentscope.extensions.channel.weixin;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Standalone/test adapter with the same fencing and claim rules as a durable host.
 *
 * <p><b>Not for production.</b> Everything lives in this process: a restart loses the cursor,
 * peer context tokens and leases, and a second host instance sees a different store, so the
 * single-consumer guarantee only holds within one JVM. Reaching for a durable {@link
 * WeixinStateStore} is what makes restart recovery and horizontal scaling work; see the module
 * README.
 *
 * <p>Completed message tombstones expire after {@link #RETENTION_MS}, and during {@link
 * #acceptBatch} the store forgets accounts that hold nothing at all (no cursor, no peer context,
 * no inbox, no live lease) — for example ids seen only while validating a request. An account
 * that has actually consumed a message keeps its cursor and peer context until the host retires
 * it with {@link #removeAccount}; dropping them would make the consumer replay the provider
 * backlog from the beginning.
 */
final class InMemoryWeixinStateStore implements WeixinStateStore {
    private static final long RETENTION_MS = Duration.ofDays(7).toMillis();
    private final Clock clock;
    private final Map<String, Account> accounts = new HashMap<>();

    InMemoryWeixinStateStore() {
        this(Clock.systemUTC());
    }

    InMemoryWeixinStateStore(Clock clock) {
        this.clock = clock;
    }

    /** Creates the account if needed; only mutating operations may use this. */
    private Account account(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("accountId is required");
        return accounts.computeIfAbsent(id, ignored -> new Account());
    }

    /** Number of accounts currently retained; used by tests and diagnostics. */
    int retainedAccounts() {
        return accounts.size();
    }

    @Override
    public synchronized void removeAccount(String accountId) {
        if (accountId == null || accountId.isBlank())
            throw new IllegalArgumentException("accountId is required");
        accounts.remove(accountId);
    }

    /** Looks up an account without creating one; validation paths must use this. */
    private Account existing(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("accountId is required");
        return accounts.get(id);
    }

    @Override
    public synchronized String loadCursor(String accountId) {
        Account a = existing(accountId);
        return a == null ? "" : a.cursor;
    }

    @Override
    public synchronized String loadContextToken(String accountId, String peerId) {
        Account a = existing(accountId);
        return a == null ? null : a.contexts.get(peerId);
    }

    @Override
    public synchronized boolean saveContextToken(
            String accountId, WeixinLease lease, String peerId, String token) {
        if (!isLeaseCurrent(accountId, lease)) return false;
        if (peerId != null && token != null && !token.isBlank()) {
            account(accountId).contexts.put(peerId, token);
        }
        return true;
    }

    @Override
    public synchronized Optional<WeixinLease> acquireLease(
            String accountId, String holderId, long leaseMs) {
        if (holderId == null || holderId.isBlank() || leaseMs <= 0)
            throw new IllegalArgumentException("holderId and positive leaseMs are required");
        Account a = account(accountId);
        if (a.lease != null && a.expiresAt > clock.millis()) {
            if (!a.lease.holderId().equals(holderId)) return Optional.empty();
        } else {
            a.lease = new WeixinLease(holderId, ++a.generation);
        }
        a.expiresAt = clock.millis() + leaseMs;
        return Optional.of(a.lease);
    }

    @Override
    public synchronized boolean renewLease(String accountId, WeixinLease lease, long leaseMs) {
        if (leaseMs <= 0) throw new IllegalArgumentException("leaseMs must be positive");
        if (!isLeaseCurrent(accountId, lease)) return false;
        account(accountId).expiresAt = clock.millis() + leaseMs;
        return true;
    }

    @Override
    public synchronized boolean isLeaseCurrent(String accountId, WeixinLease lease) {
        Account a = existing(accountId);
        return a != null && lease != null && lease.equals(a.lease) && a.expiresAt > clock.millis();
    }

    @Override
    public synchronized void releaseLease(String accountId, WeixinLease lease) {
        Account a = existing(accountId);
        if (a != null && lease != null && lease.equals(a.lease)) a.expiresAt = 0;
    }

    @Override
    public synchronized boolean acceptBatch(
            String accountId,
            WeixinLease lease,
            String nextCursor,
            List<WeixinInboxMessage> messages) {
        List<WeixinInboxMessage> batch = List.copyOf(messages);
        if (!isLeaseCurrent(accountId, lease)) return false;
        Account a = account(accountId);
        for (WeixinInboxMessage message : batch) {
            a.inbox.putIfAbsent(message.messageId(), new Message(message.payload()));
        }
        if (nextCursor != null && !nextCursor.isBlank()) a.cursor = nextCursor;
        prune(accountId);
        return true;
    }

    @Override
    public synchronized List<WeixinInboxClaim> claimMessages(
            String accountId, WeixinLease lease, int limit, long claimMs) {
        if (limit <= 0 || claimMs <= 0)
            throw new IllegalArgumentException("positive claim bounds required");
        if (!isLeaseCurrent(accountId, lease)) return List.of();
        List<WeixinInboxClaim> claims = new ArrayList<>();
        for (var entry : account(accountId).inbox.entrySet()) {
            Message m = entry.getValue();
            if (m.completed || m.abandoned) continue;
            if (lease.equals(m.lease) && m.claimUntil > clock.millis()) break;
            m.lease = lease;
            m.claimId = UUID.randomUUID().toString();
            m.claimUntil = clock.millis() + claimMs;
            claims.add(new WeixinInboxClaim(entry.getKey(), m.payload, m.claimId));
            if (claims.size() >= limit) break;
        }
        return List.copyOf(claims);
    }

    @Override
    public synchronized boolean completeMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        Message m = validClaim(accountId, lease, claim);
        if (m == null) return false;
        m.completed = true;
        m.completedAt = clock.millis();
        m.payload = null;
        m.claimId = null;
        return true;
    }

    @Override
    public synchronized boolean failMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        Message m = validClaim(accountId, lease, claim);
        if (m == null) return false;
        m.claimUntil = 0;
        m.claimId = null;
        return true;
    }

    @Override
    public synchronized boolean abandonMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        Message m = validClaim(accountId, lease, claim);
        if (m == null) return false;
        m.abandoned = true;
        m.completedAt = clock.millis();
        m.payload = null;
        m.claimId = null;
        return true;
    }

    /**
     * Expires tombstones in every account and forgets accounts that hold no state at all. Called on
     * each accepted batch, so the cost is proportional to the accounts seen while this process runs.
     *
     * <p>An account with a cursor or peer context is never dropped here: that state is the
     * consumer's position, and forgetting it would replay the provider backlog.
     */
    private void prune(String activeAccountId) {
        long now = clock.millis();
        for (Account account : accounts.values()) {
            account.inbox
                    .values()
                    .removeIf(
                            m ->
                                    (m.completed || m.abandoned)
                                            && m.completedAt < now - RETENTION_MS);
        }
        accounts.entrySet()
                .removeIf(
                        entry ->
                                !entry.getKey().equals(activeAccountId)
                                        && entry.getValue().expiresAt <= now
                                        && entry.getValue().cursor.isEmpty()
                                        && entry.getValue().contexts.isEmpty()
                                        && entry.getValue().inbox.isEmpty());
    }

    private Message validClaim(String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        if (!isLeaseCurrent(accountId, lease)) return null;
        Account a = existing(accountId);
        Message m = a == null ? null : a.inbox.get(claim.messageId());
        return m != null
                        && !m.completed
                        && lease.equals(m.lease)
                        && claim.claimId().equals(m.claimId)
                        && m.claimUntil > clock.millis()
                ? m
                : null;
    }

    private static final class Account {
        String cursor = "";
        long generation;
        long expiresAt;
        WeixinLease lease;
        final Map<String, String> contexts = new HashMap<>();
        final Map<String, Message> inbox = new LinkedHashMap<>();
    }

    private static final class Message {
        String payload;
        WeixinLease lease;
        String claimId;
        long claimUntil;
        boolean completed;
        boolean abandoned;
        long completedAt;

        Message(String payload) {
            this.payload = payload;
        }
    }
}
