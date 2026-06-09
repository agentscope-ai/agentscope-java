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

import io.a2a.spec.DataPart;
import io.a2a.spec.FilePart;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.agentscope.core.message.ContentBlock;

/**
 * The router for {@link PartParser} according to the {@link Part#getKind()}.
 */
public class PartParserRouter {

    /**
     * Parse {@link Part} to {@link ContentBlock}.
     *
     * @param part the part to parse
     * @return the parsed content block, or null if the part is null or not supported
     */
    public ContentBlock parse(Part<?> part) {
        if (null == part) {
            return null;
        }
        return switch (part.getKind()) {
            case TEXT -> new TextPartParser().parse((TextPart) part);
            case FILE -> new FilePartParser().parse((FilePart) part);
            case DATA -> new DataPartParser().parse((DataPart) part);
        };
    }
}
