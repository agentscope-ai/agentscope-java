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

import java.util.List;
import java.util.Optional;

/**
 * Account-scoped state for at-least-once message processing. Implementations must atomically
 * validate the unexpired lease on every mutation. External Agent and provider side effects are
 * not part of this transaction and can be repeated after a crash.
 */
public interface WeixinStateStore {
    String loadCursor(String accountId);

    String loadContextToken(String accountId, String peerId);

    boolean saveContextToken(String accountId, WeixinLease lease, String peerId, String token);

    /** A released or expired lease must never reuse its generation, even for the same holder. */
    Optional<WeixinLease> acquireLease(String accountId, String holderId, long leaseMs);

    boolean renewLease(String accountId, WeixinLease lease, long leaseMs);

    boolean isLeaseCurrent(String accountId, WeixinLease lease);

    void releaseLease(String accountId, WeixinLease lease);

    /** Saves the whole batch and its cursor in one transaction, deduplicating message IDs. */
    boolean acceptBatch(
            String accountId,
            WeixinLease lease,
            String nextCursor,
            List<WeixinInboxMessage> messages);

    /** Claims in reception order. A new lease may immediately recover the previous owner's work. */
    List<WeixinInboxClaim> claimMessages(
            String accountId, WeixinLease lease, int limit, long claimMs);

    /** Only the exact, unexpired claim may finish. Completed payloads must be removed. */
    boolean completeMessage(String accountId, WeixinLease lease, WeixinInboxClaim claim);

    boolean failMessage(String accountId, WeixinLease lease, WeixinInboxClaim claim);

    static WeixinStateStore inMemory() {
        return new InMemoryWeixinStateStore();
    }
}
