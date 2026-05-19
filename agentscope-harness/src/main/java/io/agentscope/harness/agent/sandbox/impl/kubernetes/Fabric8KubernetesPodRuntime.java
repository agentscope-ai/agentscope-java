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
package io.agentscope.harness.agent.sandbox.impl.kubernetes;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Kubernetes Pod operations using the Fabric8 client. */
public class Fabric8KubernetesPodRuntime {

    private static final Logger log = LoggerFactory.getLogger(Fabric8KubernetesPodRuntime.class);
    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final int POD_READY_TIMEOUT_SECONDS = 120;
    private static final int TAR_TIMEOUT_SECONDS = 120;

    private final KubernetesClient client;
    private final KubernetesSandboxClientOptions templateOptions;

    public Fabric8KubernetesPodRuntime(
            KubernetesClient client, KubernetesSandboxClientOptions templateOptions) {
        this.client = Objects.requireNonNull(client, "client");
        this.templateOptions =
                templateOptions != null ? templateOptions : new KubernetesSandboxClientOptions();
    }

    public void ensurePodReady(KubernetesSandboxState state) throws Exception {
        String ns = state.getNamespace();
        String podName = state.getPodName();
        if (podName == null || podName.isBlank()) {
            String name = buildPodName(state.getSessionId());
            state.setPodName(name);
            podName = name;
            createPod(state);
        } else {
            Pod existing = client.pods().inNamespace(ns).withName(podName).get();
            if (existing == null) {
                log.warn("[sandbox-k8s] Pod {} not found in {}, recreating workspace", podName, ns);
                state.setWorkspaceRootReady(false);
                createPod(state);
            }
        }
        client.pods()
                .inNamespace(ns)
                .withName(state.getPodName())
                .waitUntilReady(POD_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public void deletePodIfOwned(KubernetesSandboxState state) {
        if (!state.isPodOwned()) {
            return;
        }
        String ns = state.getNamespace();
        String podName = state.getPodName();
        if (podName == null || podName.isBlank()) {
            return;
        }
        try {
            client.pods().inNamespace(ns).withName(podName).delete();
        } catch (Exception e) {
            log.warn("[sandbox-k8s] Failed to delete pod {}: {}", podName, e.getMessage());
        }
    }

    public ExecResult exec(KubernetesSandboxState state, String command, int timeoutSeconds)
            throws Exception {
        String script =
                "cd " + shellSingleQuote(state.getWorkspaceRoot()) + " && (" + command + ")";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ContainerResource pod = pod(state);
        try (ExecWatch watch = pod.writingOutput(out).writingError(err).exec("sh", "-c", script)) {
            Integer exit = watch.exitCode().get(timeoutSeconds + 5L, TimeUnit.SECONDS);
            int code = exit != null ? exit : -1;
            String stdout = truncate(out.toByteArray());
            String stderr = truncate(err.toByteArray());
            boolean truncated =
                    out.size() >= OUTPUT_TRUNCATE_BYTES || err.size() >= OUTPUT_TRUNCATE_BYTES;
            ExecResult result = new ExecResult(code, stdout, stderr, truncated);
            if (!result.ok()) {
                throw new SandboxException.ExecException(code, stdout, stderr);
            }
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new SandboxException.ExecTimeoutException(command, timeoutSeconds);
        }
    }

    public InputStream tarWorkspaceOut(KubernetesSandboxState state) throws Exception {
        String root = state.getWorkspaceRoot();
        StringBuilder script = new StringBuilder("tar ");
        for (String ex :
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(state.getWorkspaceSpec())) {
            script.append(ex).append(' ');
        }
        script.append("-cf - -C ").append(shellSingleQuote(root)).append(" .");
        String cmd = script.toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ContainerResource pod = pod(state);
        try (ExecWatch watch = pod.writingOutput(out).writingError(err).exec("sh", "-c", cmd)) {
            Integer exit = watch.exitCode().get(TAR_TIMEOUT_SECONDS + 10L, TimeUnit.SECONDS);
            if (exit == null || exit != 0) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                        "tar failed (exit=" + exit + "): " + truncate(err.toByteArray()));
            }
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    public void tarWorkspaceIn(KubernetesSandboxState state, InputStream archive) throws Exception {
        hydrateWithArchive(state, archive);
    }

    private void hydrateWithArchive(KubernetesSandboxState state, InputStream archive)
            throws Exception {
        String root = state.getWorkspaceRoot();
        mkdir(state, root);
        ContainerResource pod = pod(state);
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try (ExecWatch watch =
                pod.readingInput(archive)
                        .writingError(errBuf)
                        .exec("tar", "-xf", "-", "-C", root)) {
            Integer exit = watch.exitCode().get(TAR_TIMEOUT_SECONDS + 10L, TimeUnit.SECONDS);
            if (exit == null || exit != 0) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                        "tar extract failed (exit="
                                + exit
                                + "): "
                                + errBuf.toString(StandardCharsets.UTF_8));
            }
        }
    }

    public void mkdir(KubernetesSandboxState state, String path) throws Exception {
        exec(state, "mkdir -p " + shellSingleQuote(path), 30);
    }

    public void rmRf(KubernetesSandboxState state, String path) throws Exception {
        try {
            exec(state, "rm -rf " + shellSingleQuote(path), 30);
        } catch (Exception e) {
            log.warn("[sandbox-k8s] rm -rf {} failed: {}", path, e.getMessage());
        }
    }

    private ContainerResource pod(KubernetesSandboxState state) {
        return client.pods()
                .inNamespace(state.getNamespace())
                .withName(state.getPodName())
                .inContainer(state.getContainerName());
    }

    private void createPod(KubernetesSandboxState state) {
        String ns = state.getNamespace();
        String podName = state.getPodName();
        Map<String, String> labels = new HashMap<>(templateOptions.getPodLabels());
        labels.put("app.kubernetes.io/name", "agentscope-sandbox");
        labels.put("agentscope.io/session", state.getSessionId());

        ContainerBuilder cb =
                new ContainerBuilder()
                        .withName(state.getContainerName())
                        .withImage(state.getImage())
                        .withCommand("sh", "-c")
                        .withArgs("while true; do sleep 3600; done");

        if (templateOptions.getCpuRequest() != null || templateOptions.getMemoryRequest() != null) {
            ResourceRequirementsBuilder rb = new ResourceRequirementsBuilder();
            Map<String, Quantity> requests = new HashMap<>();
            if (templateOptions.getCpuRequest() != null) {
                requests.put("cpu", new Quantity(templateOptions.getCpuRequest()));
            }
            if (templateOptions.getMemoryRequest() != null) {
                requests.put("memory", new Quantity(templateOptions.getMemoryRequest()));
            }
            cb.withResources(rb.withRequests(requests).build());
        }

        List<Volume> bindVolumes = new ArrayList<>();
        WorkspaceSpec ws = state.getWorkspaceSpec();
        int volIdx = 0;
        if (ws != null) {
            for (Map.Entry<String, BindMountEntry> ent :
                    WorkspaceMountSupport.topLevelBindMounts(ws).entrySet()) {
                BindMountEntry bm = ent.getValue();
                String host = WorkspaceMountSupport.normalizedHostPath(bm.getHostPath());
                if (host.isEmpty()) {
                    log.warn("[sandbox-k8s] Skipping bind mount {}: blank hostPath", ent.getKey());
                    continue;
                }
                String volName = "asbm" + volIdx++;
                Path hostPath = Path.of(host);
                String hpType = "Directory";
                try {
                    if (Files.exists(hostPath)) {
                        hpType = Files.isDirectory(hostPath) ? "Directory" : "File";
                    }
                } catch (Exception e) {
                    log.debug(
                            "[sandbox-k8s] could not stat host path {}: {}", host, e.getMessage());
                }
                bindVolumes.add(
                        new VolumeBuilder()
                                .withName(volName)
                                .withNewHostPath()
                                .withPath(host)
                                .withType(hpType)
                                .endHostPath()
                                .build());
                cb.addNewVolumeMount()
                        .withName(volName)
                        .withMountPath(
                                WorkspaceMountSupport.containerMountPath(
                                        state.getWorkspaceRoot(), ent.getKey()))
                        .withReadOnly(bm.isReadOnly())
                        .endVolumeMount();
            }
        }

        PodSpecBuilder specBuilder = new PodSpecBuilder().withRestartPolicy("Always");
        for (Volume v : bindVolumes) {
            specBuilder.addToVolumes(v);
        }
        specBuilder.addToContainers(cb.build()).withNodeSelector(templateOptions.getNodeSelector());
        if (templateOptions.getServiceAccount() != null
                && !templateOptions.getServiceAccount().isBlank()) {
            specBuilder.withServiceAccountName(templateOptions.getServiceAccount());
        }
        Pod pod =
                new PodBuilder()
                        .withNewMetadata()
                        .withName(podName)
                        .withNamespace(ns)
                        .withLabels(labels)
                        .endMetadata()
                        .withSpec(specBuilder.build())
                        .build();

        client.pods().inNamespace(ns).resource(pod).create();
    }

    static String buildPodName(String sessionId) {
        String compact = sessionId.replace("-", "");
        String suffix = compact.length() > 20 ? compact.substring(0, 20) : compact;
        String name = "as-sbx-" + suffix;
        if (name.length() > 63) {
            name = name.substring(0, 63);
        }
        return name.toLowerCase();
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static String truncate(byte[] buf) {
        int n = Math.min(buf.length, OUTPUT_TRUNCATE_BYTES);
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }
}
