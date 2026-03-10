/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.tts;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio player for playing PCM audio data in real-time.
 *
 * <p>This player uses Java Sound API (javax.sound.sampled) to play
 * audio data. It supports both synchronous and asynchronous playback.
 *
 * <p>Example usage:
 * <pre>{@code
 * AudioPlayer player = AudioPlayer.builder()
 *     .sampleRate(24000)
 *     .build();
 *
 * player.start();
 *
 * // Play audio chunks as they arrive
 * ttsModel.push("hello").subscribe(player::play);
 *
 * player.stop();
 * }</pre>
 */
public class AudioPlayer {

    private static final Logger log = LoggerFactory.getLogger(AudioPlayer.class);

    private final int sampleRate;
    private final int sampleSizeInBits;
    private final int channels;
    private final boolean signed;
    private final boolean bigEndian;

    private SourceDataLine line;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>();
    private Thread playbackThread;

    private AudioPlayer(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.sampleSizeInBits = builder.sampleSizeInBits;
        this.channels = builder.channels;
        this.signed = builder.signed;
        this.bigEndian = builder.bigEndian;
    }

    /**
     * Starts the audio player and opens the audio line.
     *
     * @throws TTSException if audio line cannot be opened
     */
    public void start() {
        if (running.get()) {
            return;
        }

        try {
            AudioFormat format =
                    new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                throw new TTSException("Audio line not supported: " + format);
            }

            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            running.set(true);

            // Start background playback thread
            playbackThread = new Thread(this::playbackLoop, "audio-player");
            playbackThread.setDaemon(true);
            playbackThread.start();

            log.debug("AudioPlayer started with format: {}", format);
        } catch (LineUnavailableException e) {
            throw new TTSException("Failed to open audio line", e);
        }
    }

    /**
     * Plays audio data synchronously.
     *
     * @param audioData PCM audio data
     */
    public void playSync(byte[] audioData) {
        if (!running.get()) {
            start();
        }
        if (line != null && audioData != null && audioData.length > 0) {
            line.write(audioData, 0, audioData.length);
        }
    }

    /**
     * Queues audio data for asynchronous playback.
     *
     * @param audioData PCM audio data
     */
    public void play(byte[] audioData) {
        if (!running.get()) {
            start();
        }
        if (audioData != null && audioData.length > 0) {
            boolean enqueued = audioQueue.offer(audioData);
            if (!enqueued) {
                log.warn(
                        "Failed to enqueue audio data for asynchronous playback; audio data may be"
                                + " dropped.");
            }
        }
    }

    /**
     * Plays an AudioBlock.
     *
     * @param audioBlock the audio block to play
     */
    public void play(AudioBlock audioBlock) {
        if (audioBlock == null || audioBlock.getSource() == null) {
            return;
        }

        if (audioBlock.getSource() instanceof Base64Source) {
            Base64Source source = (Base64Source) audioBlock.getSource();
            if (source.getData() != null && !source.getData().isEmpty()) {
                byte[] audioData = Base64.getDecoder().decode(source.getData());
                play(audioData);
            }
        }
    }

    /**
     * Interrupts current playback and clears the queue, but keeps the audio line open.
     *
     * <p>This is useful when you want to immediately stop current playback and start
     * playing new audio without closing and reopening the audio line. Unlike {@link #stop()},
     * this method keeps the audio line open so new audio can be played immediately.
     */
    public void interrupt() {
        // Clear the queue immediately
        audioQueue.clear();

        // Stop and flush the current line
        if (line != null) {
            line.stop();
            line.flush();
            line.start(); // Restart to accept new audio
        }

        log.debug("AudioPlayer interrupted (queue cleared, line kept open)");
    }

    /**
     * Stops the audio player and closes the audio line.
     */
    public void stop() {
        running.set(false);

        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (line != null) {
            line.drain();
            line.stop();
            line.close();
            line = null;
        }

        audioQueue.clear();
        log.debug("AudioPlayer stopped");
    }

    /**
     * Waits for all queued audio to finish playing.
     */
    public void drain() {
        // Wait for queue to empty
        while (!audioQueue.isEmpty() && running.get()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Drain the audio line
        if (line != null) {
            line.drain();
        }
    }

    private void playbackLoop() {
        while (running.get()) {
            try {
                byte[] audioData = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (audioData != null && line != null) {
                    line.write(audioData, 0, audioData.length);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Creates a new builder for AudioPlayer.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing AudioPlayer instances.
     */
    public static class Builder {
        private int sampleRate = 24000;
        private int sampleSizeInBits = 16;
        private int channels = 1;
        private boolean signed = true;
        private boolean bigEndian = false;

        /**
         * Sets the sample rate in Hz.
         *
         * @param sampleRate sample rate (e.g., 16000, 24000, 48000)
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the sample size in bits.
         *
         * @param sampleSizeInBits bits per sample (e.g., 8, 16)
         * @return this builder
         */
        public Builder sampleSizeInBits(int sampleSizeInBits) {
            this.sampleSizeInBits = sampleSizeInBits;
            return this;
        }

        /**
         * Sets the number of audio channels.
         *
         * @param channels 1 for mono, 2 for stereo
         * @return this builder
         */
        public Builder channels(int channels) {
            this.channels = channels;
            return this;
        }

        /**
         * Sets whether the audio data is signed.
         *
         * @param signed true for signed, false for unsigned
         * @return this builder
         */
        public Builder signed(boolean signed) {
            this.signed = signed;
            return this;
        }

        /**
         * Sets the byte order of the audio data.
         *
         * @param bigEndian true for big-endian, false for little-endian
         * @return this builder
         */
        public Builder bigEndian(boolean bigEndian) {
            this.bigEndian = bigEndian;
            return this;
        }

        /**
         * Builds the AudioPlayer instance.
         *
         * @return a configured AudioPlayer
         */
        public AudioPlayer build() {
            return new AudioPlayer(this);
        }
    }
}
