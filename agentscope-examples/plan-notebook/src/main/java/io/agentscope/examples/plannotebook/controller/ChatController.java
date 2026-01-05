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
package io.agentscope.examples.plannotebook.controller;

import io.agentscope.examples.plannotebook.service.AgentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Controller for chat API.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Chat endpoint with SSE streaming.
     *
     * @param message User message
     * @param sessionId Session ID (optional, defaults to "default")
     * @return Flux of streaming text chunks
     */
    @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(
            @RequestParam String message,
            @RequestParam(defaultValue = "default") String sessionId) {
        return agentService.chat(sessionId, message);
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    /**
     * Reset the agent and clear all conversations and plans.
     */
    @PostMapping("/reset")
    public String reset() {
        agentService.reset();
        return "OK";
    }
}
