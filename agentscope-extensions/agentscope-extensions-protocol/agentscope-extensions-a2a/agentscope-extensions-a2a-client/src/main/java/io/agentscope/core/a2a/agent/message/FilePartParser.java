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
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;

/**
 * Parser for {@link FilePart} to {@link ContentBlock}.
 *
 * <ul>
 *     <li>{@link FilePart} with mimeType is `image/*` parse to {@link ImageBlock}</li>
 *     <li>{@link FilePart} with mimeType is `audio/*` parse to {@link AudioBlock}</li>
 *     <li>{@link FilePart} with mimeType is `video/*` parse to {@link VideoBlock}</li>
 * </ul>
 */
public class FilePartParser implements PartParser<FilePart> {

    @Override
    public ContentBlock parse(FilePart part) {
        FileContent file = part.getFile();
        return switch (getPrimaryType(file.mimeType())) {
            case MessageConstants.BlockContent.TYPE_IMAGE -> parseToImageBlock(file);
            case MessageConstants.BlockContent.TYPE_AUDIO -> parseToAudioBlock(file);
            case MessageConstants.BlockContent.TYPE_VIDEO -> parseToVideoBlock(file);
            default -> null;
        };
    }

    private ImageBlock parseToImageBlock(FileContent file) {
        ImageBlock.Builder builder = ImageBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private AudioBlock parseToAudioBlock(FileContent file) {
        AudioBlock.Builder builder = AudioBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private VideoBlock parseToVideoBlock(FileContent file) {
        VideoBlock.Builder builder = VideoBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private Source buildSource(FileContent file) {
        if (file instanceof FileWithBytes fileWithBytes) {
            return Base64Source.builder()
                    .mediaType(file.mimeType())
                    .data(fileWithBytes.bytes())
                    .build();
        } else if (file instanceof FileWithUri fileWithUri) {
            return URLSource.builder().url(fileWithUri.uri()).build();
        } else {
            return null;
        }
    }

    private String getPrimaryType(String mimeType) {
        // If no mimeType, return empty string.
        if (mimeType == null || mimeType.isEmpty()) {
            return "";
        }
        int slashIndex = mimeType.indexOf('/');
        if (slashIndex == -1) {
            // If no '/', means this type is a primary type or wrong type.
            return mimeType;
        }
        // Extract primary type
        return mimeType.substring(0, slashIndex);
    }
}
