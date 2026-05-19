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

package io.agentscope.examples.bobatea.business.config;

import io.agentscope.examples.bobatea.business.entity.Feedback;
import io.agentscope.examples.bobatea.business.entity.Order;
import io.agentscope.examples.bobatea.business.model.OrderCreateRequest;
import io.agentscope.examples.bobatea.business.model.OrderQueryRequest;
import io.agentscope.examples.bobatea.business.model.OrderResponse;
import io.agentscope.examples.bobatea.business.service.FeedbackService;
import io.agentscope.examples.bobatea.business.service.OrderService;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MCP Tool Handlers - contains all tool execution logic.
 * Separates business logic from MCP configuration for better maintainability.
 *
 */
@Component
public class McpToolHandlers {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private OrderService orderService;

    @Autowired private FeedbackService feedbackService;

    // ===================== Order Handlers =====================

    public CallToolResult createOrderWithUser(Map<String, Object> args) {
        try {
            OrderCreateRequest request =
                    new OrderCreateRequest(
                            getLong(args, "userId"),
                            null,
                            getString(args, "productName"),
                            convertSweetnessToNumber(getString(args, "sweetness")),
                            convertIceLevelToNumber(getString(args, "iceLevel")),
                            getInt(args, "quantity", 1),
                            getString(args, "remark"));

            OrderResponse order = orderService.createOrder(request);
            return success(
                    "Order created successfully! Order ID: %s, User ID: %d, Product: %s, "
                            + "Sweetness: %s, Ice Level: %s, Quantity: %d, Price: %.2f yuan",
                    order.getOrderId(),
                    order.getUserId(),
                    order.getProductName(),
                    order.getSweetnessText(),
                    order.getIceLevelText(),
                    order.getQuantity(),
                    order.getTotalPrice());
        } catch (Exception e) {
            return error("Failed to create order: " + e.getMessage());
        }
    }

    public CallToolResult getOrder(Map<String, Object> args) {
        try {
            String orderId = getString(args, "orderId");
            Order order = orderService.getOrder(orderId);
            if (order == null) {
                return success("Order does not exist: " + orderId);
            }
            return success(
                    "Order Info - ID: %s, Product: %s, Sweetness: %s, Ice Level: %s, "
                            + "Quantity: %d, Price: %.2f yuan, Created: %s",
                    order.getOrderId(),
                    order.getProductName(),
                    order.getSweetnessText(),
                    order.getIceLevelText(),
                    order.getQuantity(),
                    order.getTotalPrice(),
                    order.getCreatedAt().format(DATE_FORMATTER));
        } catch (Exception e) {
            return error("Failed to query order: " + e.getMessage());
        }
    }

    public CallToolResult getOrderByUser(Map<String, Object> args) {
        try {
            Long userId = getLong(args, "userId");
            String orderId = getString(args, "orderId");
            OrderResponse order = orderService.getOrderByUserIdAndOrderId(userId, orderId);
            if (order == null) {
                return success("Order does not exist: %s (User ID: %d)", orderId, userId);
            }
            return success(
                    "Order Info - ID: %s, User ID: %d, Product: %s, Sweetness: %s, "
                            + "Ice Level: %s, Quantity: %d, Price: %.2f yuan, Created: %s",
                    order.getOrderId(),
                    order.getUserId(),
                    order.getProductName(),
                    order.getSweetnessText(),
                    order.getIceLevelText(),
                    order.getQuantity(),
                    order.getTotalPrice(),
                    order.getCreatedAt().format(DATE_FORMATTER));
        } catch (Exception e) {
            return error("Failed to query order: " + e.getMessage());
        }
    }

    public CallToolResult checkStock(Map<String, Object> args) {
        try {
            String productName = getString(args, "productName");
            int quantity = getInt(args, "quantity", 1);
            boolean available = orderService.checkStock(productName, quantity);
            return success(
                    available
                            ? "Product %s has sufficient stock, can provide %d units"
                            : "Product %s has insufficient stock, cannot provide %d units",
                    productName,
                    quantity);
        } catch (Exception e) {
            return error("Failed to check stock: " + e.getMessage());
        }
    }

    public CallToolResult getOrders(Map<String, Object> args) {
        try {
            List<Order> orders = orderService.getAllOrders();
            if (orders.isEmpty()) {
                return success("No order records at the moment.");
            }
            StringBuilder result = new StringBuilder("All orders list:\n");
            for (Order order : orders) {
                result.append(formatOrderLine(order));
            }
            return success(result.toString());
        } catch (Exception e) {
            return error("Failed to get order list: " + e.getMessage());
        }
    }

    public CallToolResult getOrdersByUser(Map<String, Object> args) {
        try {
            Long userId = getLong(args, "userId");
            List<OrderResponse> orders = orderService.getOrdersByUserId(userId);
            if (orders.isEmpty()) {
                return success("User %d has no order records.", userId);
            }
            StringBuilder result = new StringBuilder("User " + userId + " orders list:\n");
            for (OrderResponse order : orders) {
                result.append(formatOrderResponseLine(order));
            }
            return success(result.toString());
        } catch (Exception e) {
            return error("Failed to get user order list: " + e.getMessage());
        }
    }

    public CallToolResult queryOrders(Map<String, Object> args) {
        try {
            Long userId = getLong(args, "userId");
            OrderQueryRequest request = new OrderQueryRequest(userId);
            request.setProductName(getString(args, "productName"));
            request.setSweetness(getInteger(args, "sweetness"));
            request.setIceLevel(getInteger(args, "iceLevel"));

            String startTime = getString(args, "startTime");
            String endTime = getString(args, "endTime");
            if (startTime != null && !startTime.trim().isEmpty()) {
                request.setStartTime(LocalDateTime.parse(startTime, DATE_FORMATTER));
            }
            if (endTime != null && !endTime.trim().isEmpty()) {
                request.setEndTime(LocalDateTime.parse(endTime, DATE_FORMATTER));
            }

            List<OrderResponse> orders = orderService.queryOrders(request);
            if (orders.isEmpty()) {
                return success("No order records matching the criteria found.");
            }
            StringBuilder result =
                    new StringBuilder("Query results (" + orders.size() + " records):\n");
            for (OrderResponse order : orders) {
                result.append(formatOrderResponseLine(order));
            }
            return success(result.toString());
        } catch (Exception e) {
            return error("Failed to query orders: " + e.getMessage());
        }
    }

    public CallToolResult deleteOrder(Map<String, Object> args) {
        try {
            Long userId = getLong(args, "userId");
            String orderId = getString(args, "orderId");
            boolean deleted = orderService.deleteOrder(userId, orderId);
            return success(
                    deleted
                            ? "Order deleted successfully: " + orderId
                            : "Failed to delete order, order does not exist or no permission: "
                                    + orderId);
        } catch (Exception e) {
            return error("Failed to delete order: " + e.getMessage());
        }
    }

    public CallToolResult updateRemark(Map<String, Object> args) {
        try {
            Long userId = getLong(args, "userId");
            String orderId = getString(args, "orderId");
            String remark = getString(args, "remark");
            OrderResponse order = orderService.updateOrderRemark(userId, orderId, remark);
            return success(
                    order != null
                            ? "Order remark updated successfully: "
                                    + orderId
                                    + ", new remark: "
                                    + remark
                            : "Failed to update order remark, order does not exist or no"
                                    + " permission: "
                                    + orderId);
        } catch (Exception e) {
            return error("Failed to update order remark: " + e.getMessage());
        }
    }

    public CallToolResult validateProduct(Map<String, Object> args) {
        try {
            String productName = getString(args, "productName");
            boolean exists = orderService.validateProduct(productName);
            return success(
                    exists
                            ? "Product %s exists and is available"
                            : "Product %s does not exist or has been discontinued",
                    productName);
        } catch (Exception e) {
            return error("Failed to validate product: " + e.getMessage());
        }
    }

    // ===================== Feedback Handlers =====================

    public CallToolResult createFeedback(Map<String, Object> args) {
        try {
            Feedback feedback = new Feedback();
            feedback.setUserId(getLong(args, "userId"));
            feedback.setFeedbackType(getInteger(args, "feedbackType"));
            feedback.setContent(getString(args, "content"));

            String orderId = getString(args, "orderId");
            if (orderId != null && !orderId.trim().isEmpty()) {
                feedback.setOrderId(orderId);
            }
            Integer rating = getInteger(args, "rating");
            if (rating != null) {
                feedback.setRating(rating);
            }

            Feedback created = feedbackService.createFeedback(feedback);
            return success(
                    "Feedback record created successfully! Feedback ID: %d, User ID: %d, "
                            + "Feedback Type: %s, Content: %s",
                    created.getId(),
                    created.getUserId(),
                    created.getFeedbackTypeText(),
                    created.getContent());
        } catch (Exception e) {
            return error("Failed to create feedback record: " + e.getMessage());
        }
    }

    public CallToolResult getFeedbackByUser(Map<String, Object> args) {
        try {
            Long userId = getLong(args, "userId");
            List<Feedback> feedbacks = feedbackService.getFeedbacksByUserId(userId);
            if (feedbacks.isEmpty()) {
                return success("No feedback records for this user");
            }
            StringBuilder result = new StringBuilder();
            result.append(
                    String.format(
                            "User %d feedback records (total %d):\n", userId, feedbacks.size()));
            for (Feedback fb : feedbacks) {
                result.append(
                        String.format(
                                "- Feedback ID: %d, Type: %s, Rating: %s, Content: %s, Time: %s\n",
                                fb.getId(),
                                fb.getFeedbackTypeText(),
                                fb.getRatingText(),
                                fb.getContent(),
                                fb.getCreatedAt()));
            }
            return success(result.toString());
        } catch (Exception e) {
            return error("Failed to query user feedback records: " + e.getMessage());
        }
    }

    public CallToolResult getFeedbackByOrder(Map<String, Object> args) {
        try {
            String orderId = getString(args, "orderId");
            List<Feedback> feedbacks = feedbackService.getFeedbacksByOrderId(orderId);
            if (feedbacks.isEmpty()) {
                return success("No feedback records for this order");
            }
            StringBuilder result = new StringBuilder();
            result.append(
                    String.format(
                            "Order %s feedback records (total %d):\n", orderId, feedbacks.size()));
            for (Feedback fb : feedbacks) {
                result.append(
                        String.format(
                                "- Feedback ID: %d, User ID: %d, Type: %s, Rating: %s, "
                                        + "Content: %s, Time: %s\n",
                                fb.getId(),
                                fb.getUserId(),
                                fb.getFeedbackTypeText(),
                                fb.getRatingText(),
                                fb.getContent(),
                                fb.getCreatedAt()));
            }
            return success(result.toString());
        } catch (Exception e) {
            return error("Failed to query order feedback records: " + e.getMessage());
        }
    }

    public CallToolResult updateSolution(Map<String, Object> args) {
        try {
            Long feedbackId = getLong(args, "feedbackId");
            String solution = getString(args, "solution");
            boolean success = feedbackService.updateFeedbackSolution(feedbackId, solution);
            return success(
                    success
                            ? "Feedback ID %d solution updated successfully: %s"
                            : "Failed to update solution for Feedback ID %d",
                    feedbackId,
                    solution);
        } catch (Exception e) {
            return error("Failed to update feedback solution: " + e.getMessage());
        }
    }

    // ===================== Helper Methods =====================

    private String formatOrderLine(Order order) {
        return String.format(
                "- Order ID: %s, Product: %s, Sweetness: %s, Ice Level: %s, "
                        + "Quantity: %d, Price: %.2f yuan, Created: %s\n",
                order.getOrderId(),
                order.getProductName(),
                order.getSweetnessText(),
                order.getIceLevelText(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getCreatedAt().format(DATE_FORMATTER));
    }

    private String formatOrderResponseLine(OrderResponse order) {
        return String.format(
                "- Order ID: %s, Product: %s, Sweetness: %s, Ice Level: %s, "
                        + "Quantity: %d, Price: %.2f yuan, Created: %s\n",
                order.getOrderId(),
                order.getProductName(),
                order.getSweetnessText(),
                order.getIceLevelText(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getCreatedAt().format(DATE_FORMATTER));
    }

    private CallToolResult success(String message, Object... args) {
        String formatted = args.length > 0 ? String.format(message, args) : message;
        return new CallToolResult(List.of(new McpSchema.TextContent(formatted)), false);
    }

    private CallToolResult error(String message) {
        return new CallToolResult(List.of(new McpSchema.TextContent(message)), true);
    }

    private String getString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLong(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }

    private Integer getInteger(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }

    private int getInt(Map<String, Object> args, String key, int defaultValue) {
        Integer value = getInteger(args, key);
        return value != null ? value : defaultValue;
    }

    private Integer convertSweetnessToNumber(String sweetness) {
        if (sweetness == null) return 5;
        return switch (sweetness.toLowerCase()) {
            case "no sugar" -> 1;
            case "light sugar" -> 2;
            case "half sugar" -> 3;
            case "less sugar" -> 4;
            default -> 5;
        };
    }

    private Integer convertIceLevelToNumber(String iceLevel) {
        if (iceLevel == null) return 5;
        return switch (iceLevel.toLowerCase()) {
            case "hot" -> 1;
            case "warm" -> 2;
            case "no ice" -> 3;
            case "less ice" -> 4;
            default -> 5;
        };
    }
}
