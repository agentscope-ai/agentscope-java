/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.skill.repository;

import io.agentscope.core.skill.AgentSkill;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Example demonstrating how to use FileSystemSkillRepository.
 *
 * <p>This example shows:
 * <ul>
 *   <li>Creating a repository instance
 *   <li>Loading skills from the file system
 *   <li>Saving new skills with resources
 *   <li>Checking skill existence
 *   <li>Deleting skills
 * </ul>
 */
public class FileSystemSkillRepositoryExample {

    public static void main(String[] args) {
        // 1. Create a repository pointing to a skills directory
        Path skillsDir = Paths.get("/path/to/skills");
        FileSystemSkillRepository repository = new FileSystemSkillRepository(skillsDir);

        System.out.println("Repository Info: " + repository.getRepositoryInfo());
        System.out.println("Source: " + repository.getSource());

        // 2. List all available skills
        List<String> skillNames = repository.getAllSkillNames();
        System.out.println("\nAvailable skills: " + skillNames);

        // 3. Load a specific skill
        if (!skillNames.isEmpty()) {
            String skillName = skillNames.get(0);
            AgentSkill skill = repository.getSkill(skillName);
            System.out.println("\nLoaded skill: " + skill.getName());
            System.out.println("Description: " + skill.getDescription());
            System.out.println("Resources: " + skill.getResources().keySet());
        }

        // 4. Create and save a new skill with resources
        Map<String, String> resources = new HashMap<>();
        resources.put("references/api.md", "# API Documentation\n\nAPI details here...");
        resources.put("examples/example1.txt", "Example usage...");
        resources.put("scripts/setup.sh", "#!/bin/bash\necho 'Setup script'");

        AgentSkill newSkill =
                new AgentSkill(
                        "my-custom-skill",
                        "A custom skill for demonstration",
                        "This skill demonstrates how to create and save skills with resources.",
                        resources);

        // Save the skill (force=false means it won't overwrite if exists)
        boolean saved = repository.save(List.of(newSkill), false);
        System.out.println("\nSkill saved: " + saved);

        // 5. Check if skill exists
        boolean exists = repository.skillExists("my-custom-skill");
        System.out.println("Skill exists: " + exists);

        // 6. Update an existing skill (force=true to overwrite)
        AgentSkill updatedSkill =
                new AgentSkill(
                        "my-custom-skill",
                        "Updated description",
                        "Updated content with new information.",
                        null);

        boolean updated = repository.save(List.of(updatedSkill), true);
        System.out.println("Skill updated: " + updated);

        // 7. Load all skills
        List<AgentSkill> allSkills = repository.getAllSkills();
        System.out.println("\nTotal skills loaded: " + allSkills.size());
        for (AgentSkill skill : allSkills) {
            System.out.println("  - " + skill.getName() + ": " + skill.getDescription());
        }

        // 8. Delete a skill
        boolean deleted = repository.delete("my-custom-skill");
        System.out.println("\nSkill deleted: " + deleted);

        // 9. Verify deletion
        exists = repository.skillExists("my-custom-skill");
        System.out.println("Skill exists after deletion: " + exists);
    }
}
