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
package io.agentscope.extensions.channel.dingtalk;

import java.util.Optional;

/**
 * Resolves the {@link DingTalkChannelProperties} of a tenant from the routing key carried by the
 * callback URL path.
 *
 * <p>The static wiring assumes the channel set and its credentials are fixed at build time. In a
 * multi-tenant deployment they are runtime data instead: tenants connect their own DingTalk
 * enterprise app, the credentials (app key, app secret, robot code, callback AES key) live in an
 * application-owned store keyed by the routing key, and rows are added, rotated and removed while
 * the process serves traffic. Supplying a resolver to {@link DingTalkTenantChannelManager} lets
 * {@link DingTalkCallbackController} obtain credentials per callback instead of reading them from a
 * pre-built channel instance.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>The argument is the {@code {tenantKey}} path segment of
 *       {@code /api/channels/dingtalk/{tenantKey}/callback} — the application-chosen id it
 *       registers with the platform as part of the callback URL.
 *   <li>Resolved properties must configure {@link DingTalkChannelProperties#MODE_HTTP mode=http}:
 *       tenant channels are materialized per callback, which only the http reception mode supports.
 *       A stream-mode WebSocket binds its credentials at connect time and cannot be served this
 *       way.
 *   <li>An empty result means the tenant does not exist; the callback is rejected without
 *       dispatching. Returning empty for a transient lookup failure would silently stop serving a
 *       live tenant — fail loudly instead.
 *   <li>The resolver is consulted on every callback, so a rotation takes effect on the next
 *       request. Implementations that query a database on each call should cache application-side.
 *   <li>Implementations must be thread-safe; callbacks are served concurrently.
 * </ul>
 */
@FunctionalInterface
public interface DingTalkCredentialResolver {

    /**
     * Returns the credentials for {@code tenantKey}.
     *
     * @param tenantKey the routing key from the callback URL path
     * @return the tenant's properties, or empty when no such tenant exists
     */
    Optional<DingTalkChannelProperties> resolve(String tenantKey);
}
