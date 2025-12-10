/*
 * Copyright 1999-2025 Alibaba Group Holding Ltd.
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

package io.agentscope.core.a2a.agent.message;

import io.a2a.spec.DataPart;
import io.a2a.spec.FilePart;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.agentscope.core.message.ContentBlock;
import reactor.util.annotation.Nullable;

/**
 * The router for {@link PartParser} according to the {@link Part#getKind()}.
 *
 * @author xiweng.yy
 */
public class PartParserRouter {

    /**
     * Parse {@link Part} to {@link ContentBlock}.
     *
     * @param part the part to parse
     * @return the parsed content block, or null if the part is null or not supported
     */
    @Nullable
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
