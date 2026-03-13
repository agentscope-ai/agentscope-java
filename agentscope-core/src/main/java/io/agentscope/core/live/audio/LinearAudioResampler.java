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
 * Linear interpolation audio resampler.
 *
 * <p>Uses linear interpolation algorithm for sample rate conversion. Simple and efficient,
 * suitable for real-time scenarios.
 *
 * <p>This is a singleton class. Use {@link AudioResampler#linear()} to get the instance.
 */
public final class LinearAudioResampler implements AudioResampler {

    /** Singleton instance. */
    static final LinearAudioResampler INSTANCE = new LinearAudioResampler();

    private LinearAudioResampler() {}

    @Override
    public byte[] resample(byte[] audioData, AudioFormat sourceFormat, AudioFormat targetFormat) {
        int sourceSampleRate = sourceFormat.getSampleRate();
        int targetSampleRate = targetFormat.getSampleRate();

        // Return original data if no resampling needed
        if (sourceSampleRate == targetSampleRate
                && sourceFormat.getBitDepth() == targetFormat.getBitDepth()) {
            return audioData;
        }

        int bytesPerSample = sourceFormat.getBitDepth() / 8;
        int sourceSamples = audioData.length / bytesPerSample;
        int targetSamples = (int) ((long) sourceSamples * targetSampleRate / sourceSampleRate);
        byte[] result = new byte[targetSamples * bytesPerSample];

        double ratio = (double) sourceSampleRate / targetSampleRate;

        for (int i = 0; i < targetSamples; i++) {
            double srcIndex = i * ratio;
            int srcIndexInt = (int) srcIndex;
            double frac = srcIndex - srcIndexInt;

            short sample1 = readSample16(audioData, srcIndexInt);
            short sample2 = readSample16(audioData, Math.min(srcIndexInt + 1, sourceSamples - 1));

            short interpolated = (short) (sample1 + frac * (sample2 - sample1));

            writeSample16(result, i, interpolated);
        }

        return result;
    }

    private short readSample16(byte[] data, int index) {
        int offset = index * 2;
        if (offset + 1 >= data.length) {
            return 0;
        }
        return (short) ((data[offset] & 0xFF) | (data[offset + 1] << 8));
    }

    private void writeSample16(byte[] data, int index, short sample) {
        int offset = index * 2;
        data[offset] = (byte) (sample & 0xFF);
        data[offset + 1] = (byte) ((sample >> 8) & 0xFF);
    }
}
