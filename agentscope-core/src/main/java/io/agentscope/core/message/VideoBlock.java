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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents video content in a message.
 *
 * <p>This content block supports video from two sources:
 * <ul>
 *   <li>URL source - video files accessible via HTTP/HTTPS URLs or local file URLs</li>
 *   <li>Base64 source - video encoded as Base64 strings with MIME type</li>
 * </ul>
 *
 * <p>Video blocks enable advanced multimodal AI interactions where agents need to process
 * or analyze video content such as presentations, tutorials, surveillance footage,
 * or other visual media that includes motion and temporal elements.
 */
public final class VideoBlock extends ContentBlock {

    private final Source source;

    private final Float fps;

    private final Integer maxFrames;

    private final Integer minPixels;

    private final Integer maxPixels;

    private final Integer totalPixels;

    /**
     * Creates a new video block with source.
     *
     * @param source The video source (URL or Base64)
     */
    public VideoBlock(@JsonProperty("source") Source source) {
        this(source, null, null, null, null, null);
    }

    /**
     * Creates a new video block for JSON deserialization.
     *
     * @param source The video source (URL or Base64)
     * @param fps The frames per second. The value range is [0.1, 10], and the default value is 2.0.
     * @param maxFrames The maximum number of frames captured in the video.
     * @param minPixels Used to set the minimum pixel threshold for input video frames.
     * @param maxPixels Used to set the maximum pixel threshold for input video frames.
     * @param totalPixels Used to limit the total pixels of all frames extracted from the video (single image pixels × total frames).
     */
    @JsonCreator
    private VideoBlock(
            @JsonProperty("source") Source source,
            @JsonProperty("fps") Float fps,
            @JsonProperty("max_frames") Integer maxFrames,
            @JsonProperty("min_pixels") Integer minPixels,
            @JsonProperty("max_pixels") Integer maxPixels,
            @JsonProperty("total_pixels") Integer totalPixels) {
        this.source = source;
        this.fps = fps;
        this.maxFrames = maxFrames;
        this.minPixels = minPixels;
        this.maxPixels = maxPixels;
        this.totalPixels = totalPixels;
    }

    /**
     * Gets the source of this video content.
     *
     * @return The video source containing URL or Base64 data
     */
    public Source getSource() {
        return source;
    }

    /**
     * Gets the frames per second (FPS) of the video.
     *
     * @return The frames per second value
     */
    public Float getFps() {
        return fps;
    }

    /**
     * Gets the maximum number of frames to capture from the video.
     *
     * @return The maximum number of frames
     */
    public Integer getMaxFrames() {
        return maxFrames;
    }

    /**
     * Gets the minimum pixel threshold for input video frames.
     *
     * @return The minimum pixel threshold
     */
    public Integer getMinPixels() {
        return minPixels;
    }

    /**
     * Gets the maximum pixel threshold for input video frames.
     *
     * @return The maximum pixel threshold
     */
    public Integer getMaxPixels() {
        return maxPixels;
    }

    /**
     * Gets the total pixels of all frames extracted from the video (single image pixels × total frames).
     * @return The total pixels
     */
    public Integer getTotalPixels() {
        return totalPixels;
    }

    /**
     * Creates a new builder for constructing VideoBlock instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing VideoBlock instances.
     */
    public static class Builder {

        private Source source;

        private Float fps;

        private Integer maxFrames;

        private Integer minPixels;

        private Integer maxPixels;

        private Integer totalPixels;

        /**
         * Sets the source for the video content.
         *
         * @param source The video source (URL or Base64)
         * @return This builder for chaining
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * Sets the frames per second (FPS) of the video.
         *
         * @param fps The frames per second value
         * @return This builder for chaining
         */
        public Builder fps(Float fps) {
            this.fps = fps;
            return this;
        }

        /**
         * Sets the maximum number of frames to capture from the video.
         *
         * @param maxFrames The maximum number of frames
         * @return This builder for chaining
         */
        public Builder maxFrames(Integer maxFrames) {
            this.maxFrames = maxFrames;
            return this;
        }

        /**
         * Sets the minimum pixel threshold for input video frames.
         *
         * @param minPixels The minimum pixel threshold
         * @return This builder for chaining
         */
        public Builder minPixels(Integer minPixels) {
            this.minPixels = minPixels;
            return this;
        }

        /**
         * Sets the maximum pixel threshold for input video frames.
         *
         * @param maxPixels The maximum pixel threshold
         * @return This builder for chaining
         */
        public Builder maxPixels(Integer maxPixels) {
            this.maxPixels = maxPixels;
            return this;
        }

        /**
         * Sets the total pixels of all frames extracted from the video (single image pixels × total frames).
         *
         * @param totalPixels The total pixels
         * @return This builder for chaining
         */
        public Builder totalPixels(Integer totalPixels) {
            this.totalPixels = totalPixels;
            return this;
        }

        /**
         * Builds a new VideoBlock with the configured source.
         *
         * @return A new VideoBlock instance
         */
        public VideoBlock build() {
            return new VideoBlock(source, fps, maxFrames, minPixels, maxPixels, totalPixels);
        }
    }
}
