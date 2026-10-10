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
package io.agentscope.builder.web.persistence.jpa;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class JpaUserStoreSeedTest {
    @Test
    void releaseDeploymentDoesNotCreateLegacyDemoAccounts() {
        UserEntityRepository repository = mock(UserEntityRepository.class);
        new JpaUserStore(repository, false).seedDefaultAdmin();
        verifyNoInteractions(repository);
    }

    @Test
    void developmentKeepsExistingAdminUnchanged() {
        UserEntityRepository repository = mock(UserEntityRepository.class);
        when(repository.existsById("admin")).thenReturn(true);
        new JpaUserStore(repository).seedDefaultAdmin();
        verify(repository, never()).save(any());
    }

    @Test
    void developmentCanStillSeedAnEmptyStore() {
        UserEntityRepository repository = mock(UserEntityRepository.class);
        new JpaUserStore(repository).seedDefaultAdmin();
        verify(repository).save(any(UserEntity.class));
    }
}
