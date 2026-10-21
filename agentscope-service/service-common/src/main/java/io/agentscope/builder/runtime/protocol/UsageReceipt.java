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
package io.agentscope.builder.runtime.protocol;

import java.math.BigDecimal;
import java.util.Objects;

/** One accounting increment; cumulative summaries are not additional chargeable receipts. */
public record UsageReceipt(
        RuntimeRequestContext context,
        String receiptId,
        String sourceEventId,
        String accountingDomain,
        long inputTokens,
        long outputTokens,
        BigDecimal reportedCost,
        String currency) {
    public UsageReceipt {
        Objects.requireNonNull(context, "context");
        RuntimeProtocol.requireId(receiptId, "receiptId");
        RuntimeProtocol.requireId(sourceEventId, "sourceEventId");
        RuntimeProtocol.requireId(accountingDomain, "accountingDomain");
        if (inputTokens < 0 || outputTokens < 0)
            throw new IllegalArgumentException("Token increments must not be negative");
        if (reportedCost != null && reportedCost.signum() < 0)
            throw new IllegalArgumentException("Cost increment must not be negative");
    }
}
