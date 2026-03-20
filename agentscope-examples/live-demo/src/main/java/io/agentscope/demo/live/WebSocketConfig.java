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
package io.agentscope.demo.live;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * WebSocket configuration for Live Demo.
 *
 * <p>Configures the WebSocket endpoint at /live-chat for real-time voice conversation.
 */
@Configuration
public class WebSocketConfig {

    @Value("${live.model-name:qwen3-omni-flash-realtime}")
    private String modelName;

    @Value("${live.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Value("${live.agent-name:voice-assistant}")
    private String agentName;

    @Value("${live.system-prompt:You are a friendly voice assistant. Keep responses concise.}")
    private String systemPrompt;

    @Value("${live.voice:Cherry}")
    private String voice;

    @Bean
    public HandlerMapping webSocketHandlerMapping() {
        Map<String, WebSocketHandler> map = new HashMap<>();
        map.put("/live-chat", liveWebSocketHandler());

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(map);
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public LiveWebSocketHandler liveWebSocketHandler() {
        return new LiveWebSocketHandler(modelName, apiKey, agentName, systemPrompt, voice);
    }
}
