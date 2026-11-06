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

/** Receives technical runtime observations without imposing a host product lifecycle. */
public interface WeixinRuntimeListener {

    default void onLeaseAcquired(String accountId, WeixinLease lease) {}

    default void onRunning(String accountId) {}

    default void onStopped(String accountId) {}

    default void onCredentialRejected(String accountId, String reason) {}

    default void onTransientFailure(String accountId, String reason) {}

    /**
     * A reply the provider refused. The Agent has already run, so a delivery failure never replays
     * the inbound message; the host reports it and its own delivery queue decides what happens next.
     */
    default void onDeliveryFailed(String accountId, String reason) {}

    /**
     * An accepted inbound message whose dispatch kept failing, so it was abandoned instead of
     * retried forever. Distinct from {@link #onDeliveryFailed}: the Agent may never have finished.
     */
    default void onDispatchFailed(String accountId, String reason) {}

    default void onRecovered(String accountId) {}

    static WeixinRuntimeListener noOp() {
        return new WeixinRuntimeListener() {};
    }
}
