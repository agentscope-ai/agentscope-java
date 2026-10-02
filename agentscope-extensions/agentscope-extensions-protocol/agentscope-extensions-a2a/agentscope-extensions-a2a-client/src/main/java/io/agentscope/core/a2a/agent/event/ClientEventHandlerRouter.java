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

import io.a2a.client.ClientEvent;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Strategy router for {@link ClientEventHandler}.
 */
public class ClientEventHandlerRouter {

    private static final Logger log = LoggerFactory.getLogger(ClientEventHandlerRouter.class);

    private final Map<Class<? extends ClientEvent>, ClientEventHandler<? extends ClientEvent>>
            handlers;

    public ClientEventHandlerRouter() {
        this.handlers = new HashMap<>(3);
        registerHandler(new TaskUpdateEventHandler());
        registerHandler(new MessageEventHandler());
        registerHandler(new TaskEventHandler());
    }

    private void registerHandler(ClientEventHandler<? extends ClientEvent> handler) {
        handlers.put(handler.getHandleEventType(), handler);
    }

    /**
     * Handle {@link ClientEvent} By event type.
     *
     * <p>If not found handler for this event type, it will return empty.
     *
     * @param event   client event from A2A server
     * @param context client event context
     */
    @SuppressWarnings("unchecked")
    public <T extends ClientEvent> void handle(T event, ClientEventContext context) {
        if (!handlers.containsKey(event.getClass())) {
            LoggerUtil.debug(
                    log,
                    "[{}] No found handler for event {}, ignore this event",
                    context.getCurrentRequestId(),
                    event.getClass().getSimpleName());
            return;
        }
        ClientEventHandler<T> handler = (ClientEventHandler<T>) handlers.get(event.getClass());
        handler.handle(event, context);
    }
}
