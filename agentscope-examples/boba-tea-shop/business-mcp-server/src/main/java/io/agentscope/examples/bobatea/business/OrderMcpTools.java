/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.examples.bobatea.business;

import io.agentscope.examples.bobatea.business.entity.Order;
import io.agentscope.examples.bobatea.business.model.OrderCreateRequest;
import io.agentscope.examples.bobatea.business.model.OrderQueryRequest;
import io.agentscope.examples.bobatea.business.model.OrderResponse;
import io.agentscope.examples.bobatea.business.service.OrderService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Order MCP Tools Class
 * Provides order operation tools under the MCP protocol
 */
@Service
public class OrderMcpTools {

    @Autowired private OrderService orderService;

    /**
     * Create Order Tool (new interface, supports user ID)
     */
    @Tool(
            name = "order-create-order-with-user",
            description =
                    "Create a new boba tea order for user. Supports all products from Cloud Edge"
                        + " Boba Tea Shop, including Cloud Jasmine, Osmanthus Cloud Dew, Misty"
                        + " Tieguanyin and other classic products. System will automatically check"
                        + " stock and calculate price.")
    public String createOrderWithUser(
            @ToolParam(description = "User ID, must be a positive integer") Long userId,
            @ToolParam(
                            description =
                                    "Product name, must be an existing product at Cloud Edge Boba"
                                        + " Tea Shop, such as: Cloud Jasmine, Osmanthus Cloud Dew,"
                                        + " Misty Tieguanyin, Mountain Red Charm, Cloud Peach"
                                        + " Oolong, Cloud Edge Pu'er, Osmanthus Longjing, Cloud"
                                        + " Peak Mountain Tea")
                    String productName,
            @ToolParam(
                            description =
                                    "Sweetness requirement, options: Regular Sugar, Less Sugar,"
                                            + " Half Sugar, Light Sugar, No Sugar")
                    String sweetness,
            @ToolParam(
                            description =
                                    "Ice level requirement, options: Regular Ice, Less Ice, No Ice,"
                                            + " Warm, Hot")
                    String iceLevel,
            @ToolParam(description = "Purchase quantity, must be a positive integer, default is 1")
                    int quantity,
            @ToolParam(description = "Order remark, optional") String remark) {
        try {
            // Convert sweetness and ice level to numbers
            Integer sweetnessLevel = convertSweetnessToNumber(sweetness);
            Integer iceLevelNumber = convertIceLevelToNumber(iceLevel);

            OrderCreateRequest request =
                    new OrderCreateRequest(
                            userId,
                            null,
                            productName,
                            sweetnessLevel,
                            iceLevelNumber,
                            quantity,
                            remark);

            OrderResponse order = orderService.createOrder(request);
            return String.format(
                    "Order created successfully! Order ID: %s, User ID: %d, Product: %s, Sweetness:"
                            + " %s, Ice Level: %s, Quantity: %d, Price: %.2f yuan",
                    order.getOrderId(),
                    order.getUserId(),
                    order.getProductName(),
                    order.getSweetnessText(),
                    order.getIceLevelText(),
                    order.getQuantity(),
                    order.getTotalPrice());
        } catch (Exception e) {
            return "Failed to create order: " + e.getMessage();
        }
    }

    /**
     * Query Order Tool (compatible with original interface)
     */
    @Tool(
            name = "order-get-order",
            description =
                    "Query order details by order ID, including product name, sweetness, ice level,"
                            + " quantity, price and creation time.")
    public String getOrder(
            @ToolParam(
                            description =
                                    "Order ID, unique identifier starting with ORDER_, for example:"
                                            + " ORDER_1693654321000")
                    String orderId) {
        try {
            Order order = orderService.getOrder(orderId);
            if (order == null) {
                return "Order does not exist: " + orderId;
            }

            return String.format(
                    "Order Info - ID: %s, Product: %s, Sweetness: %s, Ice Level: %s, Quantity: %d,"
                            + " Price: %.2f yuan, Created: %s",
                    order.getOrderId(),
                    order.getProductName(),
                    order.getSweetnessText(),
                    order.getIceLevelText(),
                    order.getQuantity(),
                    order.getTotalPrice(),
                    order.getCreatedAt()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception e) {
            return "Failed to query order: " + e.getMessage();
        }
    }

    /**
     * Query Order Tool by User ID and Order ID
     */
    @Tool(
            name = "order-get-order-by-user",
            description =
                    "Query order details by user ID and order ID, including product name,"
                            + " sweetness, ice level, quantity, price and creation time.")
    public String getOrderByUser(
            @ToolParam(description = "User ID, must be a positive integer") Long userId,
            @ToolParam(
                            description =
                                    "Order ID, unique identifier starting with ORDER_, for example:"
                                            + " ORDER_1693654321000")
                    String orderId) {
        try {
            OrderResponse order = orderService.getOrderByUserIdAndOrderId(userId, orderId);
            if (order == null) {
                return "Order does not exist: " + orderId + " (User ID: " + userId + ")";
            }

            return String.format(
                    "Order Info - ID: %s, User ID: %d, Product: %s, Sweetness: %s, Ice Level: %s,"
                            + " Quantity: %d, Price: %.2f yuan, Created: %s",
                    order.getOrderId(),
                    order.getUserId(),
                    order.getProductName(),
                    order.getSweetnessText(),
                    order.getIceLevelText(),
                    order.getQuantity(),
                    order.getTotalPrice(),
                    order.getCreatedAt()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception e) {
            return "Failed to query order: " + e.getMessage();
        }
    }

    /**
     * Check Stock Tool
     */
    @Tool(
            name = "order-check-stock",
            description =
                    "Check if the specified product has sufficient stock, ensuring the user's"
                            + " required quantity can be fulfilled before placing an order. Returns"
                            + " stock status and availability information.")
    public String checkStock(
            @ToolParam(
                            description =
                                    "Product name, must be an existing product at Cloud Edge Boba"
                                        + " Tea Shop, such as: Cloud Jasmine, Osmanthus Cloud Dew,"
                                        + " Misty Tieguanyin, Mountain Red Charm, Cloud Peach"
                                        + " Oolong, Cloud Edge Pu'er, Osmanthus Longjing, Cloud"
                                        + " Peak Mountain Tea")
                    String productName,
            @ToolParam(
                            description =
                                    "Quantity to check, must be a positive integer, representing"
                                            + " the quantity the user wants to purchase")
                    int quantity) {
        try {
            boolean available = orderService.checkStock(productName, quantity);
            return available
                    ? String.format(
                            "Product %s has sufficient stock, can provide %d units",
                            productName, quantity)
                    : String.format(
                            "Product %s has insufficient stock, cannot provide %d units",
                            productName, quantity);
        } catch (Exception e) {
            return "Failed to check stock: " + e.getMessage();
        }
    }

    /**
     * Get All Orders Tool (compatible with original interface)
     */
    @Tool(
            name = "order-get-orders",
            description =
                    "Get a list of all orders in the system, including order ID, product"
                        + " information, price and creation time. Used to view order history and"
                        + " statistics.")
    public String getAllOrders() {
        try {
            List<Order> orders = orderService.getAllOrders();
            if (orders.isEmpty()) {
                return "No order records at the moment.";
            }

            StringBuilder result = new StringBuilder("All orders list:\n");
            for (Order order : orders) {
                result.append(
                        String.format(
                                "- Order ID: %s, Product: %s, Sweetness: %s, Ice Level: %s,"
                                        + " Quantity: %d, Price: %.2f yuan, Created: %s\n",
                                order.getOrderId(),
                                order.getProductName(),
                                order.getSweetnessText(),
                                order.getIceLevelText(),
                                order.getQuantity(),
                                order.getTotalPrice(),
                                order.getCreatedAt()
                                        .format(
                                                DateTimeFormatter.ofPattern(
                                                        "yyyy-MM-dd HH:mm:ss"))));
            }

            return result.toString();
        } catch (Exception e) {
            return "Failed to get order list: " + e.getMessage();
        }
    }

    /**
     * Get Orders by User ID Tool
     */
    @Tool(
            name = "order-get-orders-by-user",
            description =
                    "Get all orders for a user by user ID, including order ID, product information,"
                            + " price and creation time. Used to view user's order history.")
    public String getOrdersByUser(
            @ToolParam(description = "User ID, must be a positive integer") Long userId) {
        try {
            List<OrderResponse> orders = orderService.getOrdersByUserId(userId);
            if (orders.isEmpty()) {
                return "User " + userId + " has no order records.";
            }

            StringBuilder result = new StringBuilder("User " + userId + " orders list:\n");
            for (OrderResponse order : orders) {
                result.append(
                        String.format(
                                "- Order ID: %s, Product: %s, Sweetness: %s, Ice Level: %s,"
                                        + " Quantity: %d, Price: %.2f yuan, Created: %s\n",
                                order.getOrderId(),
                                order.getProductName(),
                                order.getSweetnessText(),
                                order.getIceLevelText(),
                                order.getQuantity(),
                                order.getTotalPrice(),
                                order.getCreatedAt()
                                        .format(
                                                DateTimeFormatter.ofPattern(
                                                        "yyyy-MM-dd HH:mm:ss"))));
            }

            return result.toString();
        } catch (Exception e) {
            return "Failed to get user order list: " + e.getMessage();
        }
    }

    /**
     * Multi-dimensional Query User Orders Tool
     */
    @Tool(
            name = "order-query-orders",
            description =
                    "Query user orders by multiple conditions, supports filtering by product name,"
                            + " sweetness, ice level, time range, etc.")
    public String queryOrders(
            @ToolParam(description = "User ID, must be a positive integer") Long userId,
            @ToolParam(description = "Product name, optional, supports fuzzy matching")
                    String productName,
            @ToolParam(
                            description =
                                    "Sweetness, optional, 1-No Sugar, 2-Light Sugar, 3-Half Sugar,"
                                            + " 4-Less Sugar, 5-Regular Sugar")
                    Integer sweetness,
            @ToolParam(
                            description =
                                    "Ice level, optional, 1-Hot, 2-Warm, 3-No Ice, 4-Less Ice,"
                                            + " 5-Regular Ice")
                    Integer iceLevel,
            @ToolParam(description = "Start time, optional, format: yyyy-MM-dd HH:mm:ss")
                    String startTime,
            @ToolParam(description = "End time, optional, format: yyyy-MM-dd HH:mm:ss")
                    String endTime) {
        try {
            OrderQueryRequest request = new OrderQueryRequest(userId);
            request.setProductName(productName);
            request.setSweetness(sweetness);
            request.setIceLevel(iceLevel);

            if (startTime != null && !startTime.trim().isEmpty()) {
                request.setStartTime(
                        LocalDateTime.parse(
                                startTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            if (endTime != null && !endTime.trim().isEmpty()) {
                request.setEndTime(
                        LocalDateTime.parse(
                                endTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }

            List<OrderResponse> orders = orderService.queryOrders(request);
            if (orders.isEmpty()) {
                return "No order records matching the criteria found.";
            }

            StringBuilder result =
                    new StringBuilder("Query results (" + orders.size() + " records):\n");
            for (OrderResponse order : orders) {
                result.append(
                        String.format(
                                "- Order ID: %s, Product: %s, Sweetness: %s, Ice Level: %s,"
                                        + " Quantity: %d, Price: %.2f yuan, Created: %s\n",
                                order.getOrderId(),
                                order.getProductName(),
                                order.getSweetnessText(),
                                order.getIceLevelText(),
                                order.getQuantity(),
                                order.getTotalPrice(),
                                order.getCreatedAt()
                                        .format(
                                                DateTimeFormatter.ofPattern(
                                                        "yyyy-MM-dd HH:mm:ss"))));
            }

            return result.toString();
        } catch (Exception e) {
            return "Failed to query orders: " + e.getMessage();
        }
    }

    /**
     * Delete Order Tool
     */
    @Tool(
            name = "order-delete-order",
            description =
                    "Delete an order by user ID and order ID. Can only delete orders belonging to"
                            + " that user.")
    public String deleteOrder(
            @ToolParam(description = "User ID, must be a positive integer") Long userId,
            @ToolParam(description = "Order ID, unique identifier starting with ORDER_")
                    String orderId) {
        try {
            boolean deleted = orderService.deleteOrder(userId, orderId);
            if (deleted) {
                return "Order deleted successfully: " + orderId;
            } else {
                return "Failed to delete order, order does not exist or no permission: " + orderId;
            }
        } catch (Exception e) {
            return "Failed to delete order: " + e.getMessage();
        }
    }

    /**
     * Update Order Remark Tool
     */
    @Tool(
            name = "order-update-remark",
            description =
                    "Update order remark by user ID and order ID. Can only update orders belonging"
                            + " to that user.")
    public String updateOrderRemark(
            @ToolParam(description = "User ID, must be a positive integer") Long userId,
            @ToolParam(description = "Order ID, unique identifier starting with ORDER_")
                    String orderId,
            @ToolParam(description = "New remark content") String remark) {
        try {
            OrderResponse order = orderService.updateOrderRemark(userId, orderId, remark);
            if (order != null) {
                return "Order remark updated successfully: " + orderId + ", new remark: " + remark;
            } else {
                return "Failed to update order remark, order does not exist or no permission: "
                        + orderId;
            }
        } catch (Exception e) {
            return "Failed to update order remark: " + e.getMessage();
        }
    }

    /**
     * Validate Product Exists Tool
     */
    @Tool(
            name = "order-validate-product",
            description = "Validate if the specified product exists and is available.")
    public String validateProduct(@ToolParam(description = "Product name") String productName) {
        try {
            boolean exists = orderService.validateProduct(productName);
            return exists
                    ? String.format("Product %s exists and is available", productName)
                    : String.format(
                            "Product %s does not exist or has been discontinued", productName);
        } catch (Exception e) {
            return "Failed to validate product: " + e.getMessage();
        }
    }

    /**
     * Convert sweetness string to number
     */
    private Integer convertSweetnessToNumber(String sweetness) {
        if (sweetness == null) return 5; // Default: Regular Sugar
        switch (sweetness.toLowerCase()) {
            case "no sugar":
                return 1;
            case "light sugar":
                return 2;
            case "half sugar":
                return 3;
            case "less sugar":
                return 4;
            case "regular sugar":
                return 5;
            default:
                return 5;
        }
    }

    /**
     * Convert ice level string to number
     */
    private Integer convertIceLevelToNumber(String iceLevel) {
        if (iceLevel == null) return 5; // Default: Regular Ice
        switch (iceLevel.toLowerCase()) {
            case "hot":
                return 1;
            case "warm":
                return 2;
            case "no ice":
                return 3;
            case "less ice":
                return 4;
            case "regular ice":
                return 5;
            default:
                return 5;
        }
    }
}
