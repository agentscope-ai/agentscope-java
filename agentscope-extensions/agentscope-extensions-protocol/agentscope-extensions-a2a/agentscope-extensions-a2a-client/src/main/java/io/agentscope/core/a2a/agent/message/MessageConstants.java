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

/**
 * Message Constants.
 */
public class MessageConstants {

    public static class BlockContent {

        public static final String TYPE_TEXT = "text";

        public static final String TYPE_THINKING = "thinking";

        public static final String TYPE_IMAGE = "image";

        public static final String TYPE_AUDIO = "audio";

        public static final String TYPE_VIDEO = "video";

        public static final String TYPE_TOOL_USE = "tool_use";

        public static final String TYPE_TOOL_RESULT = "tool_result";
    }

    public static final String SOURCE_NAME_METADATA_KEY = "_agentscope_msg_source";

    public static final String MSG_ID_METADATA_KEY = "_agentscope_msg_id";

    public static final String BLOCK_TYPE_METADATA_KEY = "_agentscope_block_type";

    public static final String TOOL_NAME_METADATA_KEY = "_agentscope_tool_name";

    public static final String TOOL_CALL_ID_METADATA_KEY = "_agentscope_tool_call_id";

    public static final String TOOL_RESULT_OUTPUT_METADATA_KEY = "_agentscope_tool_output";
}
