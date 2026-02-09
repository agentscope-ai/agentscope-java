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
package io.agentscope.core.skill.util;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.io.IOException;

/**
 * @deprecated Use {@link ClasspathSkillRepository} instead.
 */
@Deprecated
public class JarSkillRepositoryAdapter extends ClasspathSkillRepository {

    /**
     * Creates an adapter for loading skills from resources.
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @throws IOException if initialization fails
     */
    @Deprecated
    public JarSkillRepositoryAdapter(String resourcePath) throws IOException {
        super(resourcePath);
    }

    /**
     * Creates an adapter for loading skills from resources using a specific ClassLoader.
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @param classLoader The ClassLoader to use for loading resources
     * @throws IOException if initialization fails
     */
    @Deprecated
    protected JarSkillRepositoryAdapter(String resourcePath, ClassLoader classLoader)
            throws IOException {
        super(resourcePath, classLoader);
    }
}
