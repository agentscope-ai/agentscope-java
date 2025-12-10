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

import io.a2a.spec.Part;
import io.agentscope.core.message.ContentBlock;

/**
 * {@link Part} parser interface, used to parse {@link Part} and convert to
 * {@link ContentBlock}.
 *
 * @author xiweng.yy
 */
public interface PartParser<T extends Part<?>> {

    /**
     * Parse the given part and convert it to a ContentBlock.
     *
     * @param part the part to parse, not null
     * @return the parsed content block
     */
    ContentBlock parse(T part);
}
