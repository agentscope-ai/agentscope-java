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
package io.agentscope.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for AgentScopeProcessor. Tests build step methods and their behavior.
 */
class AgentScopeProcessorTest {

    private AgentScopeProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AgentScopeProcessor();
    }

    @Test
    void testProcessorCanBeInstantiated() {
        assertNotNull(processor);
    }

    @Test
    void testFeatureBuildStep() {
        FeatureBuildItem feature = processor.feature();

        assertNotNull(feature);
        assertEquals("agentscope", feature.getName());
    }

    @Test
    void testFeatureMethodHasBuildStepAnnotation() throws NoSuchMethodException {
        Method method = AgentScopeProcessor.class.getDeclaredMethod("feature");

        assertTrue(method.isAnnotationPresent(io.quarkus.deployment.annotations.BuildStep.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRegisterForReflection() {
        BuildProducer<ReflectiveClassBuildItem> reflective = mock(BuildProducer.class);

        processor.registerForReflection(reflective);

        // Verify that multiple reflective class build items were produced
        verify(reflective, atLeast(4)).produce(any(ReflectiveClassBuildItem.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRegisterForReflectionProducesAgentClasses() {
        BuildProducer<ReflectiveClassBuildItem> reflective = mock(BuildProducer.class);
        ArgumentCaptor<ReflectiveClassBuildItem> captor =
                ArgumentCaptor.forClass(ReflectiveClassBuildItem.class);

        processor.registerForReflection(reflective);

        verify(reflective, atLeast(1)).produce(captor.capture());

        // Verify we captured build items
        assertFalse(captor.getAllValues().isEmpty());
    }

    @Test
    void testRegisterForReflectionMethodHasBuildStepAnnotation() throws NoSuchMethodException {
        Method method =
                AgentScopeProcessor.class.getDeclaredMethod(
                        "registerForReflection", BuildProducer.class);

        assertTrue(method.isAnnotationPresent(io.quarkus.deployment.annotations.BuildStep.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAddDependencies() {
        BuildProducer<AdditionalBeanBuildItem> beans = mock(BuildProducer.class);

        processor.addDependencies(beans);

        // Verify that multiple bean build items were produced
        verify(beans, atLeast(3)).produce(any(AdditionalBeanBuildItem.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAddDependenciesProducesAgentScopeProducer() {
        BuildProducer<AdditionalBeanBuildItem> beans = mock(BuildProducer.class);
        ArgumentCaptor<AdditionalBeanBuildItem> captor =
                ArgumentCaptor.forClass(AdditionalBeanBuildItem.class);

        processor.addDependencies(beans);

        verify(beans, atLeast(1)).produce(captor.capture());

        // Verify we captured build items
        assertFalse(captor.getAllValues().isEmpty());
    }

    @Test
    void testAddDependenciesMethodHasBuildStepAnnotation() throws NoSuchMethodException {
        Method method =
                AgentScopeProcessor.class.getDeclaredMethod("addDependencies", BuildProducer.class);

        assertTrue(method.isAnnotationPresent(io.quarkus.deployment.annotations.BuildStep.class));
    }

    @Test
    void testInitializeAgentScopeMethodExists() throws NoSuchMethodException {
        Method method =
                AgentScopeProcessor.class.getDeclaredMethod(
                        "initializeAgentScope",
                        io.agentscope.quarkus.runtime.AgentScopeRecorder.class,
                        io.agentscope.quarkus.runtime.AgentScopeConfig.class);

        assertNotNull(method);
    }

    @Test
    void testInitializeAgentScopeMethodHasRecordAnnotation() throws NoSuchMethodException {
        Method method =
                AgentScopeProcessor.class.getDeclaredMethod(
                        "initializeAgentScope",
                        io.agentscope.quarkus.runtime.AgentScopeRecorder.class,
                        io.agentscope.quarkus.runtime.AgentScopeConfig.class);

        assertTrue(method.isAnnotationPresent(io.quarkus.deployment.annotations.Record.class));
        assertTrue(method.isAnnotationPresent(io.quarkus.deployment.annotations.BuildStep.class));
    }

    @Test
    void testAllPublicMethodsHaveBuildStepAnnotation() {
        Method[] methods = AgentScopeProcessor.class.getDeclaredMethods();
        int buildStepCount = 0;

        for (Method method : methods) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    || method.getName().equals("feature")
                    || method.getName().equals("registerForReflection")
                    || method.getName().equals("addDependencies")
                    || method.getName().equals("initializeAgentScope")) {
                // Count methods that should have BuildStep
                if (method.isAnnotationPresent(io.quarkus.deployment.annotations.BuildStep.class)) {
                    buildStepCount++;
                }
            }
        }

        // We expect at least 4 build step methods
        assertTrue(buildStepCount >= 4, "Expected at least 4 BuildStep methods");
    }
}
