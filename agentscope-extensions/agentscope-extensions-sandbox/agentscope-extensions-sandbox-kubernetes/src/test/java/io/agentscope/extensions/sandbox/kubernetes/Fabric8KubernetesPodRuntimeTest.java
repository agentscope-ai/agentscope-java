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
package io.agentscope.extensions.sandbox.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class Fabric8KubernetesPodRuntimeTest {

    @Mock KubernetesClient kc;

    @SuppressWarnings("unchecked")
    MixedOperation<Pod, PodList, PodResource> pods =
            (MixedOperation<Pod, PodList, PodResource>) mock(MixedOperation.class);

    @SuppressWarnings("unchecked")
    NonNamespaceOperation<Pod, PodList, PodResource> inNamespace =
            (NonNamespaceOperation<Pod, PodList, PodResource>) mock(NonNamespaceOperation.class);

    @Mock PodResource podResource;

    KubernetesSandboxClientOptions opts;
    Fabric8KubernetesPodRuntime runtime;

    @BeforeEach
    void setUp() {
        opts = new KubernetesSandboxClientOptions();
        runtime = new Fabric8KubernetesPodRuntime(kc, opts);

        doReturn(pods).when(kc).pods();
        doReturn(inNamespace).when(pods).inNamespace(anyString());
        doReturn(podResource).when(inNamespace).resource(any(Pod.class));
        doReturn(mock(Pod.class)).when(podResource).create();
        // waitUntilReady stub
        doReturn(podResource).when(inNamespace).withName(anyString());
        doReturn(mock(Pod.class)).when(podResource).waitUntilReady(any(long.class), any());
    }

    @Test
    void ensurePodReady_injectsEnvironmentVariablesFromWorkspaceSpec() throws Exception {
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setEnvironment(Map.of("MY_KEY", "my_val", "ANOTHER", "123"));

        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("test-session-id");
        state.setNamespace("default");
        state.setContainerName("workspace");
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(ws);

        ArgumentCaptor<Pod> podCaptor = ArgumentCaptor.forClass(Pod.class);
        doReturn(podResource).when(inNamespace).resource(podCaptor.capture());

        runtime.ensurePodReady(state);

        Pod created = podCaptor.getValue();
        List<EnvVar> envVars = created.getSpec().getContainers().get(0).getEnv();
        Map<String, String> actual =
                envVars.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        EnvVar::getName, EnvVar::getValue));

        assertEquals("my_val", actual.get("MY_KEY"));
        assertEquals("123", actual.get("ANOTHER"));
    }

    @Test
    void ensurePodReady_noEnvVars_podCreatedWithoutEnv() throws Exception {
        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("test-session-id-2");
        state.setNamespace("default");
        state.setContainerName("workspace");
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/workspace");

        ArgumentCaptor<Pod> podCaptor = ArgumentCaptor.forClass(Pod.class);
        doReturn(podResource).when(inNamespace).resource(podCaptor.capture());

        runtime.ensurePodReady(state);

        Pod created = podCaptor.getValue();
        List<EnvVar> envVars = created.getSpec().getContainers().get(0).getEnv();
        assertTrue(envVars == null || envVars.isEmpty());
    }

    @Test
    void buildPodName_truncatesLongSessionId() {
        String longId = "12345678-1234-1234-1234-123456789012";
        String name = Fabric8KubernetesPodRuntime.buildPodName(longId);
        assertTrue(name.length() <= 63);
        assertTrue(name.startsWith("as-sbx-"));
    }

    // -------------------------------------------------------------------------
    //  hydrateWithArchive / tarWorkspaceIn
    // -------------------------------------------------------------------------

    private KubernetesSandboxState createK8sState() {
        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("test-session");
        state.setNamespace("default");
        state.setPodName("test-pod");
        state.setContainerName("workspace");
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/opt");
        return state;
    }

    @Test
    void tarWorkspaceIn_success_exitCodeZero() throws Exception {
        ContainerResource container = mock(ContainerResource.class);
        doReturn(container).when(podResource).inContainer(anyString());

        // Hydrate chain: container.redirectingInput().writingError().exec()
        ContainerResource redirecting = mock(ContainerResource.class);
        ContainerResource writingErr = mock(ContainerResource.class);
        ExecWatch uploadWatch = mock(ExecWatch.class);
        doReturn(redirecting).when(container).redirectingInput();
        doReturn(writingErr).when(redirecting).writingError(any(ByteArrayOutputStream.class));
        doReturn(uploadWatch).when(writingErr).exec(anyString(), anyString(), anyString());
        doReturn(mock(OutputStream.class)).when(uploadWatch).getInput();
        doReturn(CompletableFuture.completedFuture(0)).when(uploadWatch).exitCode();

        // Exec chain for mkdir / tar / rm
        ExecWatch execWatch = mock(ExecWatch.class);
        ContainerResource writingOut = mock(ContainerResource.class);
        ContainerResource writingErrExec = mock(ContainerResource.class);
        doReturn(writingOut).when(container).writingOutput(any(ByteArrayOutputStream.class));
        doReturn(writingErrExec).when(writingOut).writingError(any(ByteArrayOutputStream.class));
        doReturn(execWatch).when(writingErrExec).exec(anyString(), anyString(), anyString());
        doReturn(CompletableFuture.completedFuture(0)).when(execWatch).exitCode();

        runtime.tarWorkspaceIn(createK8sState(), new ByteArrayInputStream("data".getBytes()));
    }

    @Test
    void tarWorkspaceIn_uploadExitNull_isTreatedAsSuccess() throws Exception {
        ContainerResource container = mock(ContainerResource.class);
        doReturn(container).when(podResource).inContainer(anyString());

        ContainerResource redirecting = mock(ContainerResource.class);
        ContainerResource writingErr = mock(ContainerResource.class);
        ExecWatch uploadWatch = mock(ExecWatch.class);
        doReturn(redirecting).when(container).redirectingInput();
        doReturn(writingErr).when(redirecting).writingError(any(ByteArrayOutputStream.class));
        doReturn(uploadWatch).when(writingErr).exec(anyString(), anyString(), anyString());
        doReturn(mock(OutputStream.class)).when(uploadWatch).getInput();
        doReturn(CompletableFuture.completedFuture(null)).when(uploadWatch).exitCode();

        ExecWatch execWatch = mock(ExecWatch.class);
        ContainerResource writingOut = mock(ContainerResource.class);
        ContainerResource writingErrExec = mock(ContainerResource.class);
        doReturn(writingOut).when(container).writingOutput(any(ByteArrayOutputStream.class));
        doReturn(writingErrExec).when(writingOut).writingError(any(ByteArrayOutputStream.class));
        doReturn(execWatch).when(writingErrExec).exec(anyString(), anyString(), anyString());
        doReturn(CompletableFuture.completedFuture(0)).when(execWatch).exitCode();

        // Null exit code is expected when Websocket closes before stream 3 status arrives.
        // The upload itself completed successfully — null should be treated as success.
        runtime.tarWorkspaceIn(createK8sState(), new ByteArrayInputStream("data".getBytes()));
    }

    @Test
    void tarWorkspaceIn_uploadExitNonZero_throwsException() {
        ContainerResource container = mock(ContainerResource.class);
        doReturn(container).when(podResource).inContainer(anyString());

        ContainerResource redirecting = mock(ContainerResource.class);
        ContainerResource writingErr = mock(ContainerResource.class);
        ExecWatch uploadWatch = mock(ExecWatch.class);
        doReturn(redirecting).when(container).redirectingInput();
        doReturn(writingErr).when(redirecting).writingError(any(ByteArrayOutputStream.class));
        doReturn(uploadWatch).when(writingErr).exec(anyString(), anyString(), anyString());
        doReturn(mock(OutputStream.class)).when(uploadWatch).getInput();
        doReturn(CompletableFuture.completedFuture(1)).when(uploadWatch).exitCode();

        ExecWatch execWatch = mock(ExecWatch.class);
        ContainerResource writingOut = mock(ContainerResource.class);
        ContainerResource writingErrExec = mock(ContainerResource.class);
        doReturn(writingOut).when(container).writingOutput(any(ByteArrayOutputStream.class));
        doReturn(writingErrExec).when(writingOut).writingError(any(ByteArrayOutputStream.class));
        doReturn(execWatch).when(writingErrExec).exec(anyString(), anyString(), anyString());
        doReturn(CompletableFuture.completedFuture(0)).when(execWatch).exitCode();

        assertThrows(
                SandboxException.SandboxRuntimeException.class,
                () ->
                        runtime.tarWorkspaceIn(
                                createK8sState(), new ByteArrayInputStream("data".getBytes())));
    }
}
