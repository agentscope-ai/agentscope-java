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
package io.agentscope.core.message;

/**
 * Type of transcription in Live sessions.
 *
 * <p>Distinguishes between user input transcription (ASR) and model output transcription (TTS
 * text).
 */
public enum TranscriptionType {

    /**
     * Input transcription - user's speech converted to text (ASR).
     *
     * <p>Provider events:
     *
     * <ul>
     *   <li>DashScope: conversation.item.input_audio_transcription.completed
     *   <li>OpenAI: conversation.item.input_audio_transcription.completed/delta
     *   <li>Gemini: serverContent.inputTranscription
     *   <li>Doubao: ASRResponse (451)
     * </ul>
     */
    INPUT,

    /**
     * Output transcription - model's audio response as text.
     *
     * <p>Provider events:
     *
     * <ul>
     *   <li>DashScope: response.audio_transcript.delta/done
     *   <li>OpenAI: response.output_audio_transcript.delta/done
     *   <li>Gemini: serverContent.outputTranscription
     *   <li>Doubao: TTSSentenceStart (350)
     * </ul>
     */
    OUTPUT
}
