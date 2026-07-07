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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal Connect client for envd {@code process.Process/Start} (server streaming), sufficient
 * for {@code sh -c} command execution and binary tar streaming on stdout.
 */
final class E2bEnvdProcessClient {

    private static final MediaType CONNECT_JSON = MediaType.get("application/connect+json");
    private static final MediaType CONNECT_PROTO = MediaType.get("application/connect+proto");
    private static final int ENVD_PORT = 49983;
    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OkHttpClient http;
    private final Descriptors.FileDescriptor fileDescriptor;
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
        try (InputStream in =
                E2bEnvdProcessClient.class.getResourceAsStream("/e2b-process-fdp.pb")) {
            if (in == null) {
                throw new IOException("Missing classpath resource /e2b-process-fdp.pb");
            }
            com.google.protobuf.DescriptorProtos.FileDescriptorProto fdp =
                    com.google.protobuf.DescriptorProtos.FileDescriptorProto.parseFrom(
                            in.readAllBytes());
            this.fileDescriptor =
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

    byte[] runShellBinaryStdout(
            E2bSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        ShellCapture cap = runShellCapture(state, cwd, shellCommand, timeoutSeconds);
        if (cap.exitCode != 0) {
            throw new SandboxException.ExecException(
                    cap.exitCode, "(binary stdout)", truncateUtf8(cap.stderr.toByteArray()));
        }
        return cap.stdout.toByteArray();
    }

    private ShellCapture runShellCapture(
            E2bSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        OkHttpClient callClient =
                timeoutSeconds > 0
                        ? http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
                        : http;
        String host = envdHost(state);
        String url = host + "/process.Process/Start";
        byte[] envelope = encodeStartRequestEnvelope(shellCommand, cwd);
        Request.Builder rb =
                new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(envelope, connectMediaType()))
                        .addHeader("Connect-Protocol-Version", "1")
                        .addHeader("User-Agent", "agentscope-java-e2b")
                        .addHeader("E2b-Sandbox-Id", state.getSandboxId())
                        .addHeader("E2b-Sandbox-Port", Integer.toString(ENVD_PORT))
                        .addHeader("Authorization", basicAuthUser(opt.getRunUser()));
        if (state.getEnvdAccessToken() != null && !state.getEnvdAccessToken().isBlank()) {
            rb.addHeader("X-Access-Token", state.getEnvdAccessToken());
        }
        Request req = rb.build();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Integer.MIN_VALUE;
        try (Response res = callClient.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String err = res.body() != null ? res.body().string() : "";
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "envd Start failed HTTP " + res.code() + ": " + err);
            }
            try (InputStream in = res.body().byteStream()) {
                exit = drainStartStream(in, stdout, stderr);
            }
        } catch (InterruptedIOException e) {
            throw new SandboxException.ExecTimeoutException(shellCommand, timeoutSeconds);
        }
        return new ShellCapture(exit, stdout, stderr);
    }

    private record ShellCapture(
            int exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {}

    private int drainStartStream(
            InputStream in, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr)
            throws IOException {
        int exit = Integer.MIN_VALUE;
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
            int len = ByteBuffer.wrap(lenB).order(ByteOrder.BIG_ENDIAN).getInt() & 0x7FFFFFFF;
            if (len < 0 || len > 64 * 1024 * 1024) {
                throw new IOException("Invalid connect frame length: " + len);
            }
            byte[] data = in.readNBytes(len);
            if (data.length < len) {
                break;
            }
            if (flags != 0x00) {
                continue;
            }
            try {
                DynamicMessage sr = parseStartResponseFrame(data);
                if (!sr.hasField(srEventF)) {
                    continue;
                }
                DynamicMessage pe = (DynamicMessage) sr.getField(srEventF);
                if (pe.hasField(peDataF)) {
                    DynamicMessage de = (DynamicMessage) pe.getField(peDataF);
                    appendDataStream(de, "stdout", stdout);
                    appendDataStream(de, "stderr", stderr);
                }
                if (pe.hasField(peEndF)) {
                    DynamicMessage end = (DynamicMessage) pe.getField(peEndF);
                    Descriptors.FieldDescriptor ec =
                            end.getDescriptorForType().findFieldByName("exit_code");
                    if (end.hasField(ec)) {
                        Object v = end.getField(ec);
                        exit = v instanceof Integer ? (Integer) v : ((Long) v).intValue();
                    }
                }
            } catch (IOException e) {
                continue;
            }
        }
        return exit;
    }

    private static void appendDataStream(
            DynamicMessage dataEv, String field, ByteArrayOutputStream out) throws IOException {
        for (Map.Entry<Descriptors.FieldDescriptor, Object> entry :
                dataEv.getAllFields().entrySet()) {
            if (!field.equals(entry.getKey().getName())) {
                continue;
            }
            Object v = entry.getValue();
            if (v instanceof ByteString) {
                ((ByteString) v).writeTo(out);
            }
            return;
        }
    }

    DynamicMessage buildStartRequest(String shellCommand, String cwd) {
        Descriptors.Descriptor pcDesc = fileDescriptor.findMessageTypeByName("ProcessConfig");
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
        return fileDescriptor;
    }

    static byte[] encodeUnaryEnvelope(byte[] msg) {
        byte[] out = new byte[5 + msg.length];
        out[0] = 0x00;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(msg.length);
        System.arraycopy(msg, 0, out, 5, msg.length);
        return out;
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
            Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");
            DynamicMessage.Builder endBuilder = DynamicMessage.newBuilder(endDesc);
            Descriptors.FieldDescriptor exitCodeField = endDesc.findFieldByName("exit_code");
            JsonNode exitCodeNode = endNode.path("exitCode");
            if (exitCodeNode.canConvertToInt()) {
                endBuilder.setField(exitCodeField, exitCodeNode.intValue());
            }
            if (!endBuilder.getAllFields().isEmpty()) {
                event.setField(processEventDesc.findFieldByName("end"), endBuilder.build());
            }
        }

        if (!event.getAllFields().isEmpty()) {
            response.setField(startResponseDesc.findFieldByName("event"), event.build());
        }
        return response.build();
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

    private static String envdHost(E2bSandboxState state) {
        String id = state.getSandboxId();
        String dom =
                state.getSandboxDomain() != null && !state.getSandboxDomain().isBlank()
                        ? state.getSandboxDomain()
                        : "e2b.app";
        return "https://" + ENVD_PORT + "-" + id + "." + dom;
    }

    private static String basicAuthUser(String user) {
        String u = user != null ? user : "user";
        String token =
                Base64.getEncoder().encodeToString((u + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static String truncateUtf8(byte[] b) {
        int n = Math.min(b.length, OUTPUT_TRUNCATE_BYTES);
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }
}
