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
package io.agentscope.core.formatter.openai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI content part DTO for multimodal messages.
 *
 * <p>Each content part has a type and corresponding data:
 * - "text" type with text field
 * - "image_url" type with image_url field
 * - "video_url" type with video_url field
 * - "input_audio" type with input_audio field
 *
 * <p>Example text part:
 * <pre>{@code
 * {"type": "text", "text": "What's in this image?"}
 * }</pre>
 *
 * <p>Example image part:
 * <pre>{@code
 * {"type": "image_url", "image_url": {"url": "https://example.com/image.jpg"}}
 * }</pre>
 *
 * <p>Example video part:
 * <pre>{@code
 * {"type": "video_url", "videoUrl": {"url": "https://example.com/video.mp4"}}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIContentPart {

    /** Content type: "text", "image_url", "video_url", or "input_audio". */
    @JsonProperty("type")
    private String type;

    /** Text content (for type="text"). */
    @JsonProperty("text")
    private String text;

    /** Image URL object (for type="image_url"). */
    @JsonProperty("image_url")
    private OpenAIImageUrl imageUrl;

    /** Audio input object (for type="input_audio"). */
    @JsonProperty("input_audio")
    private OpenAIInputAudio inputAudio;

    /** Video URL object (for type="video_url"). */
    @JsonProperty("video_url")
    private OpenAIVideoUrl videoUrl;

    public OpenAIContentPart() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public OpenAIImageUrl getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(OpenAIImageUrl imageUrl) {
        this.imageUrl = imageUrl;
    }

    public OpenAIInputAudio getInputAudio() {
        return inputAudio;
    }

    public void setInputAudio(OpenAIInputAudio inputAudio) {
        this.inputAudio = inputAudio;
    }

    public OpenAIVideoUrl getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(OpenAIVideoUrl videoUrl) {
        this.videoUrl = videoUrl;
    }

    /**
     * Create a text content part.
     *
     * @param text the text content
     * @return a new OpenAIContentPart
     */
    public static OpenAIContentPart text(String text) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("text");
        part.setText(text);
        return part;
    }

    /**
     * Create an image content part.
     *
     * @param url the image URL or base64 data URI
     * @return a new OpenAIContentPart
     */
    public static OpenAIContentPart imageUrl(String url) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("image_url");
        part.setImageUrl(new OpenAIImageUrl(url));
        return part;
    }

    /**
     * Create an image content part with detail level.
     *
     * @param url the image URL or base64 data URI
     * @param detail the detail level ("low", "high", or "auto")
     * @return a new OpenAIContentPart
     */
    public static OpenAIContentPart imageUrl(String url, String detail) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("image_url");
        part.setImageUrl(new OpenAIImageUrl(url, detail));
        return part;
    }

    /**
     * Create an audio content part.
     *
     * @param data the base64-encoded audio data
     * @param format the audio format (e.g., "wav", "mp3")
     * @return a new OpenAIContentPart
     */
    public static OpenAIContentPart inputAudio(String data, String format) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("input_audio");
        part.setInputAudio(new OpenAIInputAudio(data, format));
        return part;
    }

    /**
     * Create a video content part.
     *
     * @param url the video URL or base64 data URI
     * @return a new OpenAIContentPart
     */
    public static OpenAIContentPart videoUrl(String url) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("video_url");
        part.setVideoUrl(new OpenAIVideoUrl(url));
        return part;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OpenAIContentPart part = new OpenAIContentPart();

        public Builder type(String type) {
            part.setType(type);
            return this;
        }

        public Builder text(String text) {
            part.setText(text);
            return this;
        }

        public Builder imageUrl(OpenAIImageUrl imageUrl) {
            part.setImageUrl(imageUrl);
            return this;
        }

        public Builder inputAudio(OpenAIInputAudio inputAudio) {
            part.setInputAudio(inputAudio);
            return this;
        }

        public Builder videoUrl(OpenAIVideoUrl videoUrl) {
            part.setVideoUrl(videoUrl);
            return this;
        }

        public OpenAIContentPart build() {
            OpenAIContentPart result = part;
            part = new OpenAIContentPart();
            return result;
        }
    }
}
