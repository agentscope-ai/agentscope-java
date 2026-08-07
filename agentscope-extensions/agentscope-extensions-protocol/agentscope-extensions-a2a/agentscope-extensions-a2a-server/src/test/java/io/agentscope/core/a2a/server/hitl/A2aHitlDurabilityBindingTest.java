/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;

class A2aHitlDurabilityBindingTest {

    @Test
    void exposesOneCoherentDurabilityUnit() {
        TaskStore taskStore = org.mockito.Mockito.mock(TaskStore.class);
        HitlResumeCoordinator coordinator = org.mockito.Mockito.mock(HitlResumeCoordinator.class);
        HitlSessionLease lease = org.mockito.Mockito.mock(HitlSessionLease.class);
        A2aHitlDurabilityBinding binding = new TestBinding(taskStore, coordinator, lease);

        assertSame(taskStore, binding.taskStore());
        assertSame(coordinator, binding.resumeCoordinator());
        assertSame(lease, binding.sessionLease());
    }

    @Test
    void verificationDescriptorRejectsBlankIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HitlDurabilityVerification(" ", "redis-main"));
        assertThrows(
                IllegalArgumentException.class, () -> new HitlDurabilityVerification("redis", " "));
    }

    @Test
    void reusableContractKitChecksProviderAndGenericComponents() {
        HitlResumeCoordinator coordinator = mock(HitlResumeCoordinator.class);
        HitlSessionLease lease = mock(HitlSessionLease.class);
        A2aHitlDurabilityBinding binding = mock(A2aHitlDurabilityBinding.class);
        HitlDurabilityVerification verification =
                new HitlDurabilityVerification("custom", "shared-store");
        when(binding.taskStore()).thenReturn(new InMemoryTaskStore());
        when(binding.resumeCoordinator()).thenReturn(coordinator);
        when(binding.sessionLease()).thenReturn(lease);
        when(binding.verify()).thenReturn(verification);
        when(coordinator.durabilityCapability()).thenReturn(HitlDurabilityCapability.DURABLE);
        when(lease.durabilityCapability()).thenReturn(HitlDurabilityCapability.DURABLE);

        assertSame(verification, A2aHitlDurabilityBindingContract.verify(binding));
        verify(binding).verify();
    }

    @Test
    void reusableContractKitRejectsLocalProtocolComponents() {
        A2aHitlDurabilityBinding binding = mock(A2aHitlDurabilityBinding.class);
        when(binding.taskStore()).thenReturn(new InMemoryTaskStore());
        when(binding.resumeCoordinator()).thenReturn(new LocalHitlResumeCoordinator());
        when(binding.sessionLease()).thenReturn(new LocalHitlSessionLease());

        assertThrows(
                IllegalStateException.class,
                () -> A2aHitlDurabilityBindingContract.verify(binding));
    }

    private record TestBinding(
            TaskStore taskStore,
            HitlResumeCoordinator resumeCoordinator,
            HitlSessionLease sessionLease)
            implements A2aHitlDurabilityBinding {

        @Override
        public HitlDurabilityVerification verify() {
            return new HitlDurabilityVerification("test", "test-store");
        }
    }
}
