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
package io.agentscope.extensions.aistio.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Control-plane hosted {@link RemoteSnapshotSpec} over {@code /api/v1/dp/snapshots/*}.
 *
 * <p>Upload first negotiates via {@code POST .../upload-url}; when the control plane returns
 * {@code mode:"inline"} (or the endpoint is missing), bytes are PUT directly to the control plane.
 */
public final class ControlPlaneSnapshotSpec extends RemoteSnapshotSpec {

    ControlPlaneSnapshotSpec(ControlPlaneHttpClient http, String agentName, String namespace) {
        super(new ControlPlaneRemoteSnapshotClient(http, agentName, namespace));
    }

    private static final class ControlPlaneRemoteSnapshotClient implements RemoteSnapshotClient {

        private final ControlPlaneHttpClient http;
        private final String agentName;
        private final String namespace;

        ControlPlaneRemoteSnapshotClient(
                ControlPlaneHttpClient http, String agentName, String namespace) {
            this.http = Objects.requireNonNull(http, "http");
            this.agentName = Objects.requireNonNull(agentName, "agentName");
            this.namespace = Objects.requireNonNull(namespace, "namespace");
        }

        @Override
        public void upload(String snapshotId, InputStream data) throws Exception {
            byte[] bytes = readAll(data);
            String mode = negotiateUploadMode(snapshotId);
            if (!"inline".equalsIgnoreCase(mode) && mode != null && !mode.isBlank()) {
                // Presigned / external modes are not yet implemented client-side.
                throw new UnsupportedOperationException(
                        "Unsupported snapshot upload mode: " + mode);
            }
            String path = "/api/v1/dp/snapshots/" + encPath(snapshotId) + "?" + tenantQuery();
            ControlPlaneHttpClient.Response resp =
                    http.sendBytes("PUT", path, "application/octet-stream", bytes);
            if (resp.status() != 201 && (resp.status() < 200 || resp.status() >= 300)) {
                throw new RuntimeException(
                        "control-plane snapshot upload failed: HTTP "
                                + resp.status()
                                + " "
                                + resp.body());
            }
        }

        @Override
        public InputStream download(String snapshotId) throws Exception {
            String path = "/api/v1/dp/snapshots/" + encPath(snapshotId) + "?" + tenantQuery();
            ControlPlaneHttpClient.BytesResponse resp = http.sendForBytes("GET", path, null, null);
            if (resp.status() == 404) {
                throw new RuntimeException("snapshot not found: " + snapshotId);
            }
            if (resp.status() < 200 || resp.status() >= 300) {
                throw new RuntimeException(
                        "control-plane snapshot download failed: HTTP " + resp.status());
            }
            return new ByteArrayInputStream(resp.body());
        }

        @Override
        public boolean exists(String snapshotId) throws Exception {
            String path = "/api/v1/dp/snapshots/" + encPath(snapshotId) + "?" + tenantQuery();
            ControlPlaneHttpClient.Response resp = http.send("HEAD", path, null);
            if (resp.status() == 200) {
                return true;
            }
            if (resp.status() == 404) {
                return false;
            }
            throw new RuntimeException(
                    "control-plane snapshot exists check failed: HTTP " + resp.status());
        }

        private String negotiateUploadMode(String snapshotId) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("agentName", agentName);
                body.put("namespace", namespace);
                String path = "/api/v1/dp/snapshots/" + encPath(snapshotId) + "/upload-url";
                ControlPlaneHttpClient.Response resp = http.send("POST", path, body);
                if (resp.status() == 404) {
                    return "inline";
                }
                if (resp.status() < 200 || resp.status() >= 300) {
                    return "inline";
                }
                JsonNode node = ControlPlaneHttpClient.mapper().readTree(resp.body());
                String mode = node.path("mode").asText("inline");
                return mode == null || mode.isBlank() ? "inline" : mode;
            } catch (Exception e) {
                return "inline";
            }
        }

        private String tenantQuery() {
            return "agentName="
                    + URLEncoder.encode(agentName, StandardCharsets.UTF_8)
                    + "&namespace="
                    + URLEncoder.encode(namespace, StandardCharsets.UTF_8);
        }

        private static String encPath(String id) {
            return URLEncoder.encode(id == null ? "" : id, StandardCharsets.UTF_8)
                    .replace("+", "%20");
        }

        private static byte[] readAll(InputStream in) throws Exception {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }
}
