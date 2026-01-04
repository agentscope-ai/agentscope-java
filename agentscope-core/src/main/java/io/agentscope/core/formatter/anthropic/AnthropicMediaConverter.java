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
package io.agentscope.core.formatter.anthropic;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;

/**
 * Handles media content conversion for Anthropic API.
 */
public class AnthropicMediaConverter {

    /**
     * Convert ImageBlock to Anthropic ImageBlockParam. For local files, converts to base64. For
     * remote URLs, uses URL source directly.
     */
    public ImageBlockParam convertImageBlock(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                // Convert local file to base64
                String base64Data = MediaUtils.fileToBase64(url);
                String mediaType = MediaUtils.determineMediaType(url);

                return ImageBlockParam.builder()
                        .source(
                                Base64ImageSource.builder()
                                        .data(base64Data)
                                        .mediaType(
                                                Base64ImageSource.MediaType.of(
                                                        mediaType != null
                                                                ? mediaType
                                                                : "image/png"))
                                        .build())
                        .build();
            } else {
                // Use remote URL directly
                return ImageBlockParam.builder()
                        .source(UrlImageSource.builder().url(url).build())
                        .build();
            }
        } else if (source instanceof Base64Source base64Source) {
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();

            return ImageBlockParam.builder()
                    .source(
                            Base64ImageSource.builder()
                                    .data(base64Data)
                                    .mediaType(
                                            Base64ImageSource.MediaType.of(
                                                    mediaType != null ? mediaType : "image/png"))
                                    .build())
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }
}
