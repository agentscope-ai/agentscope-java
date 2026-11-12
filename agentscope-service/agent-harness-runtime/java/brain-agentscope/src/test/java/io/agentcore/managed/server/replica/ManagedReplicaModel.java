/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.replica;

import io.agentcore.managed.server.AgentHarnessRuntimeApp;
import io.agentcore.managed.server.web.catalog.HarnessAgentBuildService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Flux;

/** Explicit deployment fixture loaded from test classes, never packaged in production services. */
public final class ManagedReplicaModel
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public static void main(String[] args) {
        var application = new SpringApplication(AgentHarnessRuntimeApp.class);
        application.addInitializers(new ManagedReplicaModel());
        application.run(args);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getBeanFactory().registerSingleton("managedReplicaModel", new FixtureModel());
        // The fixture explicitly supplies a schema-only tool. No production tool registration or
        // credentials are changed; this exercises the native external-result interaction path.
        context.getBeanFactory()
                .addBeanPostProcessor(
                        new BeanPostProcessor() {
                            @Override
                            public Object postProcessAfterInitialization(Object bean, String name) {
                                if (!(bean instanceof HarnessAgentBuildService)) return bean;
                                var proxy = new ProxyFactory(bean);
                                proxy.setProxyTargetClass(true);
                                proxy.addAdvice(
                                        (MethodInterceptor)
                                                invocation -> {
                                                    Object result = invocation.proceed();
                                                    if (result instanceof HarnessAgent agent
                                                            && invocation
                                                                    .getMethod()
                                                                    .getName()
                                                                    .equals("getOrBuildAgent")) {
                                                        synchronized (agent.getToolkit()) {
                                                            agent.getToolkit()
                                                                    .registerSchema(
                                                                            ToolSchema.builder()
                                                                                    .name(
                                                                                            "ask_user")
                                                                                    .description(
                                                                                            "Explicit"
                                                                                                + " fixture"
                                                                                                + " external"
                                                                                                + " answer")
                                                                                    .parameters(
                                                                                            Map.of(
                                                                                                    "type",
                                                                                                    "object",
                                                                                                    "properties",
                                                                                                    Map
                                                                                                            .of(
                                                                                                                    "question",
                                                                                                                    Map
                                                                                                                            .of(
                                                                                                                                    "type",
                                                                                                                                    "string"))))
                                                                                    .build());
                                                        }
                                                    }
                                                    return result;
                                                });
                                return proxy.getProxy();
                            }
                        });
    }

    private static final class FixtureModel implements Model {
        @Override
        public String getModelName() {
            return "managed-replica-deterministic-fixture";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (!messages.isEmpty()
                    && messages.get(0).getTextContent().contains("memory extraction assistant"))
                return text("- Preserve confirmed tool outcomes.");
            var task = Pattern.compile("fixture:task:([0-9]{1,3}):([a-z0-9-]{1,40})");
            for (var message : messages) {
                var match = task.matcher(message.getTextContent());
                if (match.find())
                    return task(messages, tools, Integer.parseInt(match.group(1)), match.group(2));
            }
            int request = -1;
            String marker = "";
            for (int index = 0; index < messages.size(); index++) {
                String value = messages.get(index).getTextContent();
                if (value.startsWith("fixture:")) {
                    request = index;
                    marker = value;
                }
            }
            if (request < 0)
                return Flux.error(new IllegalArgumentException("Fixture requires fixture: input"));
            String controls =
                    messages.stream()
                            .map(Msg::getTextContent)
                            .filter(value -> value.startsWith("control:"))
                            .distinct()
                            .reduce((a, b) -> a + "|" + b)
                            .map(value -> " controls=" + value)
                            .orElse("");
            if (marker.startsWith("fixture:echo:"))
                return text("harness-runtime-" + marker.substring(13) + controls);
            var artifact = Pattern.compile("fixture:artifact:([a-z0-9-]{1,40})").matcher(marker);
            if (artifact.matches()) return artifact(messages, tools, request, artifact.group(1));
            var checkpoint =
                    Pattern.compile(
                                    "fixture:checkpoint:(write|read):([a-z0-9-]{1,16}):([a-z0-9-]{1,40})")
                            .matcher(marker);
            if (checkpoint.matches())
                return checkpoint(
                        messages,
                        request,
                        checkpoint.group(1),
                        checkpoint.group(2),
                        checkpoint.group(3));
            var external =
                    Pattern.compile("fixture:(external|external-error):([a-z0-9-]{1,40})")
                            .matcher(marker);
            if (external.matches()) {
                boolean error = external.group(1).equals("external-error");
                String label = external.group(2);
                String id = "replica-external-" + label;
                for (int index = request + 1; index < messages.size(); index++) {
                    for (var result : messages.get(index).getContentBlocks(ToolResultBlock.class)) {
                        if (!id.equals(result.getId())) continue;
                        boolean verified =
                                result.getOutput().stream()
                                        .filter(TextBlock.class::isInstance)
                                        .map(TextBlock.class::cast)
                                        .anyMatch(
                                                block ->
                                                        block.getText()
                                                                .equals("external-" + label));
                        if (!verified
                                || result.getState()
                                        != (error
                                                ? ToolResultState.ERROR
                                                : ToolResultState.SUCCESS))
                            return Flux.error(
                                    new IllegalStateException(
                                            "Unexpected external fixture result"));
                        return text("harness-runtime-confirmed-" + external.group(1) + "-" + label);
                    }
                }
                if (tools.stream().noneMatch(tool -> tool.getName().equals("ask_user")))
                    return Flux.error(
                            new IllegalStateException(
                                    "Explicit external fixture schema is missing"));
                var input = Map.<String, Object>of("question", "external-" + label);
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id(id)
                                                        .name("ask_user")
                                                        .input(input)
                                                        .content(
                                                                JsonUtils.getJsonCodec()
                                                                        .toJson(input))
                                                        .build()))
                                .build());
            }
            var memory =
                    Pattern.compile(
                                    "fixture:(memory|memory-denied):([0-9]{1,3}):([a-zA-Z0-9_-]{1,64}):([a-z0-9-]{1,40})")
                            .matcher(marker);
            if (memory.matches())
                return memory(
                        messages,
                        tools,
                        Integer.parseInt(memory.group(2)),
                        memory.group(3),
                        memory.group(4),
                        memory.group(1).equals("memory-denied"));
            var delegation =
                    Pattern.compile(
                                    "fixture:(delegate|delegate-send):([0-9]{1,3}):([a-z0-9-]{1,40})")
                            .matcher(marker);
            if (delegation.matches())
                return delegate(
                        messages,
                        Integer.parseInt(delegation.group(2)),
                        delegation.group(3),
                        delegation.group(1).equals("delegate-send"));
            if (!marker.matches("fixture:(execute|stream):[0-9]{1,3}:[a-z0-9-]{1,40}"))
                return Flux.error(new IllegalArgumentException("Invalid fixture command"));
            String[] parts = marker.split(":");
            int seconds = Integer.parseInt(parts[2]);
            if (seconds > 240)
                return Flux.error(
                        new IllegalArgumentException("Fixture tool limit is 240 seconds"));
            String label = parts[3];
            for (int index = request + 1; index < messages.size(); index++) {
                for (var result : messages.get(index).getContentBlocks(ToolResultBlock.class)) {
                    boolean confirmed =
                            result.getOutput().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .anyMatch(
                                            block ->
                                                    block.getText()
                                                            .contains("tool-worker-" + label));
                    if (confirmed)
                        return text("harness-runtime-confirmed-tool-worker-" + label + controls);
                    return Flux.error(
                            new IllegalStateException(
                                    "Fixture received an unconfirmed tool result: "
                                            + JsonUtils.getJsonCodec().toJson(result)));
                }
            }
            String command =
                    "printf 'start-"
                            + label
                            + "\\n' >> side-effects.txt; sleep "
                            + seconds
                            + "; printf 'tool-worker-"
                            + label
                            + "\\n'; printf 'done-"
                            + label
                            + "\\n' >> side-effects.txt";
            if (parts[1].equals("stream")) {
                command =
                        "printf 'start-"
                                + label
                                + "\\n' >> side-effects.txt; printf 'begin-"
                                + label
                                + " 世界\\n'; sleep "
                                + (seconds / 2)
                                + "; printf 'middle-"
                                + label
                                + "\\n'; sleep "
                                + (seconds - seconds / 2)
                                + "; printf 'tool-worker-"
                                + label
                                + "\\n'; printf 'done-"
                                + label
                                + "\\n' >> side-effects.txt";
            }
            var arguments = Map.<String, Object>of("command", command, "timeout", seconds + 30);
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("replica-execute-" + label)
                                                    .name("execute")
                                                    .input(arguments)
                                                    .content(
                                                            JsonUtils.getJsonCodec()
                                                                    .toJson(arguments))
                                                    .build()))
                            .build());
        }

        private Flux<ChatResponse> artifact(
                List<Msg> messages, List<ToolSchema> tools, int request, String label) {
            String create = "artifact-create-" + label;
            String publish = "artifact-publish-" + label;
            String change = "artifact-change-" + label;
            var completed = new HashMap<String, ToolResultBlock>();
            for (var message : messages.subList(request + 1, messages.size())) {
                for (var result : message.getContentBlocks(ToolResultBlock.class)) {
                    if (!List.of(create, publish, change).contains(result.getId())) continue;
                    if (result.getState() != ToolResultState.SUCCESS)
                        return Flux.error(
                                new IllegalStateException(
                                        "Artifact fixture tool failed: " + output(result)));
                    completed.put(result.getId(), result);
                }
            }
            if (!completed.containsKey(create)) {
                String command =
                        "python3 - <<'PY'\n"
                            + "from pathlib import Path\n"
                            + "p=Path('output')\n"
                            + "p.mkdir(exist_ok=True)\n"
                            + "(p/'report.bin').write_bytes(bytes((n+19)%256 for n in"
                            + " range(256))*8192)\n"
                            + "with Path('artifact-effects.txt').open('a') as f: f.write('create-"
                                + label
                                + "\\n')\n"
                                + "print('artifact-created-"
                                + label
                                + "')\nPY";
                return tool(create, "execute", Map.of("command", command, "timeout", 30));
            }
            if (!completed.containsKey(publish)) {
                if (tools.stream().noneMatch(value -> value.getName().equals("publish_artifact")))
                    return Flux.error(
                            new IllegalStateException("Managed artifact tool was not advertised"));
                return tool(
                        publish,
                        "publish_artifact",
                        Map.of(
                                "path",
                                "output/report.bin",
                                "name",
                                "report.bin",
                                "media_type",
                                "application/octet-stream"));
            }
            var metadata =
                    JsonUtils.getJsonCodec().fromJson(output(completed.get(publish)), Map.class);
            if (!(metadata.get("artifact_id") instanceof String)
                    || !(metadata.get("file_id") instanceof String)
                    || !(metadata.get("size") instanceof Number size)
                    || size.intValue() != 2097152
                    || metadata.containsKey("content")
                    || metadata.get("download_url") == null)
                return Flux.error(
                        new IllegalStateException(
                                "HarnessRuntime did not receive archived artifact metadata"));
            if (!completed.containsKey(change)) {
                String command =
                        "printf changed > output/report.bin; printf 'change-"
                                + label
                                + "\\n' >> artifact-effects.txt";
                return tool(change, "execute", Map.of("command", command, "timeout", 30));
            }
            return text("harness-runtime-artifact-confirmed-" + label);
        }

        private Flux<ChatResponse> checkpoint(
                List<Msg> messages, int request, String operation, String version, String label) {
            String id = "replica-checkpoint-" + operation + "-" + version + "-" + label;
            String marker = "checkpoint-" + operation + "-" + version + "-" + label;
            if (operation.equals("read")) {
                String history =
                        messages.subList(0, request).stream()
                                .map(Msg::getTextContent)
                                .reduce("", (a, b) -> a + "\n" + b);
                if (!history.contains("harness-runtime-checkpoint-write-" + version + "-" + label)
                        || version.equals("v1")
                                && (history.contains("harness-runtime-checkpoint-write-v2-" + label)
                                        || history.contains(
                                                "harness-runtime-checkpoint-read-v2-" + label)))
                    return Flux.error(
                            new IllegalStateException(
                                    "Checkpoint did not restore the selected model context"));
            }
            for (var message : messages.subList(request + 1, messages.size())) {
                for (var result : message.getContentBlocks(ToolResultBlock.class)) {
                    if (!id.equals(result.getId())) continue;
                    if (result.getState() != ToolResultState.SUCCESS
                            || result.getOutput().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .noneMatch(
                                            block ->
                                                    block.getText()
                                                            .contains("tool-worker-" + marker)))
                        return Flux.error(
                                new IllegalStateException(
                                        "Checkpoint workspace content or permissions differ"));
                    return text("harness-runtime-" + marker);
                }
            }
            int fill = version.equals("v1") ? 17 : version.equals("v2") ? 31 : 47;
            String command =
                    "python3 - <<'PY'\n"
                            + "from pathlib import Path\n"
                            + "import stat\n"
                            + "p=Path('checkpoint-box')\n";
            if (operation.equals("write")) {
                command +=
                        "p.mkdir(exist_ok=True)\n"
                                + "locked=p/'locked'\n"
                                + "locked.mkdir(exist_ok=True)\n"
                                + "locked.chmod(0o755)\n"
                                + "(p/'value.txt').write_text('"
                                + version
                                + "')\n"
                                + "(p/'binary.bin').write_bytes(bytes((n+"
                                + fill
                                + ")%256 for n in range(256))*8192)\n"
                                + "(locked/'item.txt').write_text('locked-"
                                + version
                                + "')\nlocked.chmod(0o555)\n";
            } else {
                command +=
                        "assert (p/'value.txt').read_text()=='"
                                + version
                                + "'\n"
                                + "assert (p/'binary.bin').read_bytes()==bytes((n+"
                                + fill
                                + ")%256 for n in range(256))*8192\n"
                                + "assert (p/'locked'/'item.txt').read_text()=='locked-"
                                + version
                                + "'\n"
                                + "assert stat.S_IMODE((p/'locked').stat().st_mode)==0o555\n";
            }
            command += "print('tool-worker-" + marker + "')\nPY";
            return tool(id, "execute", Map.of("command", command, "timeout", 30));
        }

        private Flux<ChatResponse> delegate(
                List<Msg> messages, int seconds, String label, boolean send) {
            if (seconds > 240)
                return Flux.error(new IllegalArgumentException("Child fixture too long"));
            ToolResultBlock spawned = null;
            ToolResultBlock sent = null;
            for (var message : messages) {
                for (var result : message.getContentBlocks(ToolResultBlock.class)) {
                    if (("replica-spawn-" + label).equals(result.getId())) spawned = result;
                    if (("replica-send-" + label).equals(result.getId())) sent = result;
                }
            }
            if (spawned == null)
                return tool(
                        "replica-spawn-" + label,
                        "agent_spawn",
                        Map.of(
                                "agent_id",
                                "general-purpose",
                                "task",
                                "fixture:execute:" + seconds + ":child-one-" + label,
                                "label",
                                "fixture-worker",
                                "timeout_seconds",
                                0));
            String first = output(spawned);
            if (!first.contains("harness-runtime-confirmed-tool-worker-child-one-" + label))
                return Flux.error(
                        new IllegalStateException("Unconfirmed child spawn result: " + first));
            if (!send) return text("harness-runtime-confirmed-children-" + label);
            if (sent != null) {
                if (!output(sent)
                        .contains("harness-runtime-confirmed-tool-worker-child-two-" + label))
                    return Flux.error(
                            new IllegalStateException(
                                    "Unconfirmed child send result: " + output(sent)));
                return text("harness-runtime-confirmed-children-" + label);
            }
            String key =
                    first.lines()
                            .filter(line -> line.startsWith("agent_key: "))
                            .map(line -> line.substring(11))
                            .findFirst()
                            .orElseThrow();
            return tool(
                    "replica-send-" + label,
                    "agent_send",
                    Map.of(
                            "agent_key",
                            key,
                            "message",
                            "fixture:execute:" + seconds + ":child-two-" + label,
                            "timeout_seconds",
                            0));
        }

        private static String output(ToolResultBlock result) {
            return result.getOutput().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .reduce("", String::concat);
        }

        private Flux<ChatResponse> memory(
                List<Msg> messages,
                List<ToolSchema> tools,
                int seconds,
                String store,
                String label,
                boolean denied) {
            if (seconds > 240)
                return Flux.error(new IllegalArgumentException("Memory fixture too long"));
            boolean executed = false;
            boolean deniedWrite = false;
            String path = "verification/" + label + ".md";
            for (var message : messages) {
                for (var result : message.getContentBlocks(ToolResultBlock.class)) {
                    String output =
                            result.getOutput().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .map(TextBlock::getText)
                                    .reduce("", String::concat);
                    if (result.getId().equals("replica-memory-execute-" + label)) {
                        if (!output.contains("tool-worker-memory-" + label))
                            return Flux.error(
                                    new IllegalStateException(
                                            "Memory tool fixture execution failed"));
                        executed = true;
                    }
                    if (result.getId().equals("replica-memory-write-" + label)) {
                        var write = memoryResult(output);
                        if (denied) {
                            if (!(write.get("error") instanceof String error)
                                    || !error.contains("read_only"))
                                return Flux.error(
                                        new IllegalStateException(
                                                "Read-only memory write was not rejected"));
                            deniedWrite = true;
                        }
                    }
                    if (result.getId().equals("replica-memory-read-" + label)) {
                        var read = memoryResult(output);
                        if (read.get("error") == null
                                && read.get("fileData") instanceof Map<?, ?> file
                                && ("native-memory-" + label).equals(file.get("content")))
                            return text(
                                    "harness-runtime-confirmed-memory-"
                                            + (denied ? "denied-" : "")
                                            + label);
                        return Flux.error(
                                new IllegalStateException("Memory read failed: " + output));
                    }
                }
            }
            if (!executed) {
                String command =
                        "printf 'start-memory-"
                                + label
                                + "\\n' >> side-effects.txt; sleep "
                                + seconds
                                + "; printf 'tool-worker-memory-"
                                + label
                                + "\\n'; printf 'done-memory-"
                                + label
                                + "\\n' >> side-effects.txt";
                return tool(
                        "replica-memory-execute-" + label,
                        "execute",
                        Map.of("command", command, "timeout", seconds + 30));
            }
            if (denied && !deniedWrite)
                return tool(
                        "replica-memory-write-" + label,
                        name(tools, "memory_store_write"),
                        Map.of(
                                "storeId",
                                store,
                                "path",
                                path,
                                "content",
                                "native-memory-" + label));
            return tool(
                    "replica-memory-read-" + label,
                    name(tools, "memory_store_read"),
                    Map.of("storeId", store, "path", path));
        }

        private Map<?, ?> memoryResult(String output) {
            // Reflective String-returning tools carry a JSON string, whose content is the result.
            String json = JsonUtils.getJsonCodec().fromJson(output, String.class);
            return JsonUtils.getJsonCodec().fromJson(json, Map.class);
        }

        private Flux<ChatResponse> task(
                List<Msg> messages, List<ToolSchema> tools, int seconds, String label) {
            if (seconds > 240)
                return Flux.error(new IllegalArgumentException("Task fixture too long"));
            boolean context = false;
            boolean executed = false;
            boolean completed = false;
            for (var message : messages) {
                for (var result : message.getContentBlocks(ToolResultBlock.class)) {
                    String name = result.getName().replace('.', '_');
                    String output =
                            result.getOutput().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .map(TextBlock::getText)
                                    .reduce("", String::concat);
                    if (name.endsWith("task_get") && output.contains("executionBrief"))
                        context = true;
                    if (name.equals("execute") && output.contains("tool-worker-" + label))
                        executed = true;
                    if (name.endsWith("task_complete") && output.contains("completed"))
                        completed = true;
                }
            }
            if (completed) return text("harness-runtime-task-complete-" + label);
            if (!context) return tool("task-context-" + label, name(tools, "task_get"), Map.of());
            if (!executed) {
                String command =
                        "printf 'start-"
                                + label
                                + "\\n' >> side-effects.txt; sleep "
                                + seconds
                                + "; printf 'tool-worker-"
                                + label
                                + "\\n'; printf 'done-"
                                + label
                                + "\\n' >> side-effects.txt";
                return tool(
                        "replica-execute-" + label,
                        "execute",
                        Map.of("command", command, "timeout", seconds + 30));
            }
            return tool(
                    "task-complete-" + label,
                    name(tools, "task_complete"),
                    Map.of(
                            "outcome",
                            "succeeded",
                            "summary",
                            "verified " + label,
                            "result",
                            Map.of("answer", "harness-runtime-confirmed-tool-worker-" + label)));
        }

        private String name(List<ToolSchema> tools, String suffix) {
            return tools.stream()
                    .map(ToolSchema::getName)
                    .filter(value -> value.replace('.', '_').endsWith(suffix))
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "Missing task MCP tool "
                                                    + suffix
                                                    + "; advertised="
                                                    + tools.stream()
                                                            .map(ToolSchema::getName)
                                                            .toList()));
        }

        private Flux<ChatResponse> tool(String id, String name, Map<String, Object> input) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id(id)
                                                    .name(name)
                                                    .input(input)
                                                    .content(JsonUtils.getJsonCodec().toJson(input))
                                                    .build()))
                            .build());
        }

        private static Flux<ChatResponse> text(String value) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(value).build()))
                            .build());
        }
    }
}
