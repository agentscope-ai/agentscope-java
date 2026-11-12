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
package io.agentscope.core.agent;

/**
 * User implementation: computes the capabilities a user is granted (pure whitelists).
 *
 * @author shanhongyu
 */
@FunctionalInterface
public interface CapabilityResolver {

    /**
     * Computes the per-request capability grant set for the user identified by the {@link
     * RuntimeContext} (e.g. {@link RuntimeContext#getUserId()}), optionally differentiated per
     * agent.
     *
     * <p>Only an all-null surface skips trimming. Once any dimension is set — even to an
     * explicit empty set — the surface is a complete grant enumeration and unset (null)
     * dimensions contribute no grants.
     *
     * @param ctx the per-call runtime context carrying the caller identity
     * @param agent the agent being called (for per-agent granularity; may be ignored)
     * @return the grant set; {@code null} = no trimming (normalized to {@link
     *     CapabilitySurface#ALLOW_ALL})
     * @throws RuntimeException propagates and aborts the call — an authorization
     *     infrastructure failure is not "no grants"; catch internally and return {@link
     *     CapabilitySurface#DENY_ALL} if graceful degradation is wanted
     */
    CapabilitySurface resolve(RuntimeContext ctx, Agent agent);
}
