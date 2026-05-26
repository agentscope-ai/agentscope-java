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

/**
 * Classification of the conversation peer, mirroring OpenClaw's peer kinds used for session key
 * construction and DM-scope resolution.
 *
 * <ul>
 *   <li>{@link #DIRECT} — one-to-one DM / private conversation
 *   <li>{@link #CHANNEL} — public or private channel / room
 *   <li>{@link #GROUP} — group chat (WhatsApp group, Telegram supergroup, etc.)
 *   <li>{@link #THREAD} — thread nested inside a {@link #CHANNEL} or {@link #GROUP} peer
 * </ul>
 */
public enum PeerKind {
    DIRECT("direct"),
    CHANNEL("channel"),
    GROUP("group"),
    THREAD("thread");

    private final String value;

    PeerKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Whether this peer kind represents a direct (one-to-one) message. */
    public boolean isDirect() {
        return this == DIRECT;
    }

    /** Whether this peer kind represents a thread nested inside another peer. */
    public boolean isThread() {
        return this == THREAD;
    }
}
