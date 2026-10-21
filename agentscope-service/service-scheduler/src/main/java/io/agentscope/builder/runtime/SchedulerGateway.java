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

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Scheduler transport gateway. The control plane owns identity, routing and durable work intake. */
@Component
public class SchedulerGateway implements Gateway {

    private static final Logger log = LoggerFactory.getLogger(SchedulerGateway.class);

    private final io.agentscope.builder.web.managed.ChannelWorkBridge work;

    public SchedulerGateway(io.agentscope.builder.web.managed.ChannelWorkBridge work) {
        this.work = work;
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
        return Mono.error(new IllegalArgumentException("Normalized channel identity is required"));
    }

    @Override
    public Mono<Msg> run(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress address,
            io.agentscope.core.agent.RuntimeContext callerContext,
            io.agentscope.harness.agent.gateway.channel.InboundMessage inbound) {
        return work.receive(inbound);
    }
}
