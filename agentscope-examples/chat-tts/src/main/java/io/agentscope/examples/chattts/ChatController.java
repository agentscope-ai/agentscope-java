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
package io.agentscope.examples.chattts;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Chat controller with real-time TTS streaming.
 *
 * <p>Provides SSE endpoint that streams both text and audio to frontend:
 * <ul>
 *   <li>event: "text" - LLM generated text chunks</li>
 *   <li>event: "audio" - Base64 encoded audio chunks</li>
 *   <li>event: "done" - Stream completed</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private final DashScopeChatModel chatModel;
    private final DashScopeRealtimeTTSModel ttsModel;

    /**
     * Creates a new ChatController.
     *
     * <p>Requires DASHSCOPE_API_KEY environment variable to be set.
     *
     * @throws IllegalStateException if DASHSCOPE_API_KEY is not set
     */
    public ChatController() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY environment variable is required");
        }

        this.chatModel = DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").build();

        this.ttsModel =
                DashScopeRealtimeTTSModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3-tts-flash")
                        .voice("Cherry")
                        .sampleRate(24000)
                        .format("wav")
                        .build();
    }

    /**
     * Chat endpoint with real-time TTS.
     *
     * <p>Returns SSE stream with text and audio events:
     * <pre>
     * event: text
     * data: {"text": "你好"}
     *
     * event: audio
     * data: {"audio": "base64..."}
     *
     * event: done
     * data: {"status": "completed"}
     * </pre>
     *
     * @param request containing "message" field
     * @return SSE stream of text and audio
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chat(
            @RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isEmpty()) {
            return Flux.just(
                    ServerSentEvent.<Map<String, Object>>builder()
                            .event("error")
                            .data(Map.of("error", "Message is required"))
                            .build());
        }

        Sinks.Many<ServerSentEvent<Map<String, Object>>> sink =
                Sinks.many().multicast().onBackpressureBuffer();

        // Create TTSHook that sends audio to frontend via SSE
        TTSHook ttsHook =
                TTSHook.builder()
                        .ttsModel(ttsModel)
                        .realtimeMode(true)
                        .audioCallback(
                                audio -> {
                                    if (audio.getSource() instanceof Base64Source src) {
                                        sink.tryEmitNext(
                                                ServerSentEvent.<Map<String, Object>>builder()
                                                        .event("audio")
                                                        .data(Map.of("audio", src.getData()))
                                                        .build());
                                    }
                                })
                        .build();

        // Create agent with TTS hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("你是一个友好的中文助手。请用简洁的中文回答问题。")
                        .model(chatModel)
                        .hook(ttsHook)
                        .maxIters(3)
                        .build();

        // Create user message
        Msg userMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        // Stream agent response
        agent.stream(
                        userMsg,
                        StreamOptions.builder()
                                .eventTypes(EventType.REASONING)
                                .incremental(true)
                                .build())
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(
                        event -> {
                            String text = event.getMessage().getTextContent();
                            if (text != null && !text.isEmpty()) {
                                sink.tryEmitNext(
                                        ServerSentEvent.<Map<String, Object>>builder()
                                                .event("text")
                                                .data(
                                                        Map.of(
                                                                "text",
                                                                text,
                                                                "isLast",
                                                                event.isLast()))
                                                .build());
                            }
                        })
                .doOnComplete(
                        () -> {
                            ttsHook.stop();
                            sink.tryEmitNext(
                                    ServerSentEvent.<Map<String, Object>>builder()
                                            .event("done")
                                            .data(Map.of("status", "completed"))
                                            .build());
                            sink.tryEmitComplete();
                        })
                .doOnError(
                        e -> {
                            sink.tryEmitNext(
                                    ServerSentEvent.<Map<String, Object>>builder()
                                            .event("error")
                                            .data(Map.of("error", e.getMessage()))
                                            .build());
                            sink.tryEmitComplete();
                        })
                .subscribe();

        return sink.asFlux();
    }
}
