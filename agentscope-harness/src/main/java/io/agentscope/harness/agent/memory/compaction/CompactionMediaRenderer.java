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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.harness.agent.memory.compaction.MediaReference.Disposition;
import java.util.List;
import java.util.Objects;

/**
 * Renders messages and content blocks into plain text for summarization LLM input.
 *
 * <p>The renderer preserves the fact that media existed in the conversation without embedding raw
 * binary data, URLs, access tokens or file paths in the summary. Media blocks are converted to
 * stable placeholders; tool results are processed recursively so nested media is not silently
 * dropped.
 */
public final class CompactionMediaRenderer {

    private final Disposition defaultDisposition;

    public CompactionMediaRenderer() {
        this(Disposition.DESCRIBED);
    }

    public CompactionMediaRenderer(Disposition defaultDisposition) {
        this.defaultDisposition = Objects.requireNonNull(defaultDisposition);
    }

    /** Renders a whole message as a role-prefixed line. */
    public String renderMessage(Msg msg) {
        if (msg == null || msg.getRole() == null) {
            return "";
        }
        String roleLabel =
                switch (msg.getRole()) {
                    case USER -> "Human";
                    case ASSISTANT -> "AI";
                    case TOOL -> "Tool";
                    default -> msg.getRole().name();
                };
        StringBuilder sb = new StringBuilder(roleLabel).append(": ");
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return sb.toString().strip();
        }
        boolean first = true;
        int ordinal = 0;
        for (ContentBlock block : blocks) {
            if (!first) {
                sb.append(" ");
            }
            first = false;
            sb.append(renderBlock(block, new RenderContext(msg.getId(), ordinal)));
            ordinal++;
        }
        return sb.toString().strip();
    }

    /** Renders a single content block. */
    public String renderBlock(ContentBlock block, RenderContext ctx) {
        if (block == null) {
            return "";
        }
        if (block instanceof TextBlock tb) {
            return tb.getText() == null ? "" : tb.getText().strip();
        }
        if (block instanceof ToolUseBlock tu) {
            return "[tool_call: " + (tu.getName() == null ? "?" : tu.getName()) + "]";
        }
        if (block instanceof ToolResultBlock tr) {
            return renderToolResult(tr, ctx);
        }
        if (block instanceof ImageBlock ib) {
            return MediaReference.fromImageBlock(ib, ctx.messageId, ctx.ordinal, defaultDisposition)
                    .toPlaceholder();
        }
        if (block instanceof AudioBlock ab) {
            return MediaReference.fromAudioBlock(ab, ctx.messageId, ctx.ordinal, defaultDisposition)
                    .toPlaceholder();
        }
        if (block instanceof VideoBlock vb) {
            return MediaReference.fromVideoBlock(vb, ctx.messageId, ctx.ordinal, defaultDisposition)
                    .toPlaceholder();
        }
        if (block instanceof DataBlock db) {
            return MediaReference.fromDataBlock(db, defaultDisposition).toPlaceholder();
        }
        return "[unknown_block type=" + block.getClass().getSimpleName() + "]";
    }

    /** Renders a tool result block, recursively describing any media in its output. */
    private String renderToolResult(ToolResultBlock tr, RenderContext ctx) {
        StringBuilder sb = new StringBuilder("[tool_result: ");
        sb.append(tr.getName() == null ? "?" : tr.getName());
        sb.append("]");
        List<ContentBlock> output = tr.getOutput();
        if (output != null && !output.isEmpty()) {
            sb.append(" ");
            boolean first = true;
            int ordinal = 0;
            for (ContentBlock child : output) {
                if (!first) {
                    sb.append(" ");
                }
                first = false;
                String parentId = ctx.messageId == null ? "msg" : ctx.messageId;
                sb.append(
                        renderBlock(
                                child, new RenderContext(parentId + "." + ctx.ordinal, ordinal)));
                ordinal++;
            }
        }
        return sb.toString().strip();
    }

    /** Context used to build stable references for media blocks. */
    public static final class RenderContext {
        private final String messageId;
        private final int ordinal;

        public RenderContext(String messageId, int ordinal) {
            this.messageId = messageId;
            this.ordinal = ordinal;
        }

        public String getMessageId() {
            return messageId;
        }

        public int getOrdinal() {
            return ordinal;
        }
    }
}
