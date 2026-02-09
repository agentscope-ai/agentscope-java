package io.agentscope.core.nacos.prompt;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NacosPromptListener {

    private static final Logger log = LoggerFactory.getLogger(NacosPromptListener.class);

    private static final String PROMPT_KEY_SUFFIX = ".json";
    private static final String FIELD_TEMPLATE = "template";
    private static final String FIELD_PROMPT_KEY = "promptKey";
    private static final String DEFAULT_GROUP = "nacos-ai-prompt";

    private ConfigService configService;

    private final Map<String, String> prompts;

    public NacosPromptListener(ConfigService configService) {
        this.configService = configService;
        this.prompts = new ConcurrentHashMap<>(10);
    }

    public String getPrompt(String promptKey) throws NacosException {
        return getPrompt(promptKey, null);
    }

    public String getPrompt(String promptKey, Map<String, String> args) throws NacosException {
        return getPrompt(promptKey, args, null);
    }

    /**
     * Get prompt template with optional default value
     * @param promptKey the prompt key
     * @param args the template variables for rendering
     * @param defaultValue the default value to use if prompt not found in Nacos
     * @return rendered prompt string or default value
     * @throws NacosException if Nacos service error occurs
     */
    public String getPrompt(String promptKey, Map<String, String> args, String defaultValue)
            throws NacosException {
        // Use computeIfAbsent to ensure atomic check-and-load operation
        String template =
                prompts.computeIfAbsent(
                        promptKey,
                        key -> {
                            try {
                                return getPromptFromNacosAndListener(key);
                            } catch (NacosException e) {
                                log.error("Failed to load prompt from Nacos for key: {}", key, e);
                                return "";
                            }
                        });

        // Use default value if template is empty
        if (template == null || template.isEmpty()) {
            if (defaultValue != null) {
                log.info("Using default value for prompt key: {}", promptKey);
                template = defaultValue;
            } else {
                return "";
            }
        }

        // Render template with args if provided
        if (args != null && !args.isEmpty()) {
            return renderTemplate(template, args);
        }
        return template;
    }

    private String getPromptFromNacosAndListener(String promptKey) throws NacosException {
        String promptDataId = promptKey + PROMPT_KEY_SUFFIX;
        String promptStr =
                configService.getConfigAndSignListener(
                        promptDataId, DEFAULT_GROUP, 5000, this.promptListener);

        JsonNode node = JacksonUtils.toObj(promptStr, JsonNode.class);
        if (node == null || !node.has(FIELD_PROMPT_KEY) || !node.has(FIELD_TEMPLATE)) {
            log.warn("Invalid prompt config for key: {}, missing required fields", promptKey);
            return "";
        }

        JsonNode templateNode = node.get(FIELD_TEMPLATE);
        if (templateNode == null || templateNode.isNull()) {
            log.warn("Template field is null for prompt key: {}", promptKey);
            return "";
        }

        String promptTemplate = templateNode.asText();
        log.info("Loaded prompt template for key: {}", promptKey);
        return promptTemplate;
    }

    /**
     * Render template by replacing {{variableName}} with values from args
     * @param template the template string with {{}} placeholders
     * @param args the variable map for replacement
     * @return rendered string
     */
    private String renderTemplate(String template, Map<String, String> args) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        String result = template;
        for (Map.Entry<String, String> entry : args.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    private final Listener promptListener =
            new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    try {
                        JsonNode node = JacksonUtils.toObj(configInfo, JsonNode.class);
                        if (node == null || !node.has(FIELD_PROMPT_KEY)) {
                            log.warn("Received invalid prompt config, missing promptKey field");
                            return;
                        }

                        JsonNode promptKeyNode = node.get(FIELD_PROMPT_KEY);
                        if (promptKeyNode == null || promptKeyNode.isNull()) {
                            log.warn("PromptKey field is null in configuration");
                            return;
                        }

                        String promptKey = promptKeyNode.asText();

                        if (!node.has(FIELD_TEMPLATE)) {
                            log.warn(
                                    "No template field found in configuration for key: {}",
                                    promptKey);
                            return;
                        }

                        JsonNode templateNode = node.get(FIELD_TEMPLATE);
                        if (templateNode == null || templateNode.isNull()) {
                            log.warn(
                                    "Template field is null in configuration for key: {}",
                                    promptKey);
                            return;
                        }

                        String newTemplate = templateNode.asText();
                        prompts.put(promptKey, newTemplate);
                        log.info("Prompt template updated for key: {}", promptKey);

                    } catch (Exception e) {
                        log.error(
                                "Failed to parse prompt configuration from config: {}",
                                configInfo,
                                e);
                    }
                }
            };
}
