package io.agentscope.examples.chat;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Local, read-only fixtures. Calls still go through the real toolkit and session recorder. */
public final class DemoTools {
    private final Duration stepDelay;

    DemoTools(Duration stepDelay) {
        this.stepDelay = stepDelay;
    }

    @Tool(
            name = "demo_lookup",
            description = "Look up a local demo topic with progress updates",
            readOnly = true)
    public Mono<ToolResultBlock> lookup(
            @ToolParam(name = "topic", description = "Topic to look up") String topic,
            ToolEmitter emitter) {
        return Flux.range(1, 4)
                .delayElements(stepDelay)
                .doOnNext(
                        step ->
                                emitter.emit(
                                        ToolResultBlock.text(topic + "：已读取 " + step + "/4 段资料\n")))
                .then(Mono.fromSupplier(() -> ToolResultBlock.text(topic + "：资料已就绪，支持按持久事件恢复。")));
    }

    @Tool(
            name = "demo_verify",
            description = "Verify the local demo findings with progress updates",
            readOnly = true)
    public Mono<ToolResultBlock> verify(
            @ToolParam(name = "topic", description = "Finding to verify") String topic,
            ToolEmitter emitter) {
        return Flux.range(1, 4)
                .delayElements(stepDelay)
                .doOnNext(
                        step ->
                                emitter.emit(
                                        ToolResultBlock.text("核对 " + topic + "：" + step + "/4\n")))
                .then(
                        Mono.fromSupplier(
                                () -> ToolResultBlock.text("核对完成：文本、工具参数、执行进度和最终结果均可从日志恢复。")));
    }
}
