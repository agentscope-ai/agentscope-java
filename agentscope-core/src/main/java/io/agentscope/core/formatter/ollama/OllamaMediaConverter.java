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
package io.agentscope.core.formatter.ollama;

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles media content conversion for Ollama API.
 * Converts ImageBlock to Base64 strings as required by Ollama.
 *
 */
public class OllamaMediaConverter {

    private static final Logger log = LoggerFactory.getLogger(OllamaMediaConverter.class);

    /**
     * Convert ImageBlock to Base64 string for Ollama API.
     *
     * <p>Ollama API expects an array of base64-encoded strings for images.
     * This method handles:
     * <ul>
     *   <li>Base64 sources → Returns raw base64 data</li>
     *   <li>Local file URLs → Reads file and converts to base64</li>
     *   <li>Remote URLs → Downloads content and converts to base64</li>
     * </ul>
     *
     * @param imageBlock The image block to convert
     * @return Base64 encoded string of the image
     * @throws Exception If conversion, file reading, or download fails
     */
    public String convertImageBlockToBase64(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof Base64Source base64Source) {
            // Ollama expects raw base64 string without data URI prefix
            return base64Source.getData();
        } else if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                // Read local file to base64
                // Remove file:// prefix if present for local path
                String path = url.startsWith("file://") ? url.substring(7) : url;
                return MediaUtils.fileToBase64(path);
            } else {
                // Download remote URL to base64
                return MediaUtils.downloadUrlToBase64(url);
            }
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }
}
