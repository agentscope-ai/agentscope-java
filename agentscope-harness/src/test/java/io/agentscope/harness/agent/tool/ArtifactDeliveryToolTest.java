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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ArtifactDeliveryTool}. */
class ArtifactDeliveryToolTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    private AbstractFilesystem filesystem;
    private ArtifactDeliveryTarget target;
    private ArtifactDeliveryTool tool;

    @BeforeEach
    void setUp() {
        filesystem = mock(AbstractFilesystem.class);
        target = mock(ArtifactDeliveryTarget.class);
        tool = new ArtifactDeliveryTool(filesystem, target);
    }

    private ArtifactDeliveryRequest deliverRequest() {
        ArgumentCaptor<ArtifactDeliveryRequest> captor =
                ArgumentCaptor.forClass(ArtifactDeliveryRequest.class);
        verify(target).deliver(eq(RT), captor.capture());
        return captor.getValue();
    }

    private static String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }

    @Test
    void deliverArtifact_downloadsBytesAndForwardsToTarget_withDefaults() {
        byte[] content = new byte[] {1, 2, 3};
        when(filesystem.downloadFiles(RT, List.of("outputs/report.docx")))
                .thenReturn(List.of(FileDownloadResponse.success("outputs/report.docx", content)));
        when(target.deliver(eq(RT), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ArtifactDeliveryResult.success());

        ToolResultBlock result = tool.deliverArtifact(RT, "outputs/report.docx", null, null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(text.startsWith("Delivered "));
        assertTrue(text.contains("configured destination"));
        ArtifactDeliveryRequest request = deliverRequest();
        assertEquals("outputs/report.docx", request.filePath());
        assertArrayEquals(content, request.content());
        assertEquals("report.docx", request.fileName());
        assertNull(request.description());
        assertFalse(request.force());
    }

    @Test
    void deliverArtifact_forwardsFileNameDescriptionAndForce() {
        byte[] content = new byte[] {9};
        when(filesystem.downloadFiles(RT, List.of("report.md")))
                .thenReturn(List.of(FileDownloadResponse.success("report.md", content)));
        when(target.deliver(eq(RT), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ArtifactDeliveryResult.success("stored as weekly-report.md"));

        ToolResultBlock result =
                tool.deliverArtifact(RT, "report.md", "weekly-report.md", "Weekly report", true);
        String text = textOf(result);

        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(text.contains("stored as weekly-report.md"));
        ArtifactDeliveryRequest request = deliverRequest();
        assertEquals("weekly-report.md", request.fileName());
        assertEquals("Weekly report", request.description());
        assertTrue(request.force());
    }

    @Test
    void deliverArtifact_normalizesPathBeforeDownload() {
        WorkspacePathNormalizer normalizer =
                WorkspacePathNormalizer.of("D:\\ws\\.agentscope\\workspace");
        tool = new ArtifactDeliveryTool(filesystem, normalizer, target);

        when(filesystem.downloadFiles(RT, List.of("report.md")))
                .thenReturn(List.of(FileDownloadResponse.success("report.md", new byte[] {1})));
        when(target.deliver(eq(RT), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ArtifactDeliveryResult.success());

        tool.deliverArtifact(RT, "D:\\ws\\.agentscope\\workspace\\report.md", null, null, null);

        verify(filesystem).downloadFiles(RT, List.of("report.md"));
    }

    @Test
    void deliverArtifact_downloadFailure_doesNotCallTarget() {
        when(filesystem.downloadFiles(RT, List.of("missing.pdf")))
                .thenReturn(List.of(FileDownloadResponse.fail("missing.pdf", "not found")));

        ToolResultBlock result = tool.deliverArtifact(RT, "missing.pdf", null, null, null);

        assertEquals(ToolResultState.ERROR, result.getState());
        String text = textOf(result);
        assertTrue(text.startsWith("Error:"));
        assertTrue(text.contains("not found"));
        verify(target, never()).deliver(eq(RT), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deliverArtifact_targetFailure_isReported() {
        when(filesystem.downloadFiles(RT, List.of("report.md")))
                .thenReturn(List.of(FileDownloadResponse.success("report.md", new byte[] {1})));
        when(target.deliver(eq(RT), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ArtifactDeliveryResult.fail("webdav unreachable"));

        ToolResultBlock result = tool.deliverArtifact(RT, "report.md", null, null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text.startsWith("Error:"));
        assertTrue(text.contains("webdav unreachable"));
    }

    @Test
    void deliverArtifact_nameConflict_advisesNewFileNameOrForce() {
        when(filesystem.downloadFiles(RT, List.of("report.md")))
                .thenReturn(List.of(FileDownloadResponse.success("report.md", new byte[] {1})));
        when(target.deliver(eq(RT), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ArtifactDeliveryResult.conflict("name exists"));

        ToolResultBlock result = tool.deliverArtifact(RT, "report.md", null, null, null);

        assertEquals(ToolResultState.ERROR, result.getState());
        String text = textOf(result);
        assertTrue(text.contains("already exists"));
        assertTrue(text.contains("new fileName"));
        assertTrue(text.contains("force=true"));
    }

    @Test
    void deliverArtifact_blankPath_isRejected() {
        ToolResultBlock result = tool.deliverArtifact(RT, "   ", null, null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text.startsWith("Error:"));
        assertFalse(text.contains("Delivered"));
        verify(target, never()).deliver(eq(RT), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deliverArtifact_targetNullResult_isReported() {
        when(filesystem.downloadFiles(RT, List.of("report.md")))
                .thenReturn(List.of(FileDownloadResponse.success("report.md", new byte[] {1})));
        when(target.deliver(eq(RT), org.mockito.ArgumentMatchers.any())).thenReturn(null);

        ToolResultBlock result = tool.deliverArtifact(RT, "report.md", null, null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text.startsWith("Error:"));
    }

    @Test
    void deliverArtifact_fileNameWithSeparator_isRejected() {
        ToolResultBlock result = tool.deliverArtifact(RT, "report.md", "sub/report.md", null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text.startsWith("Error:"));
        assertTrue(text.contains("plain file name"));
        verify(target, never()).deliver(eq(RT), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deliverArtifact_fileNameDotDot_isRejected() {
        ToolResultBlock result = tool.deliverArtifact(RT, "report.md", "..", null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text.startsWith("Error:"));
        verify(target, never()).deliver(eq(RT), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deliverArtifact_fileNameDot_isRejected() {
        ToolResultBlock result = tool.deliverArtifact(RT, "report.md", ".", null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text.startsWith("Error:"));
        verify(target, never()).deliver(eq(RT), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deliverArtifact_fileNameWithNul_isRejected() {
        ToolResultBlock result = tool.deliverArtifact(RT, "report.md", "report\0.md", null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text.startsWith("Error:"));
        verify(target, never()).deliver(eq(RT), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deliverArtifact_filePathWithTrailingSeparator_derivesSafeFileName() {
        when(filesystem.downloadFiles(RT, List.of("outputs/")))
                .thenReturn(List.of(FileDownloadResponse.success("outputs/", new byte[] {1})));
        when(target.deliver(eq(RT), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ArtifactDeliveryResult.success());

        tool.deliverArtifact(RT, "outputs/", null, null, null);

        ArtifactDeliveryRequest request = deliverRequest();
        assertEquals("outputs", request.fileName());
    }

    @Test
    void deliverArtifact_filePathAllSeparators_isRejected() {
        ToolResultBlock result = tool.deliverArtifact(RT, "/", null, null, null);
        String text = textOf(result);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(text.startsWith("Error:"));
        verify(target, never()).deliver(eq(RT), org.mockito.ArgumentMatchers.any());
    }
}
