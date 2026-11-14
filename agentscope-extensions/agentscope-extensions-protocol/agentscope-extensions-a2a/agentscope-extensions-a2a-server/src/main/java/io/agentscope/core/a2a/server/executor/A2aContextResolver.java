/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server.executor;

import io.agentscope.core.agent.RuntimeContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;

/**
 * Deployment-side adjudication of the user identity of an A2A call.
 *
 * <p>A2A is server-to-server: the deployment can read an already verified identity from the
 * transport layer, for example an mTLS certificate or an OAuth token, via {@link
 * RequestContext#getCallContext()}. The user id asserted by the client in the message envelope
 * ({@code _agentscope_user_id}) is a delegation claim — it names whom the caller acts for — and
 * must not override a transport-verified identity.
 *
 * <p>Priority: resolver (transport-verified) &gt; envelope-asserted user id &gt; none. Only the
 * user id of the returned {@link RuntimeContext} is used; a {@code null} or blank one means "not
 * adjudicated", and the envelope assertion stands. The two disagreeing is logged as a warning.
 *
 * <p>Fail closed: {@code null} deliberately accepts the envelope assertion — so a resolver whose
 * verification <em>fails</em> must throw, not return {@code null}; the exception rejects the call
 * instead of silently downgrading to the unverified identity. The resolved user id keys per-user
 * agent state, session logs, and tool-visible identity, so treat it as authorization input.
 *
 * <p>Example:
 * <pre>{@code
 * A2aContextResolver resolver = context -> {
 *     ServerCallContext call = context.getCallContext();
 *     String verified = verifiedUserIdOf(call); // read mTLS cert / OAuth token
 *     return verified == null ? null
 *             : RuntimeContext.builder().userId(verified).build();
 * };
 * }</pre>
 */
@FunctionalInterface
public interface A2aContextResolver {

    /**
     * Resolves the transport-verified identity of the call.
     *
     * @param context the request context of the A2A call
     * @return a {@link RuntimeContext} whose user id is the verified identity, or {@code null}
     *     when this call is not adjudicated
     */
    RuntimeContext resolve(RequestContext context);
}
