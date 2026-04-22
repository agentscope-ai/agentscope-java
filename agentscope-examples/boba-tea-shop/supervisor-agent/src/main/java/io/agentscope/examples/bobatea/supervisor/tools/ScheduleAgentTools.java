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

package io.agentscope.examples.bobatea.supervisor.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.examples.bobatea.supervisor.entity.Feedback;
import io.agentscope.examples.bobatea.supervisor.entity.Order;
import io.agentscope.examples.bobatea.supervisor.entity.Product;
import io.agentscope.examples.bobatea.supervisor.mapper.FeedbackMapper;
import io.agentscope.examples.bobatea.supervisor.mapper.OrderMapper;
import io.agentscope.examples.bobatea.supervisor.mapper.ProductMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ScheduleAgentTools {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleAgentTools.class);

    @Value("${agent.dingtalk.access-token}")
    private String accessToken;

    @Autowired private FeedbackMapper feedbackMapper;

    @Autowired private OrderMapper orderMapper;

    @Autowired private ProductMapper productMapper;

    private static final String DEFAULT_WEBHOOK_URL_TEMPLATE =
            "https://oapi.dingtalk.com/robot/send?access_token=%s";

    @Tool(description = "Get business report data information")
    public Map<String, Object> getDailyReportInfo() {
        // === Mock test data, get based on the maximum time of current test data
        String maxMonth = orderMapper.selectMaxCreatedMonth();
        System.out.println("DailyReportInfo month: " + maxMonth);
        Date startTime;
        Date endTime;
        if (maxMonth != null && !maxMonth.isEmpty()) {
            // Parse the maxMonth string (format: "yyyy-MM") to create the first day of that month
            try {
                YearMonth yearMonth = YearMonth.parse(maxMonth);
                LocalDate firstDayOfMonth = yearMonth.atDay(1);
                // Convert to Date objects
                startTime =
                        Date.from(firstDayOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant());
            } catch (Exception e) {
                // Fallback to default behavior if parsing fails
                startTime =
                        new Date(
                                System.currentTimeMillis()
                                        - 365L * 24 * 60 * 60 * 1000); // One year ago
            }
        } else {
            // Fallback to default behavior if maxMonth is null or empty
            startTime =
                    new Date(
                            System.currentTimeMillis()
                                    - 365L * 24 * 60 * 60 * 1000); // One year ago
        }
        endTime = new Date();
        // === Mock test data, get based on the maximum time of current test data

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("store_name", "Cloud Native Store #" + 1);

        String content = "";

        // == Get order sales data start
        List<Order> todayOrders = orderMapper.findOrdersByTimeRange(startTime, endTime);
        int todayOrderCount = todayOrders.size();
        BigDecimal totalRevenue =
                todayOrders.stream()
                        .map(Order::getTotalPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        Date yesterdayStartTime =
                new Date(startTime.getTime() - (365L * 24 * 60 * 60 * 1000)); // One year ago
        Date yesterdayEndTime = startTime;
        List<Order> yesterdayOrders =
                orderMapper.findOrdersByTimeRange(yesterdayStartTime, yesterdayEndTime);
        int yesterdayOrderCount = yesterdayOrders.size();
        BigDecimal yesterdayTotalRevenue =
                yesterdayOrders.stream()
                        .map(Order::getTotalPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        templateData.put("total_sales", todayOrderCount);
        templateData.put("yesterday_total_sales", yesterdayOrderCount);
        templateData.put("total_revenue", String.format("%.2f", totalRevenue));
        templateData.put(
                "avg_price",
                totalRevenue
                        .divide(new BigDecimal(todayOrderCount), 2, RoundingMode.HALF_UP)
                        .doubleValue());

        templateData.put(
                "sales_growth",
                String.format(
                                (totalRevenue.doubleValue() - yesterdayTotalRevenue.doubleValue()
                                                >= 0)
                                        ? "📈"
                                        : "📉" + " %.2f",
                                (totalRevenue.doubleValue() - yesterdayTotalRevenue.doubleValue())
                                        / yesterdayTotalRevenue.doubleValue()
                                        * 100)
                        + "%");
        templateData.put(
                "order_change",
                String.format(
                                (todayOrderCount - yesterdayOrderCount >= 0) ? "📈" : "📉" + "%.2f",
                                (((double) todayOrderCount - (double) yesterdayOrderCount)
                                        / (double) yesterdayOrderCount
                                        * 100D))
                        + "%");
        // == Get order sales data end

        // == Get feedback data start
        List<Feedback> validFeedbacks = feedbackMapper.selectByTimeRange(startTime, endTime);
        List<String> feedbackStr =
                validFeedbacks.stream().map(Feedback::toFormattedString).toList();
        templateData.put(
                "feedbacks", validFeedbacks.stream().map(Feedback::toFormattedString).toList());
        content +=
                "User feedback information:\n"
                        + feedbackStr.stream().collect(Collectors.joining("\n"));

        // Calculate review statistics
        int totalValidFeedbacks = validFeedbacks.size();
        long positiveCount = validFeedbacks.stream().filter(f -> f.getRating() == 5).count();
        long neutralCount =
                validFeedbacks.stream()
                        .filter(f -> f.getRating() >= 3 && f.getRating() <= 4)
                        .count();
        long negativeCount = validFeedbacks.stream().filter(f -> f.getRating() < 3).count();

        // Calculate percentages
        double positiveRate =
                totalValidFeedbacks > 0 ? (positiveCount * 100.0 / totalValidFeedbacks) : 0;
        double neutralRate =
                totalValidFeedbacks > 0 ? (neutralCount * 100.0 / totalValidFeedbacks) : 0;
        double negativeRate =
                totalValidFeedbacks > 0 ? (negativeCount * 100.0 / totalValidFeedbacks) : 0;

        // Calculate rating distribution (1-5 stars)
        long[] ratingDistribution = new long[5];
        for (int i = 0; i < 5; i++) {
            final int rating = i + 1;
            ratingDistribution[i] =
                    validFeedbacks.stream()
                            .filter(f -> f.getRating() != null && f.getRating() == rating)
                            .count();
        }

        // Calculate percentage distribution
        double[] ratingPercentage = new double[5];
        for (int i = 0; i < 5; i++) {
            ratingPercentage[i] =
                    totalValidFeedbacks > 0
                            ? (ratingDistribution[i] * 100.0 / totalValidFeedbacks)
                            : 0;
        }

        // Add review statistics
        templateData.put("positive_rate", String.format("%.0f", positiveRate) + "%");
        templateData.put("neutral_rate", String.format("%.0f", neutralRate) + "%");
        templateData.put("negative_rate", String.format("%.0f", negativeRate) + "%");

        // Format date and time in yyyy-MM-dd HH:mm:ss format
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        templateData.put("report_date", LocalDate.now().format(dateFormatter));
        templateData.put(
                "report_time",
                LocalDate.now().format(dateFormatter)
                        + " "
                        + LocalTime.now().format(timeFormatter));

        // Add rating distribution
        for (int i = 0; i < 5; i++) {
            templateData.put(
                    "star" + (i + 1) + "_rate", String.format("%.0f", ratingPercentage[i]));
        }
        // == Get feedback data end

        // Find the top 3 products by sales revenue
        Map<Long, BigDecimal> productSalesRevenueMap =
                todayOrders.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Order::getProductId,
                                        Collectors.reducing(
                                                BigDecimal.ZERO,
                                                Order::getTotalPrice,
                                                BigDecimal::add)));
        List<Map.Entry<Long, BigDecimal>> top3ByRevenue =
                productSalesRevenueMap.entrySet().stream()
                        .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                        .limit(3)
                        .collect(Collectors.toList());
        // Add top 3 products by sales count
        content += "\nProduct sales information:\n";
        for (int i = 0; i < 3; i++) {
            if (i < top3ByRevenue.size()) {
                Map.Entry<Long, BigDecimal> entry = top3ByRevenue.get(i);
                // Get product name from productMapper or use a default name
                String productName = "Product " + entry.getKey();
                Product product = null;
                try {
                    // Try to get the actual product name
                    product = productMapper.selectById(entry.getKey());
                    if (product != null && product.getName() != null) {
                        productName = product.getName();
                    }
                } catch (Exception e) {
                    // Use default name if product not found
                }
                templateData.put("r_product" + (i + 1), productName);
                templateData.put(
                        "r_product" + (i + 1) + "_quantity",
                        String.format("%.2f", entry.getValue()));
                // Calculate percentage of total sales
                double percentage =
                        (entry.getValue().doubleValue() * 100.0) / totalRevenue.doubleValue();
                templateData.put(
                        "r_product" + (i + 1) + "_percentage", String.format("%.1f", percentage));

                content +=
                        productName
                                + " ranked #"
                                + (i + 1)
                                + " in revenue, revenue: "
                                + String.format("%.2f", entry.getValue())
                                + ", percentage: "
                                + String.format("%.1f", percentage)
                                + "%, unit price: "
                                + (product != null ? product.getPrice() : "")
                                + ", description: "
                                + (product != null ? product.getDescription() : "")
                                + "\n";
            } else {
                templateData.put("r_product" + (i + 1), "N/A");
                templateData.put("r_product" + (i + 1) + "_quantity", 0);
                templateData.put("r_product" + (i + 1) + "_percentage", "0.0");
            }
        }

        // Find the top 3 products by sales quantity
        Map<Long, Integer> productSalesCountMap =
                todayOrders.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Order::getProductId,
                                        Collectors.summingInt(Order::getQuantity)));
        List<Map.Entry<Long, Integer>> top3BySalesCount =
                productSalesCountMap.entrySet().stream()
                        .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                        .limit(3)
                        .collect(Collectors.toList());
        for (int i = 0; i < 3; i++) {
            if (i < top3BySalesCount.size()) {
                Map.Entry<Long, Integer> entry = top3BySalesCount.get(i);
                // Get product name from productMapper or use a default name
                String productName = "Product " + entry.getKey();
                Product product = null;
                try {
                    // Try to get the actual product name
                    product = productMapper.selectById(entry.getKey());
                    if (product != null && product.getName() != null) {
                        productName = product.getName();
                    }
                } catch (Exception e) {
                    // Use default name if product not found
                }
                templateData.put("product" + (i + 1), productName);
                templateData.put("product" + (i + 1) + "_quantity", entry.getValue());
                // Calculate percentage of total sales
                double percentage = (entry.getValue() * 100.0) / todayOrderCount;
                templateData.put(
                        "product" + (i + 1) + "_percentage", String.format("%.1f", percentage));
                content +=
                        productName
                                + " ranked #"
                                + (i + 1)
                                + " in sales volume, quantity: "
                                + entry.getValue()
                                + ", percentage: "
                                + String.format("%.1f", percentage)
                                + "%, description: "
                                + (product != null ? product.getDescription() : "")
                                + "\n";
            } else {
                templateData.put("product" + (i + 1), "N/A");
                templateData.put("product" + (i + 1) + "_quantity", 0);
                templateData.put("product" + (i + 1) + "_percentage", "0.0");
            }
        }
        templateData.put("content", content);
        return templateData;
    }

    @Tool(description = "Store report document and send report via DingTalk robot")
    public String sendReport(
            @ToolParam(name = "text", description = "Business report content") String text) {
        logger.info("\n>>> Business Report:\n{}", text);

        // Save report as MD file
        try {
            saveReportToFile(text);
        } catch (IOException e) {
            logger.error("Failed to save report file", e);
        }

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> requestBody = createRequestBody("Store Business Report", text);
        String requestBodyJson = JsonUtils.getJsonCodec().toJson(requestBody);
        HttpEntity<String> request = new HttpEntity<>(requestBodyJson, headers);
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        String.format(DEFAULT_WEBHOOK_URL_TEMPLATE, accessToken),
                        request,
                        String.class);
        return response.getBody();
    }

    /**
     * Save report content as MD file
     * @param text Report content
     * @throws IOException IO exception
     */
    private void saveReportToFile(String text) throws IOException {
        // Get system user.dir property
        String userDir = System.getProperty("user.dir");

        // Create reports directory
        Path reportsDir = Paths.get(userDir, "reports");
        if (!Files.exists(reportsDir)) {
            Files.createDirectories(reportsDir);
            logger.info("Created reports directory: {}", reportsDir.toAbsolutePath());
        }

        // Generate filename (using timestamp)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String timestamp = LocalDateTime.now().format(formatter);
        String fileName = String.format("business_report_%s.md", timestamp);

        // Save file
        Path filePath = reportsDir.resolve(fileName);
        Files.writeString(
                filePath, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Report saved to: {}", filePath.toAbsolutePath());
    }

    private Map<String, Object> createRequestBody(String title, String messageContent) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("msgtype", "markdown");
        Map<String, String> markdown = new HashMap<>();
        markdown.put("title", title);
        markdown.put("text", messageContent);
        requestBody.put("markdown", markdown);
        return requestBody;
    }
}
