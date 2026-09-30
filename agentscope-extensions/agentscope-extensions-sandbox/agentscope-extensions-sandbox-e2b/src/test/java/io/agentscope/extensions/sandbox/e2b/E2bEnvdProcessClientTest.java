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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.MediaType;
import org.junit.jupiter.api.Test;

class E2bEnvdProcessClientTest {

    @Test
    void jsonCodecUsesConnectJsonAndJsonPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+json"), client.connectMediaType());
        assertEquals(0x00, envelope[0] & 0xFF);
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(envelope, 5, 5 + len);
        String json = new String(payload, StandardCharsets.UTF_8);
        assertEquals(
                "{\"process\":{\"cmd\":\"/bin/bash\",\"args\":[\"-l\",\"-c\","
                        + "\"echo hello\"],\"cwd\":\"/workspace\"},\"stdin\":false}",
                json);
    }

    @Test
    void protoCodecKeepsBinaryPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");
        DynamicMessage expected = client.buildStartRequest("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+proto"), client.connectMediaType());
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(envelope, 5, 5 + len);
        assertArrayEquals(expected.toByteArray(), payload);
    }

    @Test
    void jsonCodecParsesStartResponseFrame() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        DynamicMessage response = dataResponse(client, "hello\n", null);
        String json = responseJson(base64("hello\n"), null, null);

        DynamicMessage parsed =
                client.parseStartResponseFrame(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(response, parsed);
    }

    @Test
    void jsonCodecReturnsEmptyMessageForNullOrMissingEvent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));

        assertTrue(
                client.parseStartResponseFrame("null".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
        assertTrue(
                client.parseStartResponseFrame("{}".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
        assertTrue(
                client.parseStartResponseFrame("{\"event\":null}".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
    }

    @Test
    void jsonCodecSkipsMalformedBase64AndKeepsStreaming() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(
                        client,
                        connectFrames(
                                responseJson("%%%bad-base64%%%", null, null),
                                responseJson(null, base64("stderr\n"), null),
                                responseJson(null, null, 7)),
                        stdout,
                        stderr);

        assertEquals(7, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("stderr\n", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecRejectsEofBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        connectFrames(
                                                responseJson(base64("hello\n"), null, null),
                                                responseJson(null, base64("warn\n"), null)),
                                        stdout,
                                        stderr));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
        assertEquals("hello\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("warn\n", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecRejectsTruncatedFrameLengthBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] truncatedLength = new byte[] {0x00, 0x00, 0x00};

        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        truncatedLength,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
    }

    @Test
    void jsonCodecRejectsTruncatedFramePayloadBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteBuffer truncatedPayload = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
        truncatedPayload.put((byte) 0x00);
        truncatedPayload.putInt(4);
        truncatedPayload.put((byte) '{');
        truncatedPayload.put((byte) '}');

        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        truncatedPayload.array(),
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
    }

    @Test
    void jsonCodecReturnsEmptyOutputsWhenOnlyEndPresent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(client, connectFrame(responseJson(null, null, 5)), stdout, stderr);

        assertEquals(5, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecPreservesZeroExitCode() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(client, connectFrame(responseJson(null, null, 0)), stdout, stderr);

        assertEquals(0, exit);
    }

    // Connect protocol: final envelope (flags bit 1) is EndStreamMessage JSON.
    // Python SDK ServerStreamParser: if "error" in data → raise make_error(data["error"])
    // https://connectrpc.com/docs/protocol/#error-end-stream
    // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b_connect/client.py#L230-L238
    @Test
    void endStreamResponseWithErrorThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        String errorJson = "{\"error\":{\"code\":\"internal\",\"message\":\"process crash\"}}";
        byte[] frame = endStreamFrame(errorJson);

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("internal"));
        assertTrue(ex.getMessage().contains("process crash"));
    }

    // Mirror official connectrpc ConnectEnvelopeReader.handle_end_message:
    //   error = end_stream_message.get("error")
    //   if error: raise
    // An empty error object is falsy in Python, i.e. treated as success (clean break,
    // no SandboxRuntimeException). With no EndEvent received, draining then fails with
    // IOException — same as a "{}\" trailer without preceding events.
    @Test
    void endStreamResponseWithEmptyErrorObjectBreaksCleanly() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("{\"error\":{}}");

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("before receiving a process exit code"));
    }

    // Non-empty error without code is still truthy in Python (if error: raise).
    @Test
    void endStreamResponseWithMessageOnlyErrorThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("{\"error\":{\"message\":\"boom\"}}");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("boom"));
    }

    // Non-object error payloads are truthy as well when non-empty.
    @Test
    void endStreamResponseWithStringErrorThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("{\"error\":\"boom\"}");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("boom"));
    }

    // A corrupt end-stream trailer is untrustworthy: unlike corrupt data frames (skipped),
    // it propagates as IOException instead of being silently swallowed.
    @Test
    void corruptEndStreamPayloadThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("not-json{{{");

        assertThrows(
                IOException.class,
                () ->
                        drainStartStream(
                                client,
                                frame,
                                new ByteArrayOutputStream(),
                                new ByteArrayOutputStream()));
    }

    // Falsy error payloads (Python: 0, false, "" are all falsy) end the stream
    // cleanly without raising — mirroring connectrpc's `if error: raise`.
    @Test
    void endStreamResponseWithFalsyErrorBreaksCleanly() throws Exception {
        for (String payload :
                new String[] {"{\"error\":0}", "{\"error\":false}", "{\"error\":\"\"}"}) {
            E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
            byte[] frame = endStreamFrame(payload);

            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    drainStartStream(
                                            client,
                                            frame,
                                            new ByteArrayOutputStream(),
                                            new ByteArrayOutputStream()),
                            "payload: " + payload);
            // Clean end-stream break, then missing-exit error — never a
            // SandboxRuntimeException and never a NumberFormatException.
            assertTrue(ex.getMessage().contains("before receiving a process exit code"));
        }
    }

    // Extreme numerics must not crash the parser: 1e999 decodes to Infinity
    // (truthy in Python), so it raises — via SandboxRuntimeException, never via
    // NumberFormatException escaping the throws-IOException contract.
    @Test
    void endStreamResponseWithInfiniteErrorThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("{\"error\":1e999}");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
    }

    // Connect protocol: successful EndStreamResponse is "{}" (no error, no metadata).
    // Python SDK: end_stream without "error" → return (stop iteration).
    @Test
    void endStreamResponseWithoutErrorBreaksCleanly() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame(responseJson(base64("hello"), null, null)),
                        connectFrame(endEventJson(0, null)),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(0, exit);
        assertEquals("hello", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // An end event carrying neither exit code nor error must not fabricate exit 0:
    // without it the stream fails with "before receiving a process exit code".
    // (Proto3 note: a present-but-empty EndEvent on the wire behaves the same —
    // see emptyEndEventThrowsProto. Real envd always populates exit_code.)
    @Test
    void emptyEndEventThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = concatFrames(connectFrame(endEventJson(null, null)), endStreamFrame("{}"));

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("before receiving a process exit code"));
    }

    // Proto-wire counterpart: exit_code=0 is bit-identical to a field-less EndEvent
    // (proto3 omits default scalars), so they cannot be distinguished — the official
    // Python SDK reads end.exit_code directly and reports 0 for the same reason.
    @Test
    void emptyEndEventReadsZeroProto() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage endResp = endEventResponse(client, null, null);
        byte[] frame = concatFrames(connectFrame(endResp.toByteArray()), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(0, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void exitCodeNonZero() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = concatFrames(connectFrame(endEventJson(42, null)), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(42, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // EndEvent.error — populated when cmd.Wait() returns ExitError
    // (non-zero exit code or signal). Go handler: Error = errMsg.
    // https://github.com/e2b-dev/infra/blob/main/packages/envd/internal/services/process/handler/handler.go
    // Python SDK: CommandResult(error=event.event.end.error)
    // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b/sandbox_sync/commands/command_handle.py#L115
    // Mirrors the real signal-kill shape observed against live envd:
    // exit_code=-1 with EndEvent.error="signal: killed".
    @Test
    void endEventErrorWrittenToStderr() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame(endEventJson(-1, "signal: killed")), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(-1, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("signal: killed"));
    }

    // EndEvent.error is separated from preceding stderr output lacking a trailing
    // newline, so the two never glue together ("partial" + "exit status 1").
    @Test
    void endEventErrorSeparatedFromStderr() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame(responseJson(null, base64("partial"), null)),
                        connectFrame(endEventJson(1, "exit status 1")),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(1, exit);
        assertEquals("partial\nexit status 1", stderr.toString(StandardCharsets.UTF_8));
    }

    // Connect protocol: flags bit 0 = compressed (not supported by this client).
    // https://connectrpc.com/docs/protocol/#streaming-rpcs
    @Test
    void compressedFrameThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = envelope(0x01, "{}".getBytes(StandardCharsets.UTF_8));

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("Compressed"));
    }

    // Connect protocol: flags bits 2-7 reserved — implementations must skip unless understood.
    // https://connectrpc.com/docs/protocol/#streaming-rpcs
    @Test
    void reservedFlagsFrameIsSkipped() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        envelope(0x04, "ignored".getBytes(StandardCharsets.UTF_8)),
                        connectFrame(endEventJson(3, null)),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(3, exit);
    }

    // ---- PROTO codec tests ----

    // Data frames alone carry no exit code: the stream is rejected, mirroring
    // jsonCodecRejectsEofBeforeEnd for the proto codec.
    @Test
    void protoCodecDrainStartStreamRejectsMissingEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage dataResp = dataResponse(client, "hello\n", null);
        byte[] frame = concatFrames(connectFrame(dataResp.toByteArray()), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        IOException ex =
                assertThrows(
                        IOException.class, () -> drainStartStream(client, frame, stdout, stderr));
        assertTrue(ex.getMessage().contains("before receiving a process exit code"));
        assertEquals("hello\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void protoCodecDrainStartStreamWithEndEvent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage dataResp = dataResponse(client, null, "err\n");
        DynamicMessage endResp = endEventResponse(client, 42, "oops");
        byte[] frame =
                concatFrames(
                        connectFrame(dataResp.toByteArray()),
                        connectFrame(endResp.toByteArray()),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(42, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("oops"));
    }

    @Test
    void protoCodecDrainStartStreamWithEndOnly() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage endResp = endEventResponse(client, 7, null);
        byte[] frame = concatFrames(connectFrame(endResp.toByteArray()), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(7, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // ---- drainStartStream edge cases ----

    @Test
    void illegalFrameLengthThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = new byte[5];
        frame[0] = 0x00;
        ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(64 * 1024 * 1024 + 1);

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("Invalid connect frame length"));
    }

    // Connect Message-Length is unsigned: 0x80000005 must decode negative and be
    // rejected. The old `& 0x7FFFFFFF` mask turned it into 5, misaligning the stream.
    @Test
    void highBitFrameLengthThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = new byte[5];
        frame[0] = 0x00;
        ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(0x80000005);

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("Invalid connect frame length"));
    }

    @Test
    void zeroLengthFrameIsSkipped() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        envelope(0x00, new byte[0]),
                        connectFrame(endEventJson(99, null)),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(99, exit);
    }

    // ---- parseJsonStartResponse edge cases ----

    @Test
    void jsonCodecNonIntegerExitCodeIsIgnored() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        String json = "{\"event\":{\"end\":{\"exitCode\":\"abc\"}}}";
        byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
        // A non-convertible exitCode is ignored, leaving an empty end event —
        // which must not fabricate exit 0 (see emptyEndEventThrows).
        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("before receiving a process exit code"));
    }

    @Test
    void jsonCodecNonTextualErrorIsIgnored() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        String json = "{\"event\":{\"end\":{\"exitCode\":1,\"error\":123}}}";
        byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(1, exit);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    private static DynamicMessage dataResponse(
            E2bEnvdProcessClient client, String stdout, String stderr) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor dataDesc = processEventDesc.findNestedTypeByName("DataEvent");

        DynamicMessage data =
                DynamicMessage.newBuilder(dataDesc)
                        .setField(
                                stdout != null
                                        ? dataDesc.findFieldByName("stdout")
                                        : dataDesc.findFieldByName("stderr"),
                                ByteString.copyFromUtf8(stdout != null ? stdout : stderr))
                        .build();
        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("data"), data)
                        .build();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build();
    }

    private static DynamicMessage endEventResponse(
            E2bEnvdProcessClient client, Integer exitCode, String error) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");

        DynamicMessage.Builder endBuilder = DynamicMessage.newBuilder(endDesc);
        if (exitCode != null) {
            endBuilder.setField(endDesc.findFieldByName("exit_code"), exitCode);
        }
        if (error != null) {
            endBuilder.setField(endDesc.findFieldByName("error"), error);
        }
        DynamicMessage end = endBuilder.build();

        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("end"), end)
                        .build();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build();
    }

    private static int drainStartStream(
            E2bEnvdProcessClient client,
            byte[] connectFrame,
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr)
            throws Exception {
        Method method =
                E2bEnvdProcessClient.class.getDeclaredMethod(
                        "drainStartStream",
                        InputStream.class,
                        ByteArrayOutputStream.class,
                        ByteArrayOutputStream.class);
        method.setAccessible(true);
        try {
            return (int)
                    method.invoke(client, new ByteArrayInputStream(connectFrame), stdout, stderr);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static byte[] connectFrame(String json) {
        return E2bEnvdProcessClient.encodeUnaryEnvelope(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] envelope(int flags, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = (byte) flags;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length);
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    private static byte[] connectFrame(byte[] payload) {
        return envelope(0x00, payload);
    }

    private static byte[] endStreamFrame(String json) {
        return envelope(0x02, json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concatFrames(byte[]... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] frame : frames) {
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    private static byte[] connectFrames(String... jsons) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String json : jsons) {
            byte[] frame = connectFrame(json);
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    private static String responseJson(String stdout, String stderr, Integer exitCode) {
        StringBuilder json = new StringBuilder("{\"event\":{");
        boolean needComma = false;
        if (stdout != null || stderr != null) {
            json.append("\"data\":{");
            boolean needDataComma = false;
            if (stdout != null) {
                json.append("\"stdout\":\"").append(stdout).append("\"");
                needDataComma = true;
            }
            if (stderr != null) {
                if (needDataComma) {
                    json.append(',');
                }
                json.append("\"stderr\":\"").append(stderr).append("\"");
            }
            json.append('}');
            needComma = true;
        }
        if (exitCode != null) {
            if (needComma) {
                json.append(',');
            }
            json.append("\"end\":{\"exitCode\":").append(exitCode).append('}');
        }
        json.append("}}");
        return json.toString();
    }

    private static String endEventJson(Integer exitCode, String error) {
        StringBuilder json = new StringBuilder("{\"event\":{\"end\":{");
        boolean needComma = false;
        if (exitCode != null) {
            json.append("\"exitCode\":").append(exitCode);
            needComma = true;
        }
        if (error != null) {
            if (needComma) {
                json.append(',');
            }
            json.append("\"error\":").append(jsonString(error));
        }
        json.append("}}}");
        return json.toString();
    }

    private static String jsonString(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                + "\"";
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static E2bSandboxClientOptions options(E2bCodec codec) {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setCodec(codec);
        return options;
    }
}
