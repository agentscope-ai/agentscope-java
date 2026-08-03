/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.copilotkit.workbench;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Refund tool demonstrating AgentScope's <em>built-in checks</em>: the permission decision depends on
 * the actual arguments rather than on a static rule.
 *
 * <p>Small refunds run straight through, anything at or above {@value #ASK_THRESHOLD} needs a human,
 * and refunds without an order id are rejected outright. Because built-in checks run inside the tool
 * they cannot be bypassed by {@code PermissionMode.BYPASS} or by an allow rule — which is exactly the
 * point of the demo.
 *
 */
public class RefundOrderTool extends ToolBase {

    /** Refunds at or above this amount always require human confirmation. */
    public static final double ASK_THRESHOLD = 1000d;

    private static final String NAME = "refund_order";

    private final WorkbenchStateRegistry registry;

    public RefundOrderTool(WorkbenchStateRegistry registry) {
        super(
                ToolBase.builder()
                        .name(NAME)
                        .description(
                                "为订单发起退款。小额退款直接执行，%.0f 元及以上会触发人工确认（工具内置检查，不可被规则绕过）。"
                                        .formatted(ASK_THRESHOLD))
                        .inputSchema(inputSchema())
                        .readOnly(false)
                        .concurrencySafe(false));
        this.registry = registry;
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("orderNo", Map.of("type", "string", "description", "订单号，例如 SO202607310001"));
        properties.put("amount", Map.of("type", "number", "description", "退款金额，单位元"));
        properties.put("reason", Map.of("type", "string", "description", "退款原因"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("orderNo", "amount"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        String orderNo = string(toolInput.get("orderNo"));
        if (orderNo.isBlank()) {
            return Mono.just(PermissionDecision.deny("缺少订单号，拒绝执行退款。"));
        }
        double amount = amount(toolInput);
        if (amount >= ASK_THRESHOLD) {
            return Mono.just(
                    PermissionDecision.ask(
                            "订单 %s 退款 %.2f 元，超过 %.0f 元的自动放行阈值，需要人工确认。"
                                    .formatted(orderNo, amount, ASK_THRESHOLD)));
        }
        return Mono.just(PermissionDecision.allow("小额退款，自动放行。"));
    }

    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null) {
            return true;
        }
        return ruleContent.equalsIgnoreCase(string(toolInput.get("orderNo")));
    }

    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        String orderNo = string(toolInput.get("orderNo"));
        return List.of(
                new PermissionRule(NAME, orderNo, PermissionBehavior.ALLOW, "suggested"),
                new PermissionRule(NAME, null, PermissionBehavior.ALLOW, "suggested"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String orderNo = string(input.get("orderNo"));
        double amount = amount(input);
        String reason = string(input.get("reason"));
        String message =
                "订单 %s 已退款 %.2f 元%s，预计 1-3 个工作日到账。请勿对同一订单重复发起退款。"
                        .formatted(orderNo, amount, reason.isBlank() ? "" : "（原因：" + reason + "）");
        String callId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
        return Mono.just(
                new ToolResultBlock(
                        callId, getName(), List.of(TextBlock.builder().text(message).build())));
    }

    private static double amount(Map<String, Object> input) {
        Object raw = input.get("amount");
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
