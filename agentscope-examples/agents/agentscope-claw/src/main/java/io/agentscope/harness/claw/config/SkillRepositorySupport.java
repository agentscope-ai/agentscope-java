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
package io.agentscope.harness.claw.config;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds an {@link AgentSkillRepository} from {@link SkillRepositoryConfigEntry}. */
public final class SkillRepositorySupport {

    private static final Logger log = LoggerFactory.getLogger(SkillRepositorySupport.class);

    private static final String TYPE_FILESYSTEM = "filesystem";
    private static final String TYPE_GIT = "git";

    private SkillRepositorySupport() {}

    /**
     * @param cwd   bootstrap working directory (used to resolve relative paths)
     * @param entry non-null config entry
     * @return repository instance, or {@code null} if configuration is invalid or optional Git
     *     types are not on the classpath
     */
    public static AgentSkillRepository create(Path cwd, SkillRepositoryConfigEntry entry) {
        if (entry == null || entry.getType() == null || entry.getType().isBlank()) {
            return null;
        }
        String kind = entry.getType().trim().toLowerCase();
        return switch (kind) {
            case TYPE_FILESYSTEM -> createFilesystem(cwd, entry);
            case TYPE_GIT -> createGit(cwd, entry);
            default -> {
                log.warn(
                        "Unknown skillRepository type '{}'; expected '{}' or '{}'",
                        entry.getType(),
                        TYPE_FILESYSTEM,
                        TYPE_GIT);
                yield null;
            }
        };
    }

    private static AgentSkillRepository createFilesystem(
            Path cwd, SkillRepositoryConfigEntry entry) {
        String pathStr = entry.getPath();
        if (pathStr == null || pathStr.isBlank()) {
            log.warn("skillRepository type filesystem requires non-blank 'path'");
            return null;
        }
        Path dir = cwd.resolve(pathStr).normalize();
        if (!Files.isDirectory(dir)) {
            log.warn(
                    "skillRepository path '{}' resolved to '{}' which is not a directory",
                    pathStr,
                    dir);
            return null;
        }
        return new FileSystemSkillRepository(dir);
    }

    private static AgentSkillRepository createGit(Path cwd, SkillRepositoryConfigEntry entry) {
        String remote = entry.getRemoteUrl();
        if (remote == null || remote.isBlank()) {
            log.warn("skillRepository type git requires non-blank 'remoteUrl'");
            return null;
        }
        Path local =
                entry.getLocalPath() != null && !entry.getLocalPath().isBlank()
                        ? cwd.resolve(entry.getLocalPath()).normalize()
                        : null;
        boolean auto = entry.getAutoSync() == null || Boolean.TRUE.equals(entry.getAutoSync());
        try {
            Class<?> gitRepo =
                    Class.forName("io.agentscope.core.skill.repository.GitSkillRepository");
            var ctor =
                    gitRepo.getConstructor(
                            String.class, String.class, Path.class, String.class, boolean.class);
            return (AgentSkillRepository)
                    ctor.newInstance(remote, entry.getBranch(), local, entry.getSource(), auto);
        } catch (ClassNotFoundException e) {
            log.warn(
                    "GitSkillRepository not on classpath; add dependency"
                            + " agentscope-extensions-skill-git-repository");
            return null;
        } catch (ReflectiveOperationException e) {
            log.warn("Failed to construct GitSkillRepository: {}", e.getMessage());
            return null;
        }
    }
}
