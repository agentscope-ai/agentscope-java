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

/** Standalone/test adapter with the same fencing and claim rules as a durable host. */
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

    private Account account(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("accountId is required");
        return accounts.computeIfAbsent(id, ignored -> new Account());
    }

    @Override
    public synchronized String loadCursor(String accountId) {
        return account(accountId).cursor;
    }

    @Override
    public synchronized String loadContextToken(String accountId, String peerId) {
        return account(accountId).contexts.get(peerId);
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
        Account a = account(accountId);
        return lease != null && lease.equals(a.lease) && a.expiresAt > clock.millis();
    }

    @Override
    public synchronized void releaseLease(String accountId, WeixinLease lease) {
        Account a = account(accountId);
        if (lease != null && lease.equals(a.lease)) a.expiresAt = 0;
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
        a.inbox
                .values()
                .removeIf(m -> m.completed && m.completedAt < clock.millis() - RETENTION_MS);
        for (WeixinInboxMessage message : batch) {
            a.inbox.putIfAbsent(message.messageId(), new Message(message.payload()));
        }
        if (nextCursor != null && !nextCursor.isBlank()) a.cursor = nextCursor;
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
            if (m.completed) continue;
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

    private Message validClaim(String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        if (!isLeaseCurrent(accountId, lease)) return null;
        Message m = account(accountId).inbox.get(claim.messageId());
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
        long completedAt;

        Message(String payload) {
            this.payload = payload;
        }
    }
}
