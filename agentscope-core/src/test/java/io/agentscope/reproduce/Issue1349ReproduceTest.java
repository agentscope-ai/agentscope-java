/*
 * Reproduce test for Issue #1349:
 * DefaultToolEmitter is package-private, users outside io.agentscope.core.tool
 * cannot cast ToolEmitter to DefaultToolEmitter to access toolCallId.
 */
package io.agentscope.reproduce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.DefaultToolEmitter;
import io.agentscope.core.tool.ToolEmitter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Issue1349ReproduceTest {

    @Test
    @DisplayName(
            "Issue #1349: External code should be able to cast ToolEmitter to DefaultToolEmitter"
                    + " and get toolCallId")
    void testCanAccessDefaultToolEmitterFromExternalPackage() {
        // Simulate what the framework does internally:
        // Create a ToolUseBlock with a toolCallId
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_abc123")
                        .name("my_tool")
                        .input(Map.of())
                        .content("{}")
                        .build();

        // Create a DefaultToolEmitter (as ToolExecutor does internally)
        ToolEmitter emitter = new DefaultToolEmitter(toolUseBlock, null);

        // === The actual problem: external user needs toolCallId for async session restoration ===
        // Before fix: this line would NOT compile because DefaultToolEmitter is package-private
        // After fix:  we can cast and access the ToolUseBlock
        assertTrue(
                emitter instanceof DefaultToolEmitter,
                "Should be able to check instanceof DefaultToolEmitter from external package");

        DefaultToolEmitter defaultEmitter = (DefaultToolEmitter) emitter;
        assertNotNull(
                defaultEmitter.getToolUseBlock(),
                "Should be able to call getToolUseBlock() from external package");
        assertEquals(
                "call_abc123",
                defaultEmitter.getToolUseBlock().getId(),
                "Should be able to access toolCallId from external package");
    }

    @Test
    @DisplayName("Issue #1349: Emit should still work after making class public")
    void testEmitStillWorksAfterFix() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_xyz")
                        .name("test_tool")
                        .input(Map.of())
                        .content("{}")
                        .build();

        final boolean[] callbackFired = {false};
        DefaultToolEmitter emitter =
                new DefaultToolEmitter(
                        toolUseBlock,
                        (useBlock, chunk) -> {
                            callbackFired[0] = true;
                            assertEquals("call_xyz", useBlock.getId());
                        });

        emitter.emit(ToolResultBlock.text("progress update"));
        assertTrue(callbackFired[0], "emit() callback should fire correctly");
    }
}
