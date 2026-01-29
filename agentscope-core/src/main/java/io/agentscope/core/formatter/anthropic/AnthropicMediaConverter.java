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

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;

/**
 * Handles media content conversion for Anthropic API.
 */
public class AnthropicMediaConverter {

    /**
     * Convert ImageBlock to Anthropic ImageSource. For local files, converts to
     * base64. For remote
     * URLs, also converts to base64 (Anthropic API doesn't support URL sources in
     * the same way).
     */
    public AnthropicContent.ImageSource convertImageBlock(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            // Convert to base64 (both local and remote)
            String base64Data =
                    MediaUtils.isLocalFile(url)
                            ? MediaUtils.fileToBase64(url)
                            : MediaUtils.downloadUrlToBase64(url);
            String mediaType = MediaUtils.determineMediaType(url);

            return new AnthropicContent.ImageSource(
                    mediaType != null ? mediaType : "image/png", base64Data);
        } else if (source instanceof Base64Source base64Source) {
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();

            return new AnthropicContent.ImageSource(
                    mediaType != null ? mediaType : "image/png", base64Data);
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }
}
