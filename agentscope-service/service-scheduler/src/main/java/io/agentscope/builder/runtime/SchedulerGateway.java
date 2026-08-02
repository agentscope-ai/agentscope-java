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
package io.agentscope.builder.runtime;

import io.agentscope.builder.web.config.ChannelRuntimeCatalog;
import io.agentscope.builder.web.managed.ChannelExternalKeys;
import io.agentscope.builder.web.managed.ManagedSessionChannelBridge;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Scheduler-side {@link Gateway}: the inbound sink every channel adapter delivers to via {@code
 * ChannelManager#initAll}. Unlike the monolith's in-process gateway, this implementation holds
 * no {@code HarnessAgent} — each inbound turn is bridged over HTTP into the control/data planes
 * by {@link ManagedSessionChannelBridge}:
 *
 * <ul>
 *   <li>target agent — {@code MsgContext.extra().get("agentId")}, resolved by the channel's own
 *       router from bindings / channel default before delivery;
 *   <li>session owner — Builder tenant from {@link ChannelRuntimeCatalog} (channel configurator);
 *   <li>conversation slot — {@link ChannelExternalKeys} derived from dmScope + peer;
 *   <li>reply — the bridge posts the message as a {@code user.message} event and waits for the
 *       turn's terminal status, returning the final {@code agent.message} text.
 * </ul>
 */
@Component
public class SchedulerGateway implements Gateway {

    private static final Logger log = LoggerFactory.getLogger(SchedulerGateway.class);

    private final ManagedSessionChannelBridge bridge;
    private final ChannelRuntimeCatalog catalog;

    public SchedulerGateway(ManagedSessionChannelBridge bridge, ChannelRuntimeCatalog catalog) {
        this.bridge = bridge;
        this.catalog = catalog;
    }

    /** No-op: the scheduler runs no local agents. */
    @Override
    public void bindMainAgent(HarnessAgent agent) {
        log.debug("bindMainAgent ignored on scheduler plane (agent={})", agent.getAgentId());
    }

    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return run(context, messages, null);
    }

    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String agentId = ctx.extra().get("agentId");
        if (agentId == null || agentId.isBlank()) {
            return Mono.error(
                    new IllegalStateException(
                            "No agent bound for channel '" + ctx.channel() + "' inbound"));
        }
        String ownerId = catalog.ownerId(ctx.channel());
        if (ownerId == null || ownerId.isBlank()) {
            return Mono.error(
                    new IllegalStateException(
                            "No Builder owner for channel '"
                                    + ctx.channel()
                                    + "' — ensure the channel is registered in the control plane"));
        }
        String text = extractUserText(messages);
        if (text == null) {
            return Mono.empty();
        }
        String externalKey =
                ChannelExternalKeys.forInbound(
                        ctx, outboundAddress, catalog.dmScope(ctx.channel()));
        return bridge.dispatchAndAwaitReply(ownerId, agentId, externalKey, text)
                .map(reply -> Msg.builder().role(MsgRole.ASSISTANT).textContent(reply).build());
    }

    /** Returns the text of the last {@code USER}-role message, or {@code null} if none. */
    private static String extractUserText(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m != null && m.getRole() == MsgRole.USER && m.getTextContent() != null) {
                return m.getTextContent();
            }
        }
        return null;
    }
}
