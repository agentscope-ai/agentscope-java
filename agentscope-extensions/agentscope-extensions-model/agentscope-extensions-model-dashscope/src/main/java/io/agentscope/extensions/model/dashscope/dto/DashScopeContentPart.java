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
package io.agentscope.extensions.model.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * DashScope content part DTO for multimodal messages.
 *
 * <p>Each content part can contain one type of content:
 * text, document, image, audio, or video.
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
 * <p>Example document part (qwen-doc-turbo):
 * <pre>{@code
 * {"type": "doc_url", "doc_url": ["https://example.com/report.pdf"], "file_parsing_strategy": "auto"}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeContentPart {

    /** Part type value for document URL parts. */
    public static final String TYPE_DOC_URL = "doc_url";

    /** Part type value for plain text parts. */
    public static final String TYPE_TEXT = "text";

    /** Part type discriminator, for example {@code "doc_url"}. */
    @JsonProperty("type")
    private String type;

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

    /** Frames per second. The value range is [0.1, 10], and the default value is 2.0. */
    @JsonProperty("fps")
    private Float fps;

    /** The maximum number of frames captured in the video. */
    @JsonProperty("max_frames")
    private Integer maxFrames;

    /** Used to set the minimum pixel threshold for input image or video frames. */
    @JsonProperty("min_pixels")
    private Integer minPixels;

    /** Used to set the maximum pixel threshold for input image or video frames. */
    @JsonProperty("max_pixels")
    private Integer maxPixels;

    /** Used to limit the total pixels of all frames extracted from the video (single image pixels × total frames). */
    @JsonProperty("total_pixels")
    private Integer totalPixels;

    /**
     * Document URLs for document-aware models such as {@code qwen-doc-turbo}. Up to 10 URLs are
     * accepted per request and each must be publicly reachable.
     */
    @JsonProperty("doc_url")
    private List<String> docUrl;

    /**
     * How the documents of a {@code doc_url} part are parsed: {@code auto}, {@code text_only} or
     * {@code text_and_images}.
     */
    @JsonProperty("file_parsing_strategy")
    private String fileParsingStrategy;

    /** Cache control configuration for this content part. */
    @JsonProperty("cache_control")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> cacheControl;

    public DashScopeContentPart() {}

    /**
     * Get the part type discriminator.
     *
     * @return the part type, or null when the part is not typed
     */
    public String getType() {
        return type;
    }

    /**
     * Set the part type discriminator.
     *
     * @param type the part type (e.g. "doc_url")
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Get the document URLs carried by this part.
     *
     * @return the document URLs, or null when this is not a doc_url part
     */
    public List<String> getDocUrl() {
        return docUrl;
    }

    /**
     * Set the document URLs carried by this part.
     *
     * @param docUrl the document URLs
     */
    public void setDocUrl(List<String> docUrl) {
        this.docUrl = docUrl;
    }

    /**
     * Get the parsing strategy for the documents of this part.
     *
     * @return the parsing strategy, or null when not configured
     */
    public String getFileParsingStrategy() {
        return fileParsingStrategy;
    }

    /**
     * Set the parsing strategy for the documents of this part.
     *
     * @param fileParsingStrategy one of "auto", "text_only" or "text_and_images"
     */
    public void setFileParsingStrategy(String fileParsingStrategy) {
        this.fileParsingStrategy = fileParsingStrategy;
    }

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

    public Float getFps() {
        return fps;
    }

    public void setFps(Float fps) {
        this.fps = fps;
    }

    public Integer getMaxFrames() {
        return maxFrames;
    }

    public void setMaxFrames(Integer maxFrames) {
        this.maxFrames = maxFrames;
    }

    public Integer getMinPixels() {
        return minPixels;
    }

    public void setMinPixels(Integer minPixels) {
        this.minPixels = minPixels;
    }

    public Integer getMaxPixels() {
        return maxPixels;
    }

    public void setMaxPixels(Integer maxPixels) {
        this.maxPixels = maxPixels;
    }

    public Integer getTotalPixels() {
        return totalPixels;
    }

    public void setTotalPixels(Integer totalPixels) {
        this.totalPixels = totalPixels;
    }

    /**
     * Get the cache control configuration.
     *
     * @return the cache control configuration, or null when not configured
     */
    public Map<String, String> getCacheControl() {
        return cacheControl;
    }

    /**
     * Set the cache control configuration.
     *
     * @param cacheControl the cache control configuration
     */
    public void setCacheControl(Map<String, String> cacheControl) {
        this.cacheControl = cacheControl;
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

    /**
     * Create a document content part with a parsing strategy.
     *
     * @param urls the publicly reachable document URLs (up to 10 per request)
     * @param fileParsingStrategy one of "auto", "text_only" or "text_and_images"; may be null
     * @return a new DashScopeContentPart typed as "doc_url"
     */
    public static DashScopeContentPart docUrl(List<String> urls, String fileParsingStrategy) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setType(TYPE_DOC_URL);
        part.setDocUrl(urls);
        part.setFileParsingStrategy(fileParsingStrategy);
        return part;
    }

    /**
     * Create a document content part without an explicit parsing strategy.
     *
     * @param urls the publicly reachable document URLs (up to 10 per request)
     * @return a new DashScopeContentPart typed as "doc_url"
     */
    public static DashScopeContentPart docUrl(List<String> urls) {
        return docUrl(urls, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeContentPart part = new DashScopeContentPart();

        public Builder type(String type) {
            part.setType(type);
            return this;
        }

        /**
         * Set the document URLs of this part and mark it as a "doc_url" part.
         *
         * @param docUrl the publicly reachable document URLs
         * @return this builder
         */
        public Builder docUrl(List<String> docUrl) {
            part.setType(TYPE_DOC_URL);
            part.setDocUrl(docUrl);
            return this;
        }

        /**
         * Set the parsing strategy applied to the documents of this part.
         *
         * @param fileParsingStrategy one of "auto", "text_only" or "text_and_images"
         * @return this builder
         */
        public Builder fileParsingStrategy(String fileParsingStrategy) {
            part.setFileParsingStrategy(fileParsingStrategy);
            return this;
        }

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

        public Builder fps(Float fps) {
            part.setFps(fps);
            return this;
        }

        public Builder maxFrames(Integer maxFrames) {
            part.setMaxFrames(maxFrames);
            return this;
        }

        public Builder minPixels(Integer minPixels) {
            part.setMinPixels(minPixels);
            return this;
        }

        public Builder maxPixels(Integer maxPixels) {
            part.setMaxPixels(maxPixels);
            return this;
        }

        public Builder totalPixels(Integer totalPixels) {
            part.setTotalPixels(totalPixels);
            return this;
        }

        /**
         * Set the cache control configuration.
         *
         * @param cacheControl the cache control configuration
         * @return this builder
         */
        public Builder cacheControl(Map<String, String> cacheControl) {
            part.setCacheControl(cacheControl);
            return this;
        }

        public DashScopeContentPart build() {
            return part;
        }
    }
}
