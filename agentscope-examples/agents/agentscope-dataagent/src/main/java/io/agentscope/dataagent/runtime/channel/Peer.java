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
package io.agentscope.dataagent.runtime.channel;

import java.util.Objects;

/**
 * Identifies the conversation peer: who or what is being messaged (a user DM, a channel, a group,
 * or a thread). Used by {@link ChannelRouter} for binding evaluation and session key construction.
 *
 * @param kind the peer classification
 * @param id provider-assigned peer identifier (user id, channel id, thread id, etc.)
 */
public record Peer(PeerKind kind, String id) {

    public Peer {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    /** Returns the composite key {@code "<kind>:<id>"} used in binding matching. */
    public String key() {
        return kind.value() + ":" + id;
    }

    /** Creates a direct / DM peer. */
    public static Peer direct(String id) {
        return new Peer(PeerKind.DIRECT, id);
    }

    /** Creates a channel peer. */
    public static Peer channel(String id) {
        return new Peer(PeerKind.CHANNEL, id);
    }

    /** Creates a group peer. */
    public static Peer group(String id) {
        return new Peer(PeerKind.GROUP, id);
    }

    /** Creates a thread peer. */
    public static Peer thread(String id) {
        return new Peer(PeerKind.THREAD, id);
    }
}
