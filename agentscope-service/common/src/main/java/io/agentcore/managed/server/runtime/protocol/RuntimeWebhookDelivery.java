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
package io.agentcore.managed.server.runtime.protocol;

import java.util.List;

/** Private Scheduler transport. Only this short lived claim contains the signing secret. */
public record RuntimeWebhookDelivery(
        int version,
        String webhookId,
        String deliveryId,
        String sessionId,
        String claimToken,
        long claimFence,
        long leaseUntil,
        String url,
        String signingSecret,
        String payload) {
    public RuntimeWebhookDelivery {
        if (version != 1 || claimFence < 1 || leaseUntil < 1)
            throw new IllegalArgumentException("Invalid webhook claim");
        for (String value : new String[] {webhookId, deliveryId, sessionId, claimToken})
            if (value == null || value.isBlank() || value.length() > 256)
                throw new IllegalArgumentException("Invalid webhook identity");
        if (url == null
                || url.length() > 4096
                || signingSecret == null
                || signingSecret.length() > 256
                || payload == null
                || payload.length() > 8192)
            throw new IllegalArgumentException("Invalid webhook payload");
    }

    @Override
    public String toString() {
        return "RuntimeWebhookDelivery[deliveryId="
                + deliveryId
                + ", claimFence="
                + claimFence
                + "]";
    }

    public Proof proof() {
        return new Proof(1, webhookId, deliveryId, claimToken, claimFence);
    }

    public record ClaimRequest(int version, String workerId, int limit) {
        public ClaimRequest {
            if (version != 1
                    || workerId == null
                    || workerId.isBlank()
                    || workerId.length() > 128
                    || limit < 1
                    || limit > 8) throw new IllegalArgumentException("Invalid webhook batch");
        }
    }

    public record Batch(int version, List<RuntimeWebhookDelivery> deliveries) {
        public Batch {
            if (version != 1 || deliveries == null || deliveries.size() > 8)
                throw new IllegalArgumentException("Invalid webhook batch");
            deliveries = List.copyOf(deliveries);
        }
    }

    public record Proof(
            int version, String webhookId, String deliveryId, String claimToken, long claimFence) {
        public Proof {
            if (version != 1 || claimFence < 1)
                throw new IllegalArgumentException("Invalid webhook proof");
            for (String value : new String[] {webhookId, deliveryId, claimToken})
                if (value == null || value.isBlank() || value.length() > 256)
                    throw new IllegalArgumentException("Invalid webhook proof");
        }
    }

    public record Receipt(Proof proof, int httpStatus, String errorCode) {
        public Receipt {
            if (proof == null
                    || httpStatus != 0 && (httpStatus < 100 || httpStatus > 599)
                    || errorCode == null
                    || !List.of("", "transport", "blocked").contains(errorCode))
                throw new IllegalArgumentException("Invalid webhook receipt");
        }
    }
}
