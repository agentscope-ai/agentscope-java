package io.agentscope.examples.quickstart.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.Set;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.wanqing.WanQingFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.WanQingChatModel;
import io.agentscope.core.model.WanQingHttpClient;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.examples.quickstart.ExampleUtils;

/**
 * AgentSkillTest - Test agent with skills.
 *
 * @author wangxin <wangxin53@kuaishou.com>
 * Created on 2026-02-10
 */
public class AgentSkillTest {
    private static final String RESOURCES_DIR =
            "agentscope-examples/quickstart/src/main/resources/skills";
    private static final String OUTPUT_DIR = "agentscope-examples/quickstart/target/skill-output";

    public static void main(String[] args) throws IOException {
        System.out.println("=== Agent Skill Test ===\n");

        Toolkit toolkit = new Toolkit();
        SkillBox skillBox = new SkillBox(toolkit);

        // Load skills from directory
        Path resourcesDir = resolvePath(RESOURCES_DIR);
        System.out.println("Looking for skills in: " + resourcesDir);
        System.out.println("Directory exists: " + Files.exists(resourcesDir));

        if (Files.exists(resourcesDir)) {
            FileSystemSkillRepository repository =
                    new FileSystemSkillRepository(resourcesDir, false);
            int skillCount = 0;
            for (AgentSkill skill : repository.getAllSkills()) {
                System.out.println("Registering Skill: " + skill.getName());
                skillBox.registration().skill(skill).apply();
                skillCount++;
            }
            System.out.println("Total skills loaded: " + skillCount);
        } else {
            System.out.println("Warning: Skills directory does not exist!");
        }

        Path outputDir = resolvePath(OUTPUT_DIR);
        System.out.println("Output directory: " + outputDir);
        System.out.println("Output directory exists: " + Files.exists(outputDir));

        // Create output directory if not exists
        if (!Files.exists(outputDir)) {
            try {
                Files.createDirectories(outputDir);
                System.out.println("Created output directory: " + outputDir);
            } catch (Exception e) {
                System.err.println("Failed to create output directory: " + e.getMessage());
            }
        }

        Scanner scanner = new Scanner(System.in);
        ShellCommandTool shellCommandTool =
                new ShellCommandTool(
                        Set.of("python", "ls", "cat"),
                        cmd -> {
                            System.out.println("Enter y/n to approve or deny execution:");
                            System.out.println(cmd);
                            System.out.println();
                            String response = scanner.nextLine();
                            return response.equalsIgnoreCase("y");
                        });
        skillBox.codeExecution()
                .workDir(outputDir.toString())
                .withShell(shellCommandTool)
                .withRead()
                .withWrite()
                .enable();

        System.out.println("\nInitializing WanQing model...");

        // Get API key from environment or use default
        String apiKey =
                System.getenv()
                        .getOrDefault("WANQING_API_KEY", "sibtgmtok2rptkkxgx08e493fjgqzsq68ka0");
        String modelName =
                System.getenv().getOrDefault("WANQING_MODEL", "ep-auff5e-1768360662423998148");

        System.out.println("API Key: " + maskApiKey(apiKey));
        System.out.println("Model: " + modelName);
        System.out.println("Endpoint: " + WanQingHttpClient.GATEWAY_ENDPOINT);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("skillTest")
                        .model(
                                WanQingChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(modelName)
                                        .endpointPath(WanQingHttpClient.GATEWAY_ENDPOINT)
                                        .formatter(new WanQingFormatter())
                                        .stream(true)
                                        .build())
                        .toolkit(toolkit)
                        .skillBox(skillBox)
                        .memory(new InMemoryMemory())
                        .build();

        System.out.println("\nAgent initialized successfully!");
        System.out.println("Starting chat...\n");

        ExampleUtils.startChat(agent);

        scanner.close();
    }

    private static Path resolvePath(String relativePath) {
        return Paths.get(relativePath).toAbsolutePath().normalize();
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
