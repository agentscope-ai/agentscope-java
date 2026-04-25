package io.agentscope.harness.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import java.nio.file.Path;
import org.junit.Test;

public class E2ETest {
    @Test
    public void integrationTest() {
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("MyAgent")
                        .sysPrompt("你是一个有帮助的助手。")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                        .modelName("qwen3-max")
                                        .build())
                        .workspace(Path.of("./workspace"))
                        .build();

        Msg response =
                agent.call(
                                Msg.builder().role(MsgRole.USER).textContent("你是谁").build(),
                                RuntimeContext.builder()
                                        .sessionId("sess-1")
                                        .userId("user-1")
                                        .build())
                        .block();
        System.out.println(response.getTextContent());
    }
}
