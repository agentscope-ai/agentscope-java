package io.agentscope.examples.bobatea.supervisor.tools;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * @author xiweng.yy
 */
@Component
public class A2aAgentTools {

    private final ObjectProvider<A2aAgent> consultAgentProvider;

    private final ObjectProvider<A2aAgent> businessAgentProvider;

    public A2aAgentTools(
            @Qualifier("consultAgent") ObjectProvider<A2aAgent> consultAgentProvider,
            @Qualifier("businessAgent") ObjectProvider<A2aAgent> businessAgentProvider) {
        this.consultAgentProvider = consultAgentProvider;
        this.businessAgentProvider = businessAgentProvider;
    }

    @Tool(description = "处理咨询相关的 Agent，能处理所有与咨询相关的请求，需要将完整的上下文放到 context 里面传递")
    public String callConsultAgent(
            @ToolParam(name = "context", description = "完整的上下文") String context,
            @ToolParam(name = "userId", description = "用户的UserId") String userId) {
        // 由于 A2A extension 对 metadata 的支持还不完善，暂时通过 msg 本身传递 userId
        context = "<userId>" + userId + "</userId>" + context;
        Msg msg = Msg.builder().content(TextBlock.builder().text(context).build()).build();
        A2aAgent consultAgent = consultAgentProvider.getObject();
        return combineAgentResponse(consultAgent.call(msg).block());
    }

    @Tool(description = "处理投诉以及订单相关的 Agent，能处理所有与投诉和订单相关的请求，需要将完整的上下文放到 context 里面传递")
    public String callBusinessAgent(
            @ToolParam(name = "context", description = "完整的上下文") String context,
            @ToolParam(name = "userId", description = "用户的UserId") String userId) {
        // 由于 A2A extension 对 metadata 的支持还不完善，暂时通过 msg 本身传递 userId
        context = "<userId>" + userId + "</userId>" + context;
        Msg msg = Msg.builder().content(TextBlock.builder().text(context).build()).build();
        A2aAgent businessAgent = businessAgentProvider.getObject();
        return combineAgentResponse(businessAgent.call(msg).block());
    }

    private String combineAgentResponse(Msg responseMsg) {
        if (null == responseMsg) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        responseMsg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .forEach(block -> result.append(((TextBlock) block).getText()));
        return result.toString();
    }
}
