/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.agent.message;

import io.a2a.spec.FileContent;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.Part;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base parser for media content blocks to {@link FilePart}.
 *
 * <p>Provides common functionality for parsing {@link Source} to {@link FileContent} with type:
 * <ul>
 *     <li>Convert {@link Base64Source} to {@link FileWithBytes}</li>
 *     <li>Convert {@link URLSource} to {@link FileWithUri}</li>
 * </ul>
 *
 * <p> Due to all {@link FileContent} should include {@link FileContent#mimeType()},
 * but {@link Source} only {@link Base64Source} has {@link Base64Source#getMediaType()},
 * So For {@link URLSource} we need to parse {@link URLSource#getUrl()} to get {@link FileContent#mimeType()}.
 *
 * <p> {@link Source} doesn't define `name` field, so we need to generate a random
 * name set to {@link FileContent#name()}. Current use {@link UUID} to generate a random name.
 *
 * @param <T> the type of content block to parse
 */
public abstract class BaseMediaBlockParser<T extends ContentBlock>
        implements ContentBlockParser<T> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Get the media type for the content block.
     *
     * @return the media type string
     */
    protected abstract String getMediaType();

    /**
     * Parse the source from content block to a FilePart.
     *
     * <p>If input source is unknown type, return null.
     *
     * @param source the source to parse
     * @return the parsed FilePart
     */
    protected Part<?> parseSource(Source source) {
        FileContent file;
        if (source instanceof Base64Source base64Source) {
            file = parseFromBase64Source(base64Source);
        } else if (source instanceof URLSource urlSource) {
            file = parseFromUrlSource(urlSource);
        } else {
            LoggerUtil.warn(log, "Unsupported source type: {}", source.getClass().getName());
            return null;
        }
        return new FilePart(file, new HashMap<>());
    }

    private FileContent parseFromBase64Source(Base64Source source) {
        return new FileWithBytes(source.getMediaType(), generateRandomFileName(), source.getData());
    }

    private FileContent parseFromUrlSource(URLSource urlSource) {
        String url = urlSource.getUrl();
        return new FileWithUri(tryToParseMimeTypeFromUrl(url), generateRandomFileName(), url);
    }

    private String tryToParseMimeTypeFromUrl(String url) {
        try {
            URL javaUrl = new URL(url);
            String mimeType = Files.probeContentType(Paths.get(javaUrl.getPath()));
            if (Objects.isNull(mimeType)) {
                return getMediaType();
            }
            return mimeType.startsWith(getMediaType()) ? mimeType : getMediaType();
        } catch (Exception ignored) {
            return getMediaType();
        }
    }

    private String generateRandomFileName() {
        return UUID.randomUUID().toString();
    }
}
