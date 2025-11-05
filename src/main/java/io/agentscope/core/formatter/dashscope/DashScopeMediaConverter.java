/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.dashscope;

import com.alibaba.dashscope.common.ImageURL;
import com.alibaba.dashscope.common.MessageContentImageURL;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles media content conversion for DashScope API.
 * Converts ImageBlock/VideoBlock to DashScope-compatible formats.
 *
 * <p>DashScope uses file:// protocol for local files, which differs from OpenAI's base64 approach.
 */
public class DashScopeMediaConverter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMediaConverter.class);

    /**
     * Convert ImageBlock to URL string for DashScope API.
     *
     * <p>Uses file:// protocol for local files for consistent behavior.
     *
     * <p>Handles:
     * <ul>
     *   <li>Local files → file:// protocol URL (e.g., file:///absolute/path/image.png)
     *   <li>Remote URLs → Direct URL (e.g., https://example.com/image.png)
     *   <li>Base64 sources → Data URL (e.g., data:image/png;base64,...)
     * </ul>
     *
     * @param imageBlock The image block to convert
     * @return URL string for DashScope API
     * @throws Exception If conversion fails
     */
    public String convertImageBlockToUrl(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                // Local file: use file:// protocol
                return MediaUtils.toFileProtocolUrl(url);
            } else {
                // Remote URL: use directly
                return url;
            }

        } else if (source instanceof Base64Source base64Source) {
            // Base64 source: construct data URL
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);

        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Convert ImageBlock to MessageContentImageURL for multimodal messages.
     *
     * @param imageBlock The image block to convert
     * @return MessageContentImageURL for DashScope API
     * @throws Exception If conversion fails
     */
    public MessageContentImageURL convertImageBlockToContentPart(ImageBlock imageBlock)
            throws Exception {
        String imageUrl = convertImageBlockToUrl(imageBlock);
        return MessageContentImageURL.builder()
                .imageURL(ImageURL.builder().url(imageUrl).build())
                .build();
    }

    /**
     * Convert VideoBlock to URL string for DashScope API.
     *
     * <p>Uses file:// protocol for local files for consistent behavior (same as ImageBlock
     * handling).
     *
     * <p>Handles:
     * <ul>
     *   <li>Local files → file:// protocol URL (e.g., file:///absolute/path/video.mp4)
     *   <li>Remote URLs → Direct URL (e.g., https://example.com/video.mp4)
     *   <li>Base64 sources → Data URL (e.g., data:video/mp4;base64,...)
     * </ul>
     *
     * @param videoBlock The video block to convert
     * @return URL string for DashScope API
     * @throws Exception If conversion fails
     */
    public String convertVideoBlockToUrl(VideoBlock videoBlock) throws Exception {
        Source source = videoBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateVideoExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                // Local file: use file:// protocol
                return MediaUtils.toFileProtocolUrl(url);
            } else {
                // Remote URL: use directly
                return url;
            }

        } else if (source instanceof Base64Source base64Source) {
            // Base64 source: construct data URL
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);

        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Convert ImageBlock to Map&lt;String, Object&gt; for MultiModalMessage content.
     *
     * @param imageBlock The image block to convert
     * @return Map with "image" key for MultiModalMessage content
     * @throws Exception If conversion fails
     */
    public Map<String, Object> convertImageBlockToMap(ImageBlock imageBlock) throws Exception {
        String imageUrl = convertImageBlockToUrl(imageBlock);
        Map<String, Object> imageMap = new HashMap<>();
        imageMap.put("image", imageUrl);
        return imageMap;
    }

    /**
     * Convert VideoBlock to Map&lt;String, Object&gt; for MultiModalMessage content.
     *
     * @param videoBlock The video block to convert
     * @return Map with "video" key for MultiModalMessage content
     * @throws Exception If conversion fails
     */
    public Map<String, Object> convertVideoBlockToMap(VideoBlock videoBlock) throws Exception {
        String videoUrl = convertVideoBlockToUrl(videoBlock);
        Map<String, Object> videoMap = new HashMap<>();
        videoMap.put("video", videoUrl);
        return videoMap;
    }

    /**
     * Convert AudioBlock to URL string for DashScope API.
     *
     * <p>Uses file:// protocol for local files for consistent behavior (same as ImageBlock
     * handling).
     *
     * <p>Handles:
     * <ul>
     *   <li>Local files → file:// protocol URL (e.g., file:///absolute/path/audio.mp3)
     *   <li>Remote URLs → Direct URL (e.g., https://example.com/audio.mp3)
     *   <li>Base64 sources → Data URL (e.g., data:audio/wav;base64,...)
     * </ul>
     *
     * @param audioBlock The audio block to convert
     * @return URL string for DashScope API
     * @throws Exception If conversion fails
     */
    public String convertAudioBlockToUrl(AudioBlock audioBlock) throws Exception {
        Source source = audioBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            // Note: DashScope may not support audio validation like images
            // MediaUtils.validateAudioExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                // Local file: use file:// protocol
                return MediaUtils.toFileProtocolUrl(url);
            } else {
                // Remote URL: use directly
                return url;
            }

        } else if (source instanceof Base64Source base64Source) {
            // Base64 source: construct data URL
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);

        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Convert AudioBlock to Map&lt;String, Object&gt; for MultiModalMessage content.
     *
     * @param audioBlock The audio block to convert
     * @return Map with "audio" key for MultiModalMessage content
     * @throws Exception If conversion fails
     */
    public Map<String, Object> convertAudioBlockToMap(AudioBlock audioBlock) throws Exception {
        String audioUrl = convertAudioBlockToUrl(audioBlock);
        Map<String, Object> audioMap = new HashMap<>();
        audioMap.put("audio", audioUrl);
        return audioMap;
    }
}
