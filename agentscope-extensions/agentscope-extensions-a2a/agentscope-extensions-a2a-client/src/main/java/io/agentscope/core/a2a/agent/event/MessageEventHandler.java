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

package io.agentscope.core.a2a.agent.event;

import io.a2a.client.MessageEvent;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.a2a.agent.utils.MessageConvertUtil;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@link MessageEvent}.
 */
public class MessageEventHandler implements ClientEventHandler<MessageEvent> {

    private static final Logger log = LoggerFactory.getLogger(MessageEventHandler.class);

    @Override
    public Class<MessageEvent> getHandleEventType() {
        return MessageEvent.class;
    }

    @Override
    public void handle(MessageEvent event, ClientEventContext context) {
        String currentRequestId = context.getCurrentRequestId();
        Msg msg = MessageConvertUtil.convertFromMessage(event.getMessage());
        context.getSink().success(msg);
        LoggerUtil.info(log, "[{}] A2aAgent complete call.", currentRequestId);
        LoggerUtil.debug(log, "[{}] A2aAgent complete with artifact messages: ", currentRequestId);
        LoggerUtil.logTextMsgDetail(log, List.of(msg));
    }
}
