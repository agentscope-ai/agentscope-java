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

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;

/**
 * Interface for agents that support real-time bidirectional streaming conversation.
 *
 * <p>This interface is parallel to {@link StreamableAgent}, providing real-time voice
 * conversation capabilities.
 *
 * <p>Input stream can contain:
 * <ul>
 *   <li>{@code AudioBlock} - Audio data</li>
 *   <li>{@code TextBlock} - Text data (supported by some providers)</li>
 *   <li>{@code ImageBlock} - Image data (supported by some providers)</li>
 *   <li>{@code ControlBlock} - Control signals (interrupt, commit, etc.)</li>
 * </ul>
 *
 * <p>Output stream contains {@link LiveEvent}, with types including:
 * <ul>
 *   <li>AUDIO_DELTA - Audio increments</li>
 *   <li>TEXT_DELTA - Text increments</li>
 *   <li>INPUT_TRANSCRIPTION - Input transcription</li>
 *   <li>OUTPUT_TRANSCRIPTION - Output transcription</li>
 *   <li>TOOL_CALL - Tool invocation</li>
 *   <li>TURN_COMPLETE - Turn completion</li>
 *   <li>SESSION_CREATED/ENDED - Session lifecycle</li>
 *   <li>ERROR - Error</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * LiveableAgent agent = LiveAgent.builder()
 *     .name("voice-assistant")
 *     .systemPrompt("You are a friendly voice assistant.")
 *     .liveModel(dashScopeLiveModel)
 *     .toolkit(toolkit)
 *     .build();
 *
 * // Create input stream
 * Sinks.Many<Msg> inputSink = Sinks.many().unicast().onBackpressureBuffer();
 *
 * // Start live conversation
 * agent.live(inputSink.asFlux(), LiveConfig.defaults())
 *     .subscribe(event -> {
 *         switch (event.type()) {
 *             case AUDIO_DELTA -> playAudio(event);
 *             case TEXT_DELTA -> displayText(event);
 *             case TOOL_CALL -> showToolCall(event);
 *             case ERROR -> handleError(event);
 *         }
 *     });
 *
 * // Send audio
 * inputSink.tryEmitNext(Msg.builder()
 *     .role(MsgRole.USER)
 *     .content(AudioBlock.builder()
 *         .source(RawSource.pcm16kMono(audioData))
 *         .build())
 *     .build());
 *
 * // Send interrupt signal
 * inputSink.tryEmitNext(Msg.builder()
 *     .role(MsgRole.CONTROL)
 *     .content(ControlBlock.interrupt())
 *     .build());
 * }</pre>
 *
 * @see LiveEvent
 * @see LiveConfig
 */
public interface LiveableAgent {

    /**
     * Start a live conversation session.
     *
     * @param input Input stream (Msg containing AudioBlock/TextBlock/ImageBlock/ControlBlock)
     * @param config Live session configuration
     * @return Output event stream (LiveEvent)
     */
    Flux<LiveEvent> live(Flux<Msg> input, LiveConfig config);

    /**
     * Start a live conversation with default configuration.
     *
     * @param input Input stream
     * @return Output event stream
     */
    default Flux<LiveEvent> live(Flux<Msg> input) {
        return live(input, LiveConfig.defaults());
    }
}
