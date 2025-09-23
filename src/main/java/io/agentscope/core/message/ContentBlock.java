/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.message;

/**
 * Base class for all content blocks in messages.
 * Content blocks represent different types of content that can be included in a message,
 * such as text, images, audio, video, or thinking content.
 */
public abstract class ContentBlock {

    /**
     * Get the type of this content block.
     * @return the content block type
     */
    public abstract ContentBlockType getType();
}
