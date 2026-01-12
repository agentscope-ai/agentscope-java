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
package io.agentscope.core.live.audio;

/**
 * Audio resampler interface for converting between different sample rates.
 *
 * <p>Used internally by Formatters to convert audio data to the required format for each provider.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * // In a Formatter implementation
 * public class OpenAILiveFormatter extends AbstractTextLiveFormatter {
 *     private final AudioResampler resampler = AudioResampler.linear();
 *     private final AudioFormat requiredFormat = AudioFormat.PCM_24K_16BIT_MONO;
 *
 *     protected String formatAudio(byte[] audioData, AudioFormat sourceFormat) {
 *         byte[] resampled = resampler.resample(audioData, sourceFormat, requiredFormat);
 *         return toJson(Map.of("audio", encodeBase64(resampled)));
 *     }
 * }
 * }</pre>
 */
public interface AudioResampler {

    /**
     * Resamples audio data from source format to target format.
     *
     * @param audioData the original audio data
     * @param sourceFormat the source audio format
     * @param targetFormat the target audio format
     * @return resampled audio data (returns original data if formats are identical)
     */
    byte[] resample(byte[] audioData, AudioFormat sourceFormat, AudioFormat targetFormat);

    /**
     * Gets the singleton linear interpolation resampler instance.
     *
     * @return the linear resampler
     */
    static AudioResampler linear() {
        return LinearAudioResampler.INSTANCE;
    }

    /**
     * Checks if resampling is needed between two formats.
     *
     * @param sourceFormat the source format
     * @param targetFormat the target format
     * @return true if resampling is needed
     */
    static boolean needsResampling(AudioFormat sourceFormat, AudioFormat targetFormat) {
        return sourceFormat.getSampleRate() != targetFormat.getSampleRate()
                || sourceFormat.getBitDepth() != targetFormat.getBitDepth();
    }
}
