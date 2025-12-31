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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DashScope content part DTO for multimodal messages.
 *
 * <p>Each content part can contain one type of content:
 * text, image, audio, or video.
 *
 * <p>Example text part:
 * <pre>{@code
 * {"text": "What's in this image?"}
 * }</pre>
 *
 * <p>Example image part (URL):
 * <pre>{@code
 * {"image": "https://example.com/image.jpg"}
 * }</pre>
 *
 * <p>Example image part (base64):
 * <pre>{@code
 * {"image": "data:image/png;base64,iVBORw0KGgo..."}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeContentPart {

    /** Text content. */
    @JsonProperty("text")
    private String text;

    /** Image URL or base64 data URI. */
    @JsonProperty("image")
    private String image;

    /** Audio URL or base64 data URI. */
    @JsonProperty("audio")
    private String audio;

    /** Video URL or frame list. */
    @JsonProperty("video")
    private Object video;

    public DashScopeContentPart() {}

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getAudio() {
        return audio;
    }

    public void setAudio(String audio) {
        this.audio = audio;
    }

    public Object getVideo() {
        return video;
    }

    public void setVideo(Object video) {
        this.video = video;
    }

    /**
     * Get video as URL string.
     *
     * @return the video URL, or null if not a string
     */
    public String getVideoAsString() {
        if (video instanceof String) {
            return (String) video;
        }
        return null;
    }

    /**
     * Get video as frame list.
     *
     * @return the video frames, or null if not a list
     */
    @SuppressWarnings("unchecked")
    public List<String> getVideoAsList() {
        if (video instanceof List) {
            return (List<String>) video;
        }
        return null;
    }

    /**
     * Create a text content part.
     *
     * @param text the text content
     * @return a new DashScopeContentPart
     */
    public static DashScopeContentPart text(String text) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setText(text);
        return part;
    }

    /**
     * Create an image content part.
     *
     * @param imageUrl the image URL or base64 data URI
     * @return a new DashScopeContentPart
     */
    public static DashScopeContentPart image(String imageUrl) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setImage(imageUrl);
        return part;
    }

    /**
     * Create an audio content part.
     *
     * @param audioUrl the audio URL or base64 data URI
     * @return a new DashScopeContentPart
     */
    public static DashScopeContentPart audio(String audioUrl) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setAudio(audioUrl);
        return part;
    }

    /**
     * Create a video content part from URL.
     *
     * @param videoUrl the video URL
     * @return a new DashScopeContentPart
     */
    public static DashScopeContentPart video(String videoUrl) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setVideo(videoUrl);
        return part;
    }

    /**
     * Create a video content part from frame list.
     *
     * @param frames the list of frame URLs
     * @return a new DashScopeContentPart
     */
    public static DashScopeContentPart videoFrames(List<String> frames) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setVideo(frames);
        return part;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeContentPart part = new DashScopeContentPart();

        public Builder text(String text) {
            part.setText(text);
            return this;
        }

        public Builder image(String image) {
            part.setImage(image);
            return this;
        }

        public Builder audio(String audio) {
            part.setAudio(audio);
            return this;
        }

        public Builder video(Object video) {
            part.setVideo(video);
            return this;
        }

        public DashScopeContentPart build() {
            return part;
        }
    }
}
