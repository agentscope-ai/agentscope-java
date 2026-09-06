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

import io.agentscope.core.agent.RuntimeContext;

/**
 * Resolves a caller-provided {@link RuntimeContext} for a Channel / Gateway turn.
 *
 * <p>Applications can plug this into {@link
 * io.agentscope.harness.agent.gateway.HarnessGateway} (or via {@link
 * io.agentscope.harness.agent.gateway.GatewayBootstrap}) to attach request-scoped values such as
 * tenant information, force-sync flags, or tool dependencies. Gateway identity fields ({@code
 * sessionId}, {@code userId}, {@code outboundAddress}, …) are still applied after resolution and
 * take precedence over conflicting caller values.
 *
 * <p>Return {@code null} to leave the existing caller context (from {@link
 * ChannelRuntimeContextRequest#callerContext()}) unchanged.
 */
@FunctionalInterface
public interface ChannelRuntimeContextResolver {

    /**
     * Resolve the runtime context for the current channel turn.
     *
     * @param request channel turn metadata, including any caller-supplied context
     * @return a runtime context to use as the merge base, or {@code null} to keep {@link
     *     ChannelRuntimeContextRequest#callerContext()}
     */
    RuntimeContext resolve(ChannelRuntimeContextRequest request);
}
