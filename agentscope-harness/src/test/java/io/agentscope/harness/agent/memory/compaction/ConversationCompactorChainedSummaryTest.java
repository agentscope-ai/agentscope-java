package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Regression tests for #2359: consecutive compactions must keep the previous summary in
 * the summarization input (chained intent), instead of filtering it out — which used to
 * decay the original user intent round over round (and degenerate to
 * "No previous conversation history." when the prefix was all summaries).
 */
class ConversationCompactorChainedSummaryTest {

    private static final String PRIOR_INTENT = "原始意图：诊断生产 OOM 并给出根因";

    /** Captures the text the model is asked to summarize, returns a fixed summary. */
    private static final class CapturingModel extends ChatModelBase {
        final AtomicReference<String> seenPrompt = new AtomicReference<>("");

        @Override
        public String getModelName() {
            return "capturing-summary-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            StringBuilder sb = new StringBuilder();
            for (Msg m : messages) {
                String t = m.getTextContent();
                if (t != null) sb.append(t);
            }
            seenPrompt.set(sb.toString());
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<io.agentscope.core.message.ContentBlock>of(
                                            TextBlock.builder().text("压缩后的新摘要").build()))
                            .build());
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg assistantMsg(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg priorSummaryMsg() {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(ConversationCompactor.SUMMARY_MSG_NAME)
                .content(
                        TextBlock.builder()
                                .text(
                                        "Here is a summary of the conversation to date:\n\n"
                                                + PRIOR_INTENT)
                                .build())
                .build();
    }

    @Test
    @DisplayName("#2359: previous summary is included in the next round's summarization input")
    void chainedCompaction_keepsPriorSummaryInSummarizationInput() {
        CapturingModel model = new CapturingModel();
        ConversationCompactor compactor =
                new ConversationCompactor(model, mock(MemoryFlushManager.class));

        List<Msg> messages = new ArrayList<>();
        messages.add(priorSummaryMsg()); // 上一轮压缩的摘要（携带原始意图）
        for (int i = 0; i < 6; i++) {
            messages.add(userMsg("新的问题 " + i + "：继续排查"));
            messages.add(assistantMsg("新的回答 " + i));
        }

        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(4)
                        .keepMessages(2)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .build();

        Optional<List<Msg>> result =
                compactor.compactIfNeeded(null, messages, config, "agent", "session").block();

        assertTrue(result.isPresent(), "compaction should trigger");

        String prompt = model.seenPrompt.get();
        assertTrue(
                prompt.contains(PRIOR_INTENT),
                "#2359 regression: prior summary must be part of the summarization input, got: "
                        + prompt);
        assertFalse(
                prompt.contains("No previous conversation history"),
                "summary must not degenerate when a prior summary exists");
    }

    @Test
    @DisplayName("#2359: prefix made only of a prior summary still produces a real summary")
    void prefixOfOnlyPriorSummary_doesNotDegenerate() {
        CapturingModel model = new CapturingModel();
        ConversationCompactor compactor =
                new ConversationCompactor(model, mock(MemoryFlushManager.class));

        List<Msg> messages = new ArrayList<>();
        messages.add(priorSummaryMsg());
        messages.add(userMsg("补充一个问题"));
        messages.add(assistantMsg("补充回答"));
        messages.add(userMsg("再补充一个"));
        messages.add(assistantMsg("再补充回答"));

        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(3)
                        .keepMessages(2)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .build();

        Optional<List<Msg>> result =
                compactor.compactIfNeeded(null, messages, config, "agent", "session").block();

        assertTrue(result.isPresent());
        String prompt = model.seenPrompt.get();
        assertTrue(
                prompt.contains(PRIOR_INTENT),
                "prior summary must remain the summarization baseline, got: " + prompt);
        assertFalse(
                result.get().stream()
                        .anyMatch(
                                m -> {
                                    String t = m.getTextContent();
                                    return t != null
                                            && t.contains("No previous conversation history");
                                }),
                "must not emit the degenerate 'No previous conversation history' summary");
    }
}
