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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal envd client: Connect server streaming for command execution and the Filesystem REST
 * API for file transfer.
 */
final class E2bEnvdProcessClient {

    private static final Logger log = LoggerFactory.getLogger(E2bEnvdProcessClient.class);

    private static final MediaType CONNECT_JSON = MediaType.get("application/connect+json");
    private static final MediaType CONNECT_PROTO = MediaType.get("application/connect+proto");
    private static final MediaType APPLICATION_OCTET_STREAM =
            MediaType.get("application/octet-stream");
    private static final int ENVD_PORT = 49983;
    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OkHttpClient http;
    private final Descriptors.Descriptor startRequestDesc;
    private final Descriptors.Descriptor startResponseDesc;
    private final Descriptors.Descriptor processEventDesc;
    private final E2bSandboxClientOptions opt;

    E2bEnvdProcessClient(E2bSandboxClientOptions opt) throws Exception {
        this.opt = Objects.requireNonNull(opt, "opt");
        if (opt.getHttpClient() != null) {
            this.http = opt.getHttpClient();
        } else {
            this.http =
                    new OkHttpClient.Builder()
                            .connectTimeout(opt.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(opt.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                            .build();
        }
        Descriptors.FileDescriptor fileDescriptor;
        try (InputStream in =
                E2bEnvdProcessClient.class.getResourceAsStream("/e2b-process-fdp.pb")) {
            if (in == null) {
                throw new IOException("Missing classpath resource /e2b-process-fdp.pb");
            }
            com.google.protobuf.DescriptorProtos.FileDescriptorProto fdp =
                    com.google.protobuf.DescriptorProtos.FileDescriptorProto.parseFrom(
                            in.readAllBytes());
            fileDescriptor =
                    com.google.protobuf.Descriptors.FileDescriptor.buildFrom(
                            fdp, new com.google.protobuf.Descriptors.FileDescriptor[0]);
        }
        this.startRequestDesc = fileDescriptor.findMessageTypeByName("StartRequest");
        this.startResponseDesc = fileDescriptor.findMessageTypeByName("StartResponse");
        this.processEventDesc = fileDescriptor.findMessageTypeByName("ProcessEvent");
    }

    ExecResult runShell(E2bSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        ShellCapture cap = runShellCapture(state, cwd, shellCommand, timeoutSeconds);
        String outStr = truncateUtf8(cap.stdout.toByteArray());
        String errStr = truncateUtf8(cap.stderr.toByteArray());
        boolean truncated =
                cap.stdout.size() >= OUTPUT_TRUNCATE_BYTES
                        || cap.stderr.size() >= OUTPUT_TRUNCATE_BYTES;
        ExecResult r = new ExecResult(cap.exitCode, outStr, errStr, truncated);
        if (!r.ok()) {
            throw new SandboxException.ExecException(cap.exitCode, outStr, errStr);
        }
        return r;
    }

    private ShellCapture runShellCapture(
            E2bSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        if (opt.getEnvdBaseUrl() != null && !opt.getEnvdBaseUrl().isBlank()) {
            return runShellCaptureWithTermination(state, cwd, shellCommand, timeoutSeconds);
        }
        return runShellCaptureWithManagedDeadline(state, cwd, shellCommand, timeoutSeconds);
    }

    private ShellCapture runShellCaptureWithManagedDeadline(
            E2bSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException(
                    "[e2b] timeoutSeconds must be zero or positive, got "
                            + timeoutSeconds
                            + ". This is a caller bug, not a sandbox failure;"
                            + " check the timeout argument.");
        }
        boolean bounded = timeoutSeconds > 0;
        boolean preInterrupted = Thread.interrupted();
        if (preInterrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("exec cancelled before start");
        }
        OkHttpClient.Builder callClientBuilder = http.newBuilder().readTimeout(0, TimeUnit.SECONDS);
        if (bounded) {
            callClientBuilder.callTimeout(
                    timeoutSeconds * 1000L + CLIENT_TIMEOUT_SLACK_MILLIS, TimeUnit.MILLISECONDS);
        } else {
            callClientBuilder.callTimeout(0, TimeUnit.MILLISECONDS);
        }
        OkHttpClient callClient = callClientBuilder.build();
        byte[] envelope = encodeStartRequestEnvelope(shellCommand, cwd);
        Request.Builder requestBuilder =
                buildEnvdRequest(
                        envdHost(state) + "/process.Process/Start",
                        envelope,
                        connectMediaType(),
                        state);
        if (bounded) {
            requestBuilder.addHeader("Connect-Timeout-Ms", String.valueOf(timeoutSeconds * 1000L));
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        long startNanos = System.nanoTime();
        try (Response res = callClient.newCall(requestBuilder.build()).execute()) {
            if (!res.isSuccessful()) {
                String err = Objects.requireNonNull(res.body(), "envd response body").string();
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "envd Start failed HTTP " + res.code() + ": " + err);
            }
            try (InputStream in =
                    Objects.requireNonNull(res.body(), "envd response body").byteStream()) {
                return new ShellCapture(drainStartStream(in, stdout, stderr), stdout, stderr);
            }
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            if (!bounded) {
                throw e;
            }
            throw new SandboxException.ExecTimeoutException(shellCommand, timeoutSeconds);
        } catch (ConnectStreamException e) {
            if (bounded && CONNECT_DEADLINE_EXCEEDED.equals(e.code())) {
                logPartialStreams(
                        "timed out (server deadline_exceeded)",
                        timeoutSeconds,
                        startNanos,
                        stdout,
                        stderr);
                throw new SandboxException.ExecTimeoutException(shellCommand, timeoutSeconds);
            }
            throw new IOException(
                    "envd process stream error [" + e.code() + "]: " + e.getMessage(), e);
        } catch (MissingExitCodeException e) {
            logPartialStreams(
                    "ended without exit code", timeoutSeconds, startNanos, stdout, stderr);
            throw mapPrematureEnd(e, shellCommand, timeoutSeconds, startNanos);
        }
    }

    private ShellCapture runShellCaptureWithTermination(
            E2bSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException(
                    "[e2b] timeoutSeconds must be zero or positive, got "
                            + timeoutSeconds
                            + ". This is a caller bug, not a sandbox failure;"
                            + " check the timeout argument.");
        }
        OkHttpClient callClient =
                http.newBuilder()
                        .readTimeout(0, TimeUnit.SECONDS)
                        .callTimeout(0, TimeUnit.SECONDS)
                        .build();
        String tag = "agentscope-" + UUID.randomUUID();
        DynamicMessage.Builder start = buildStartRequest(shellCommand, cwd).toBuilder();
        start.setField(startRequestDesc.findFieldByName("tag"), tag);
        byte[] payload =
                codec() == E2bCodec.JSON
                        ? encodeJsonStartRequest(start.build())
                        : start.build().toByteArray();
        Request.Builder requestBuilder =
                buildEnvdRequest(
                        envdHost(state) + "/process.Process/Start",
                        encodeUnaryEnvelope(payload),
                        connectMediaType(),
                        state);
        if (timeoutSeconds > 0) {
            requestBuilder.addHeader("Connect-Timeout-Ms", String.valueOf(timeoutSeconds * 1000L));
        }
        var call = callClient.newCall(requestBuilder.build());
        var done = new AtomicBoolean();
        var cancelled = new AtomicBoolean();
        var pid = new AtomicInteger();
        Thread owner = Thread.currentThread();
        long deadline =
                timeoutSeconds > 0
                        ? System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
                        : Long.MAX_VALUE;
        var watchdog =
                new Thread(
                        () -> {
                            try {
                                while (!done.get()) {
                                    if (owner.isInterrupted() || System.nanoTime() >= deadline) {
                                        cancelled.set(true);
                                        try {
                                            terminate(state, tag, pid.get());
                                        } catch (Exception ignored) {
                                            // The caller verifies termination again below.
                                        }
                                        call.cancel();
                                        return;
                                    }
                                    Thread.sleep(50);
                                }
                            } catch (InterruptedException finished) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "e2b-process-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (Response res = call.execute()) {
            if (!res.isSuccessful()) {
                String err = Objects.requireNonNull(res.body(), "envd response body").string();
                throw new IOException("envd Start failed HTTP " + res.code() + ": " + err);
            }
            try (InputStream in =
                    Objects.requireNonNull(res.body(), "envd response body").byteStream()) {
                int exit = drainStartStream(in, stdout, stderr, pid);
                if (cancelled.get()) {
                    throw new InterruptedIOException("Remote process cancelled");
                }
                return new ShellCapture(exit, stdout, stderr);
            }
        } catch (Exception failure) {
            boolean interrupted = Thread.interrupted();
            try {
                terminate(state, tag, pid.get());
            } catch (Exception termination) {
                var unknown =
                        new IOException("Remote process termination outcome unknown", termination);
                unknown.addSuppressed(failure);
                throw unknown;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (owner.isInterrupted()) {
                var stopped = new InterruptedException("Remote command interrupted");
                stopped.addSuppressed(failure);
                throw stopped;
            }
            if (cancelled.get()) {
                var timedOut =
                        new SandboxException.ExecTimeoutException(shellCommand, timeoutSeconds);
                timedOut.addSuppressed(failure);
                throw timedOut;
            }
            throw failure;
        } finally {
            done.set(true);
            watchdog.interrupt();
            call.cancel();
        }
    }

    /** Connect unary RPCs use JSON without streaming envelopes, independently of Start's codec. */
    private JsonNode unary(E2bSandboxState state, String method, Object body) throws IOException {
        Request request =
                baseRequest(envdHost(state) + "/process.Process/" + method, state)
                        .post(
                                RequestBody.create(
                                        JSON.writeValueAsBytes(body),
                                        MediaType.get("application/json")))
                        .build();
        OkHttpClient client =
                http.newBuilder()
                        .readTimeout(3, TimeUnit.SECONDS)
                        .callTimeout(3, TimeUnit.SECONDS)
                        .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 404 && "SendSignal".equals(method)) {
                return JSON.createObjectNode().put("missing", true);
            }
            if (!response.isSuccessful()) {
                throw new IOException("envd " + method + " failed HTTP " + response.code());
            }
            String bodyText = response.body() == null ? "" : response.body().string();
            return bodyText.isBlank() ? JSON.createObjectNode() : JSON.readTree(bodyText);
        }
    }

    private void terminate(E2bSandboxState state, String tag, int pid) throws Exception {
        Map<String, String> selector = Map.of("tag", tag);
        JsonNode signal =
                unary(state, "SendSignal", Map.of("process", selector, "signal", "SIGNAL_SIGTERM"));
        long requestedAt = System.nanoTime();
        long until = requestedAt + TimeUnit.SECONDS.toNanos(5);
        boolean escalated = false;
        do {
            JsonNode processes = unary(state, "List", Map.of());
            boolean present = false;
            for (JsonNode process : processes.path("processes")) {
                if (tag.equals(process.path("tag").asText())
                        || pid > 0 && pid == process.path("pid").asInt()) {
                    present = true;
                }
            }
            if (!present && (pid > 0 || !signal.path("missing").asBoolean())) {
                return;
            }
            if (!present && pid == 0) {
                throw new IOException(
                        "Process start was not acknowledged; termination outcome unknown");
            }
            if (!escalated
                    && System.nanoTime() - requestedAt >= TimeUnit.MILLISECONDS.toNanos(500)) {
                unary(state, "SendSignal", Map.of("process", selector, "signal", "SIGNAL_SIGKILL"));
                escalated = true;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < until);
        throw new IOException("Remote process termination was not confirmed");
    }

    private record ShellCapture(
            int exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {}

    /** Connect error code envd returns when the server-side exec deadline fires. */
    private static final String CONNECT_DEADLINE_EXCEEDED = "deadline_exceeded";

    /**
     * Fault-grading threshold between the server deadline and the client backup timeout.
     * Within T: the server kills on time and the client observes it via {@code
     * deadline_exceeded} — the slack is never consumed. Within T+slack: engineering
     * tolerance absorbing connect/setup jitter (cold handshakes, weak networks), so the
     * kill still lands before any retry can overlap a running process. Beyond T+slack:
     * treated as a network-layer fault (request never arrived, or the server hung) and
     * the client backup fires to guarantee a local return. 5s covers the connection
     * setup distribution short of extreme degradation; larger values would approach the
     * agent retry interval and buy nothing further.
     */
    private static final long CLIENT_TIMEOUT_SLACK_MILLIS = 5000;

    /** End-stream frame carrying a Connect error (e.g. server-side timeout kill). */
    static final class ConnectStreamException extends IOException {
        private final String code;

        ConnectStreamException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** Stream ended without a process exit code (#2828). */
    static final class MissingExitCodeException extends IOException {
        MissingExitCodeException() {
            super("envd process stream ended before receiving a process exit code");
        }
    }

    private static long elapsedSeconds(long startNanos) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
    }

    static Exception mapPrematureEnd(
            MissingExitCodeException e, String shellCommand, int timeoutSeconds, long startNanos) {
        // With no deadline (0) an early end is never a timeout, however long it ran.
        if (timeoutSeconds > 0 && elapsedSeconds(startNanos) >= timeoutSeconds) {
            return new SandboxException.ExecTimeoutException(shellCommand, timeoutSeconds);
        }
        return e;
    }

    private static void logPartialStreams(
            String why,
            int timeoutSeconds,
            long startNanos,
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "[e2b] exec {} after {}s with {};"
                        + " partial stdout tail: {}; partial stderr tail: {}",
                why,
                elapsedSeconds(startNanos),
                timeoutSeconds > 0 ? timeoutSeconds + "s timeout" : "no deadline",
                tailUtf8(stdout),
                tailUtf8(stderr));
    }

    private static String tailUtf8(ByteArrayOutputStream out) {
        byte[] b = out.toByteArray();
        int n = Math.min(b.length, 512);
        return new String(b, b.length - n, n, StandardCharsets.UTF_8);
    }

    private int drainStartStream(
            InputStream in,
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr,
            AtomicInteger pid)
            throws IOException {
        Integer exit = null;
        Descriptors.FieldDescriptor srEventF = startResponseDesc.findFieldByName("event");
        Descriptors.FieldDescriptor peDataF = processEventDesc.findFieldByName("data");
        Descriptors.FieldDescriptor peEndF = processEventDesc.findFieldByName("end");
        while (true) {
            int flags = in.read();
            if (flags == -1) {
                break;
            }
            byte[] lenB = in.readNBytes(4);
            if (lenB.length < 4) {
                break;
            }
            // Connect protocol: Message-Length is 4-byte unsigned integer, big-endian
            // https://connectrpc.com/docs/protocol/#streaming-rpcs
            int len = ByteBuffer.wrap(lenB).order(ByteOrder.BIG_ENDIAN).getInt();
            int frameLimit =
                    opt.getMaxCapturedBytes() > 0
                            ? Math.min(64 * 1024 * 1024, opt.getMaxCapturedBytes())
                            : 64 * 1024 * 1024;
            if (len < 0 || len > frameLimit) {
                throw new IOException("Invalid connect frame length: " + len);
            }
            byte[] data = in.readNBytes(len);
            if (data.length < len) {
                break;
            }
            // Connect protocol: flags bit 0 = compressed. Checked before end_stream so
            // a combined envelope (e.g. 0x03) is rejected as compressed instead of
            // feeding compressed bytes to the EndStreamMessage JSON parser.
            // https://connectrpc.com/docs/protocol/#streaming-rpcs
            if ((flags & 0x01) != 0) {
                throw new IOException("Compressed connect frames not supported");
            }
            // Connect protocol: flags bit 1 = end_stream — final envelope carries EndStreamMessage
            // JSON
            // https://connectrpc.com/docs/protocol/#streaming-rpcs
            if ((flags & 0x02) != 0) {
                EndStreamMessage endMsg = parseEndStreamResponse(data);
                if (endMsg.hasError()) {
                    log.debug(
                            "drainStartStream: endStream error code={} msg={}",
                            endMsg.code(),
                            endMsg.message());
                    // A server-side exec deadline kill surfaces here as
                    // deadline_exceeded; propagate it typed so the caller maps it to
                    // an exec timeout instead of a generic stream failure (#2974).
                    if (CONNECT_DEADLINE_EXCEEDED.equals(endMsg.code())) {
                        throw new ConnectStreamException(endMsg.code(), endMsg.message());
                    }
                    throw new SandboxException.SandboxRuntimeException(
                            SandboxErrorCode.WORKSPACE_START_ERROR,
                            "Process start failed: " + endMsg.description());
                }
                log.debug("drainStartStream: endStream ok");
                break;
            }
            // Connect protocol: flags bits 2-7 reserved for future extensions
            // https://connectrpc.com/docs/protocol/#streaming-rpcs
            if ((flags & 0xFC) != 0) {
                log.debug(
                        "drainStartStream: skipping reserved flags=0x{}",
                        Integer.toHexString(flags));
                continue;
            }
            DynamicMessage sr = parseStartResponseFrame(data);
            if (!sr.hasField(srEventF)) {
                log.debug("drainStartStream: frame with no event");
                continue;
            }
            DynamicMessage pe = (DynamicMessage) sr.getField(srEventF);
            Descriptors.FieldDescriptor startField = processEventDesc.findFieldByName("start");
            if (pe.hasField(startField)) {
                DynamicMessage started = (DynamicMessage) pe.getField(startField);
                pid.set(
                        ((Number)
                                        started.getField(
                                                started.getDescriptorForType()
                                                        .findFieldByName("pid")))
                                .intValue());
            }
            if (pe.hasField(peDataF)) {
                DynamicMessage de = (DynamicMessage) pe.getField(peDataF);
                appendDataStream(de, "stdout", stdout);
                appendDataStream(de, "stderr", stderr);
            }
            if (pe.hasField(peEndF)) {
                DynamicMessage end = (DynamicMessage) pe.getField(peEndF);
                // Note: no emptiness guard here. On the proto wire, an EndEvent with
                // exit_code=0 is bit-identical to an EndEvent with no fields (proto3
                // omits default scalars), so they cannot be distinguished — the official
                // Python SDK reads end.exit_code directly for the same reason. The JSON
                // path filters truly-empty end events earlier in parseJsonStartResponse.
                Descriptors.Descriptor endDesc = end.getDescriptorForType();
                // Proto3: scalar sint32 defaults to 0, omitted from wire when 0.
                // getField() returns proto3 default (0) when absent; hasField() returns false.
                // https://protobuf.dev/programming-guides/proto3/#default
                Descriptors.FieldDescriptor ecF = endDesc.findFieldByName("exit_code");
                if (ecF != null) {
                    exit = ((Number) end.getField(ecF)).intValue();
                }
                // EndEvent.error — populated when cmd.Wait() returns ExitError
                // (non-zero exit code or signal). Go handler: ProcessEvent_EndEvent.Error =
                // errMsg
                // https://github.com/e2b-dev/infra/blob/main/packages/envd/internal/services/process/handler/handler.go
                // Python SDK: error=event.event.end.error
                // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b/sandbox_sync/commands/command_handle.py#L115
                Descriptors.FieldDescriptor errF = endDesc.findFieldByName("error");
                if (errF != null && end.hasField(errF)) {
                    writeStderrLine(
                            stderr,
                            String.valueOf(end.getField(errF)).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        if (exit == null) {
            throw new MissingExitCodeException();
        }
        return exit;
    }

    private int drainStartStream(
            InputStream in, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr)
            throws IOException {
        return drainStartStream(in, stdout, stderr, new AtomicInteger());
    }

    private static void writeStderrLine(ByteArrayOutputStream stderr, byte[] text) {
        // Keep EndEvent.error visually separate from preceding stderr output that
        // may not end with a newline ("partial" + "exit status 1" is unreadable).
        byte[] buffered = stderr.toByteArray();
        if (buffered.length > 0 && buffered[buffered.length - 1] != '\n') {
            stderr.write('\n');
        }
        stderr.writeBytes(text);
    }

    private void appendDataStream(DynamicMessage dataEv, String field, ByteArrayOutputStream out)
            throws IOException {
        Descriptors.FieldDescriptor fieldDescriptor =
                dataEv.getDescriptorForType().findFieldByName(field);
        if (fieldDescriptor != null) {
            Object value = dataEv.getField(fieldDescriptor);
            if (value instanceof ByteString) {
                if (opt.getMaxCapturedBytes() > 0
                        && ((ByteString) value).size() > opt.getMaxCapturedBytes() - out.size()) {
                    throw new CaptureLimitException();
                }
                ((ByteString) value).writeTo(out);
            }
        }
    }

    private static final class CaptureLimitException extends IOException {
        CaptureLimitException() {
            super("envd process output exceeds capture limits");
        }
    }

    // Connect protocol EndStreamMessage JSON: {"error":{"code":"...","message":"..."}}
    // Error presence follows the official SDK truthiness rule: falsy error values
    // mean success, while truthy values signal failure.
    // https://connectrpc.com/docs/protocol/#error-end-stream
    // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b_connect/client.py#L230-L238
    private static EndStreamMessage parseEndStreamResponse(byte[] data) throws IOException {
        JsonNode root = JSON.readTree(data);
        if (root == null || root.isNull()) {
            return new EndStreamMessage(false, null, null);
        }
        JsonNode errorNode = root.path("error");
        // Mirror official connectrpc ConnectEnvelopeReader.handle_end_message:
        //   error = end_stream_message.get("error")
        //   if error: raise
        // Python truthiness: missing/null/empty object/empty string/0/false mean success;
        // anything else (non-empty object, non-empty string, ...) signals failure.
        if (isFalsy(errorNode)) {
            return new EndStreamMessage(false, null, null);
        }
        String code = errorNode.path("code").asText(null);
        String message = errorNode.path("message").asText(null);
        if (code == null && message == null) {
            message = errorNode.isTextual() ? errorNode.textValue() : errorNode.toString();
        }
        return new EndStreamMessage(true, code, message);
    }

    private static boolean isFalsy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return true;
        }
        if (node.isObject() || node.isArray()) {
            return node.isEmpty();
        }
        if (node.isTextual()) {
            return node.textValue().isEmpty();
        }
        if (node.isNumber()) {
            // asDouble(): infinities stay infinite (truthy), NaN becomes 0.0 only if the
            // codec produced it — decimalValue() would throw NumberFormatException instead.
            return node.asDouble() == 0.0;
        }
        return !node.asBoolean(true);
    }

    private record EndStreamMessage(boolean errorPresent, String code, String message) {
        boolean hasError() {
            return errorPresent;
        }

        // Either side may be absent (Connect Error allows code-only or message-only);
        // never render a literal "null" into the user-visible message.
        String description() {
            if (code != null && message != null) {
                return code + ": " + message;
            }
            return message != null ? message : code;
        }
    }

    DynamicMessage buildStartRequest(String shellCommand, String cwd) {
        Descriptors.Descriptor pcDesc =
                startRequestDesc.getFile().findMessageTypeByName("ProcessConfig");
        DynamicMessage.Builder pcb = DynamicMessage.newBuilder(pcDesc);
        pcb.setField(pcDesc.findFieldByName("cmd"), "/bin/bash");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), "-l");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), "-c");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), shellCommand);
        if (cwd != null && !cwd.isBlank()) {
            pcb.setField(pcDesc.findFieldByName("cwd"), cwd);
        }
        DynamicMessage.Builder sb = DynamicMessage.newBuilder(startRequestDesc);
        sb.setField(startRequestDesc.findFieldByName("process"), pcb.build());
        sb.setField(startRequestDesc.findFieldByName("stdin"), false);
        return sb.build();
    }

    byte[] encodeStartRequestEnvelope(String shellCommand, String cwd) throws IOException {
        DynamicMessage startReq = buildStartRequest(shellCommand, cwd);
        byte[] payload =
                codec() == E2bCodec.JSON
                        ? encodeJsonStartRequest(startReq)
                        : startReq.toByteArray();
        return encodeUnaryEnvelope(payload);
    }

    DynamicMessage parseStartResponseFrame(byte[] data) throws IOException {
        if (codec() == E2bCodec.JSON) {
            return parseJsonStartResponse(data);
        }
        try {
            return DynamicMessage.parseFrom(startResponseDesc, data);
        } catch (InvalidProtocolBufferException e) {
            throw new IOException("Failed to decode connect+proto frame", e);
        }
    }

    MediaType connectMediaType() {
        return codec() == E2bCodec.JSON ? CONNECT_JSON : CONNECT_PROTO;
    }

    Descriptors.FileDescriptor fileDescriptor() {
        return startRequestDesc.getFile();
    }

    static byte[] encodeUnaryEnvelope(byte[] msg) {
        byte[] out = new byte[5 + msg.length];
        out[0] = 0x00;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(msg.length);
        System.arraycopy(msg, 0, out, 5, msg.length);
        return out;
    }

    // ---- filesystem helpers (REST API) ----

    /**
     * Upload a file to the sandbox filesystem via the E2B Filesystem REST API. Creates parent
     * directories automatically and overwrites existing files.
     *
     * @param state      sandbox state
     * @param remotePath absolute path in the sandbox
     * @param data       file content
     */
    void uploadFile(E2bSandboxState state, String remotePath, byte[] data) throws Exception {
        String filename = filenameFromPath(remotePath);
        if (filename.isEmpty()) {
            throw new IllegalArgumentException("remotePath must be a file path: " + remotePath);
        }
        String host = envdHost(state);
        String url = host + filesUrl(remotePath);

        RequestBody fileBody = RequestBody.create(data, APPLICATION_OCTET_STREAM);
        RequestBody multipart =
                new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", filename, fileBody)
                        .build();
        Request req = baseRequest(url, state).post(multipart).build();

        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw fileTransferError(
                        res, SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR, "upload");
            }
        }
    }

    /**
     * Download a file from the sandbox filesystem via the E2B Filesystem REST API.
     *
     * @param state      sandbox state
     * @param remotePath absolute path in the sandbox
     * @return file content
     */
    byte[] downloadFile(E2bSandboxState state, String remotePath) throws Exception {
        String host = envdHost(state);
        String url = host + filesUrl(remotePath);
        Request req = baseRequest(url, state).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw fileTransferError(
                        res, SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR, "download");
            }
            return res.body() != null ? res.body().bytes() : new byte[0];
        }
    }

    private static String filenameFromPath(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private String filesUrl(String remotePath) {
        return "/files?path="
                + URLEncoder.encode(remotePath, StandardCharsets.UTF_8)
                + "&username="
                + URLEncoder.encode(effectiveRunUser(), StandardCharsets.UTF_8);
    }

    private static SandboxException.SandboxRuntimeException fileTransferError(
            Response res, SandboxErrorCode errorCode, String operation) throws IOException {
        String err = res.body() != null ? res.body().string() : "";
        return new SandboxException.SandboxRuntimeException(
                errorCode, "envd " + operation + " file failed HTTP " + res.code() + ": " + err);
    }

    private Request.Builder buildEnvdRequest(
            String url, byte[] body, MediaType mediaType, E2bSandboxState state) {
        return baseRequest(url, state)
                .post(RequestBody.create(body, mediaType))
                .addHeader("Connect-Protocol-Version", "1");
    }

    private Request.Builder baseRequest(String url, E2bSandboxState state) {
        Request.Builder rb =
                new Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "agentscope-java-e2b")
                        .addHeader("E2b-Sandbox-Id", state.getSandboxId())
                        .addHeader("E2b-Sandbox-Port", Integer.toString(ENVD_PORT))
                        .addHeader("Authorization", basicAuthUser());
        if (state.getEnvdAccessToken() != null && !state.getEnvdAccessToken().isBlank()) {
            rb.addHeader("X-Access-Token", state.getEnvdAccessToken());
        }
        return rb;
    }

    private E2bCodec codec() {
        return opt.getCodec() != null ? opt.getCodec() : E2bCodec.PROTO;
    }

    private byte[] encodeJsonStartRequest(DynamicMessage msg) throws IOException {
        Descriptors.FieldDescriptor processField = startRequestDesc.findFieldByName("process");
        Descriptors.FieldDescriptor stdinField = startRequestDesc.findFieldByName("stdin");
        DynamicMessage process = (DynamicMessage) msg.getField(processField);
        Descriptors.Descriptor processDesc = process.getDescriptorForType();

        ObjectNode root = JSON.createObjectNode();
        ObjectNode processNode = root.putObject("process");
        processNode.put(
                "cmd", stringField(process, processDesc.findFieldByName("cmd"), "/bin/bash"));
        ArrayNode argsNode = processNode.putArray("args");
        Descriptors.FieldDescriptor argsField = processDesc.findFieldByName("args");
        @SuppressWarnings("unchecked")
        java.util.List<Object> args = (java.util.List<Object>) process.getField(argsField);
        for (Object arg : args) {
            argsNode.add(String.valueOf(arg));
        }
        Descriptors.FieldDescriptor cwdField = processDesc.findFieldByName("cwd");
        if (process.hasField(cwdField)) {
            processNode.put("cwd", String.valueOf(process.getField(cwdField)));
        }
        Descriptors.FieldDescriptor tagField = startRequestDesc.findFieldByName("tag");
        if (msg.hasField(tagField)) {
            root.put("tag", String.valueOf(msg.getField(tagField)));
        }
        root.put(
                "stdin", msg.hasField(stdinField) && Boolean.TRUE.equals(msg.getField(stdinField)));
        return JSON.writeValueAsBytes(root);
    }

    private DynamicMessage parseJsonStartResponse(byte[] data) throws IOException {
        JsonNode root = JSON.readTree(data);
        if (root == null || root.isNull()) {
            return DynamicMessage.newBuilder(startResponseDesc).build();
        }
        DynamicMessage.Builder response = DynamicMessage.newBuilder(startResponseDesc);
        JsonNode eventNode = root.path("event");
        if (eventNode.isMissingNode() || eventNode.isNull()) {
            return response.build();
        }
        DynamicMessage.Builder event = DynamicMessage.newBuilder(processEventDesc);

        JsonNode startNode = eventNode.path("start");
        if (startNode.path("pid").canConvertToInt()) {
            Descriptors.Descriptor descriptor = processEventDesc.findNestedTypeByName("StartEvent");
            event.setField(
                    processEventDesc.findFieldByName("start"),
                    DynamicMessage.newBuilder(descriptor)
                            .setField(
                                    descriptor.findFieldByName("pid"),
                                    startNode.path("pid").intValue())
                            .build());
        }

        JsonNode dataNode = eventNode.path("data");
        if (!dataNode.isMissingNode() && !dataNode.isNull()) {
            Descriptors.Descriptor dataDesc = processEventDesc.findNestedTypeByName("DataEvent");
            DynamicMessage.Builder dataBuilder = DynamicMessage.newBuilder(dataDesc);
            setBytesIfPresent(dataBuilder, dataNode, "stdout", dataDesc.findFieldByName("stdout"));
            setBytesIfPresent(dataBuilder, dataNode, "stderr", dataDesc.findFieldByName("stderr"));
            if (!dataBuilder.getAllFields().isEmpty()) {
                event.setField(processEventDesc.findFieldByName("data"), dataBuilder.build());
            }
        }

        JsonNode endNode = eventNode.path("end");
        if (!endNode.isMissingNode() && !endNode.isNull()) {
            if (!endNode.isObject()) {
                throw new IOException("Invalid EndEvent: " + endNode);
            }
            Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");
            DynamicMessage.Builder endBuilder = DynamicMessage.newBuilder(endDesc);
            Descriptors.FieldDescriptor exitCodeField = endDesc.findFieldByName("exit_code");
            // protojson accepts both the camelCase jsonName ("exitCode") and the
            // original proto field name ("exit_code") on the wire: prefer jsonName,
            // fall back to the original. (Single-word fields like error/event/end
            // are identical in both forms and need no fallback.)
            JsonNode exitCodeNode = endNode.path("exitCode");
            if (exitCodeNode.isMissingNode() || exitCodeNode.isNull()) {
                exitCodeNode = endNode.path("exit_code");
            }
            if (!exitCodeNode.isMissingNode() && !exitCodeNode.isNull()) {
                endBuilder.setField(exitCodeField, parseExitCode(exitCodeNode));
            }
            Descriptors.FieldDescriptor errorField = endDesc.findFieldByName("error");
            // EndEvent.error — populated when cmd.Wait() returns ExitError
            // (non-zero exit code or signal). Go handler: Error = errMsg.
            // https://github.com/e2b-dev/infra/blob/main/packages/envd/internal/services/process/handler/handler.go
            // Python SDK: error=event.event.end.error
            // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b/sandbox_sync/commands/command_handle.py#L115
            JsonNode errorNode = endNode.path("error");
            if (errorNode.isTextual()) {
                endBuilder.setField(errorField, errorNode.textValue());
            }
            // exit_code is an implicit-presence proto3 scalar: when an EndEvent is present,
            // an omitted value is its default 0. Keep the message even when it has no fields.
            event.setField(processEventDesc.findFieldByName("end"), endBuilder.build());
        }

        if (!event.getAllFields().isEmpty()) {
            response.setField(startResponseDesc.findFieldByName("event"), event.build());
        }
        return response.build();
    }

    // EndEvent.exit_code is an int32. ProtoJSON (used by the official Python/JS SDKs) accepts
    // either a JSON number or a decimal string, requires a zero fractional part, and rejects
    // values outside the int32 range. https://protobuf.dev/programming-guides/json/
    private static int parseExitCode(JsonNode node) throws IOException {
        String text = node.isTextual() ? node.textValue() : node.isNumber() ? node.asText() : null;
        if (text == null) {
            throw new IOException("Invalid EndEvent exit_code: " + node);
        }
        try {
            return new BigDecimal(text).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IOException("Invalid EndEvent exit_code: " + node);
        }
    }

    private static void setBytesIfPresent(
            DynamicMessage.Builder builder,
            JsonNode parent,
            String jsonField,
            Descriptors.FieldDescriptor field) {
        JsonNode valueNode = parent.path(jsonField);
        if (valueNode.isTextual()) {
            try {
                builder.setField(
                        field,
                        ByteString.copyFrom(Base64.getDecoder().decode(valueNode.textValue())));
            } catch (IllegalArgumentException ignored) {
                // Malformed base64 from the server should not abort the stream.
            }
        }
    }

    private static String stringField(
            DynamicMessage message, Descriptors.FieldDescriptor field, String defaultValue) {
        if (field == null || !message.hasField(field)) {
            return defaultValue;
        }
        return String.valueOf(message.getField(field));
    }

    private String envdHost(E2bSandboxState state) {
        String override = opt.getEnvdSandboxUrl();
        if (override != null && !override.isBlank()) {
            return E2bPlatformHttp.trimSlash(override.trim());
        }
        String proxy = opt.getEnvdBaseUrl();
        if (proxy != null && !proxy.isBlank()) {
            return E2bPlatformHttp.trimSlash(proxy.trim());
        }
        String id = state.getSandboxId();
        String dom =
                state.getSandboxDomain() != null && !state.getSandboxDomain().isBlank()
                        ? state.getSandboxDomain()
                        : "e2b.app";
        return "https://" + ENVD_PORT + "-" + id + "." + dom;
    }

    private String basicAuthUser() {
        String u = effectiveRunUser();
        String token =
                Base64.getEncoder().encodeToString((u + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private String effectiveRunUser() {
        String u = opt.getRunUser();
        return u != null ? u : "user";
    }

    private static String truncateUtf8(byte[] b) {
        int n = Math.min(b.length, OUTPUT_TRUNCATE_BYTES);
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }
}
