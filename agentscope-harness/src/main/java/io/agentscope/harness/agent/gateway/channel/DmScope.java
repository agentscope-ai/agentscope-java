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
package io.agentscope.harness.agent.gateway.channel;

/**
 * Controls how DM ({@link PeerKind#DIRECT}) session keys are scoped.
 *
 * <ul>
 *   <li>{@link #MAIN} — all DMs for a given agent share a single session. Suitable for single-user
 *       or shared assistant scenarios.
 *   <li>{@link #PER_PEER} — one session per peer id. Most common for multi-user deployments.
 *   <li>{@link #PER_CHANNEL_PEER} — like {@code PER_PEER} but the channel name is included in the
 *       key, disambiguating the same peer across channels.
 *   <li>{@link #PER_ACCOUNT_CHANNEL_PEER} — extends {@code PER_CHANNEL_PEER} with the account id,
 *       useful for multi-account (multi-bot) deployments on the same channel platform.
 * </ul>
 */
public enum DmScope {
    MAIN,
    PER_PEER,
    PER_CHANNEL_PEER,
    PER_ACCOUNT_CHANNEL_PEER;

    /** Default scope used when none is configured. */
    public static DmScope defaultScope() {
        return MAIN;
    }
}
