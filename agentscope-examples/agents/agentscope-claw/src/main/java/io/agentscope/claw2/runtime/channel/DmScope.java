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
package io.agentscope.claw2.runtime.channel;

/**
 * Controls how DM ({@link PeerKind#DIRECT}) session keys are scoped.
 *
 * <p>Options for {@code session.dmScope}:
 *
 * <ul>
 *   <li>{@link #MAIN} — all DMs for a given agent share a single session ({@code
 *       agent:<agentId>:main}). Suitable for single-user or shared assistant scenarios.
 *   <li>{@link #PER_PEER} — one session per peer id ({@code
 *       agent:<agentId>:<channel>:direct:<peerId>}). Most common for multi-user deployments.
 *   <li>{@link #PER_CHANNEL_PEER} — like {@code PER_PEER} but the channel name is included in the
 *       key, disambiguating the same peer across channels.
 *   <li>{@link #PER_ACCOUNT_CHANNEL_PEER} — extends {@code PER_CHANNEL_PEER} with the account id,
 *       useful for multi-account (multi-bot) deployments on the same channel platform.
 * </ul>
 */
public enum DmScope {

    /**
     * All DM conversations for this agent share one session. The session key is derived from the
     * agent alone (equivalent to {@link io.agentscope.claw2.runtime.gateway.MsgContext#defaultContext()}).
     */
    MAIN,

    /**
     * Separate session per peer id. The session key encodes {@code channel + "direct" + peerId}.
     */
    PER_PEER,

    /**
     * Separate session per channel + peer id. Differentiates the same user chatting through two
     * distinct channel integrations.
     */
    PER_CHANNEL_PEER,

    /**
     * Separate session per account + channel + peer id. Required when one platform hosts multiple
     * bot accounts (e.g. several Slack apps in the same workspace).
     */
    PER_ACCOUNT_CHANNEL_PEER;

    /** Default scope used when none is configured. */
    public static DmScope defaultScope() {
        return MAIN;
    }
}
