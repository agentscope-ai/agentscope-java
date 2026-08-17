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

import io.a2a.spec.Part;
import io.agentscope.core.message.VideoBlock;

/**
 * Parser for {@link VideoBlock} to {@link io.a2a.spec.FilePart}.
 */
public class VideoBlockParser extends BaseMediaBlockParser<VideoBlock> {

    @Override
    protected String getMediaType() {
        return MessageConstants.BlockContent.TYPE_VIDEO;
    }

    @Override
    public Part<?> parse(VideoBlock contentBlock) {
        return parseSource(contentBlock.getSource());
    }
}
