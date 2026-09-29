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
package io.agentscope.extensions.channel.common;

/**
 * Deduplicates inbound platform events across redeliveries.
 *
 * <p>Channel adapters consult this before processing an inbound event: delivery platforms (WeCom,
 * DingTalk, GitHub, GitLab, ...) redeliver the same event when no timely acknowledgment is
 * received, and each redelivery must execute at most once.
 *
 * <p>Keys are opaque and caller-composed — typically {@code channelId + "|" + platformEventId}.
 * Implementations backed by shared storage should namespace keys per deployment (for example with
 * an environment or application prefix): platform event ids are only unique within one channel
 * deployment, and a key shared by two deployments silently drops live traffic.
 *
 * <p>The default {@link IdempotencyStore} keeps state in the JVM heap, which only deduplicates
 * within a single process: a redelivery that lands on another instance, or after a restart, is not
 * recognized. Deployments running multiple channel instances should provide an implementation
 * backed by shared storage (for example a Redis {@code SET ... NX EX} keyed by channel and
 * platform event id). Injection today is programmatic: channels expose a {@code
 * fromProperties(channelId, routing, rawProperties, InboundEventDeduplicator)} factory overload
 * for hand-wired setups, while the config-driven path ({@code agentscope.json} resolved through
 * the harness {@code ChannelFactory}) constructs channels with the default process-local store.
 *
 * <p>Implementations must be thread-safe: channels invoke {@link #firstSeen(String)} from
 * concurrent callback threads.
 */
public interface InboundEventDeduplicator {

    /**
     * Records {@code key} and reports whether this is its first observation.
     *
     * @param key opaque deduplication key composed by the caller; {@code null} is treated as
     *     always first-seen
     * @return {@code true} when {@code key} has not been observed within the implementation's
     *     retention horizon and the caller should process the event; {@code false} when it is a
     *     redelivery and must be dropped
     */
    boolean firstSeen(String key);
}
