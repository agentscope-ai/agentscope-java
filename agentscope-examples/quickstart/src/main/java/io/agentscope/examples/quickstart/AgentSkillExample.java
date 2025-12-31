/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AgentSkillExample - Demonstrates ReActAgent using Agent Skill system for data analysis.
 *
 * <p>This example shows a complete data analysis workflow with:
 *
 * <ul>
 *   <li>Real data analysis Skill with progressive disclosure
 *   <li>Working tools for data operations (load, analyze, visualize)
 *   <li>Interactive agent that uses Skills to solve real problems
 * </ul>
 */
public class AgentSkillExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Agent Skill Example - Data Analysis Assistant",
                "This example demonstrates a ReActAgent using the Agent Skill system.\n"
                        + "The agent has access to data analysis skills and can:\n"
                        + "  - Load and analyze sales data\n"
                        + "  - Calculate statistics (mean, median, trend)\n"
                        + "  - Generate visualizations\n"
                        + "  - Create analysis reports");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create toolkit and skillBox, configure skills
        Toolkit toolkit = new Toolkit();
        SkillBox skillBox = new SkillBox(toolkit);
        setupDataAnalysisSkills(toolkit, skillBox);

        // Create ReActAgent with data analysis skills
        ReActAgent agent =
                ReActAgent.builder()
                        .name("DataAnalyst")
                        .sysPrompt(buildSystemPrompt())
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .skillBox(skillBox) // Automatically registers tools and hook
                        .memory(new InMemoryMemory())
                        .build();

        // Print example queries
        printExampleQueries();

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }

    /**
     * Setup data analysis skills and tools.
     *
     * @param toolkit configured Toolkit
     * @param skillBox configured SkillBox
     */
    private static void setupDataAnalysisSkills(Toolkit toolkit, SkillBox skillBox) {
        System.out.println("=== Setting Up Data Analysis Skills ===\n");

        // 1. Create data analysis skill
        AgentSkill dataSkill = createDataAnalysisSkill();
        // 2. Register skill with data analysis tools to skillBox
        skillBox.registration().tool(new DataAnalysisTools()).skill(dataSkill).apply();
        System.out.println("✓ Registered Skill: " + dataSkill.getName());
        System.out.println("  Description: " + dataSkill.getDescription());
        System.out.println("  Resources: " + dataSkill.getResources().size() + " files");
        System.out.println("\n✓ Registered Data Analysis Tools:");
        System.out.println("  - load_sales_data: Load sample sales data");
        System.out.println("  - calculate_statistics: Calculate mean, median, std dev");
        System.out.println("  - analyze_trend: Analyze data trends");
        System.out.println("  - generate_chart: Generate visualization description");
        System.out.println("  - create_report: Create analysis report");

        System.out.println(
                "\n✓ Skill loading tools will be automatically registered when building agent\n");
    }

    /**
     * Create data analysis skill with comprehensive instructions and resources.
     *
     * @return AgentSkill instance
     */
    private static AgentSkill createDataAnalysisSkill() {
        String skillMd =
                """
                ---
                name: data_analysis
                description: Use this skill when you need to analyze sales data, calculate statistics, identify trends, or generate reports. This skill provides comprehensive data analysis capabilities.
                ---

                # Data Analysis Skill

                ## Overview
                This skill enables you to perform comprehensive data analysis on sales data, including:
                - Loading and inspecting data
                - Statistical analysis (mean, median, standard deviation)
                - Trend analysis and pattern detection
                - Data visualization
                - Report generation

                ## When to Use This Skill
                Use this skill when the user asks to:
                - Analyze sales data or performance
                - Calculate statistics or metrics
                - Identify trends or patterns
                - Generate charts or visualizations
                - Create analysis reports

                ## Available Tools
                1. **load_sales_data**: Load sample sales data for analysis
                   - Returns: Dataset with sales records (date, product, amount, quantity)

                2. **calculate_statistics**: Calculate statistical metrics
                   - Input: field name (e.g., "amount", "quantity")
                   - Returns: mean, median, standard deviation, min, max

                3. **analyze_trend**: Analyze trends in the data
                   - Input: field name to analyze
                   - Returns: trend direction (increasing/decreasing/stable) and insights

                4. **generate_chart**: Generate chart visualization description
                   - Input: chart type (bar, line, pie) and field name
                   - Returns: Chart description and key insights

                5. **create_report**: Create comprehensive analysis report
                   - Returns: Formatted report with all analysis results

                ## Workflow
                Follow this workflow for data analysis tasks:
                1. Load data using load_sales_data
                2. Calculate statistics for relevant fields
                3. Analyze trends if needed
                4. Generate visualizations if requested
                5. Create final report summarizing findings

                ## Best Practices
                - Always load data first before analysis
                - Calculate statistics for numeric fields only
                - Provide clear interpretations of results
                - Include visualizations when helpful
                - Summarize key findings in the report

                ## Resources
                For detailed information, refer to:
                - references/statistics-guide.md: Statistical formulas and interpretations
                - references/visualization-guide.md: Chart types and best practices
                - examples/sample-analysis.md: Example analysis workflow
                """;

        Map<String, String> resources =
                Map.of(
                        "references/statistics-guide.md",
                        """
                        # Statistics Guide

                        ## Key Metrics

                        ### Mean (Average)
                        - Formula: sum(values) / count(values)
                        - Use: Central tendency measure
                        - Interpretation: Typical value in the dataset

                        ### Median
                        - Formula: Middle value when sorted
                        - Use: Robust central tendency (not affected by outliers)
                        - Interpretation: 50th percentile value

                        ### Standard Deviation
                        - Formula: sqrt(sum((x - mean)^2) / count)
                        - Use: Measure of data spread
                        - Interpretation: How much values vary from mean

                        ## Trend Analysis
                        - Increasing: Values generally going up over time
                        - Decreasing: Values generally going down over time
                        - Stable: Values remain relatively constant
                        - Volatile: Large fluctuations in values
                        """,
                        "references/visualization-guide.md",
                        """
                        # Visualization Guide

                        ## Chart Types

                        ### Bar Chart
                        - Best for: Comparing categories
                        - Example: Sales by product, revenue by region

                        ### Line Chart
                        - Best for: Showing trends over time
                        - Example: Monthly sales, daily revenue

                        ### Pie Chart
                        - Best for: Showing proportions/percentages
                        - Example: Market share, category distribution

                        ## Best Practices
                        1. Choose appropriate chart type for data
                        2. Label axes clearly
                        3. Use colors meaningfully
                        4. Include title and legend
                        5. Highlight key insights
                        """,
                        "examples/sample-analysis.md",
                        """
                        # Sample Analysis Workflow

                        ## Example: Monthly Sales Analysis

                        ### Step 1: Load Data
                        Use load_sales_data to get the dataset

                        ### Step 2: Calculate Statistics
                        - Calculate statistics for "amount" field
                        - Calculate statistics for "quantity" field

                        ### Step 3: Analyze Trends
                        - Analyze trend for "amount" over time
                        - Identify patterns and seasonality

                        ### Step 4: Visualize
                        - Generate line chart for sales trend
                        - Generate bar chart for product comparison

                        ### Step 5: Report
                        - Create comprehensive report
                        - Include key findings and recommendations
                        """);

        return SkillUtil.createFrom(skillMd, resources);
    }

    /**
     * Build system prompt with skill awareness.
     *
     * @return system prompt
     */
    private static String buildSystemPrompt() {
        return """
        You are a professional data analyst assistant with expertise in sales data analysis.

        You have access to data analysis skills and tools. When users ask about data analysis:
        1. Use the data_analysis skill to access tools and guidance
        2. Follow the recommended workflow in the skill instructions
        3. Provide clear explanations of your analysis
        4. Interpret results in business context
        5. Offer actionable insights and recommendations

        Always be thorough in your analysis and explain your reasoning clearly.
        """;
    }

    /**
     * Print example queries for users to try.
     */
    private static void printExampleQueries() {
        System.out.println("\n=== Example Queries to Try ===\n");
        System.out.println("1. \"Analyze the sales data and give me key statistics\"");
        System.out.println("2. \"What's the trend in sales amounts?\"");
        System.out.println("3. \"Show me a visualization of sales by product\"");
        System.out.println("4. \"Create a comprehensive analysis report\"");
        System.out.println("5. \"Compare the performance of different products\"");
        System.out.println("\n==================================\n");
    }

    /** Data analysis tools with real functionality. */
    public static class DataAnalysisTools {

        // Simulated sales data
        private static final List<SalesRecord> SALES_DATA =
                Arrays.asList(
                        new SalesRecord("2024-01", "Laptop", 1200.00, 5),
                        new SalesRecord("2024-01", "Mouse", 25.00, 20),
                        new SalesRecord("2024-01", "Keyboard", 75.00, 15),
                        new SalesRecord("2024-02", "Laptop", 1200.00, 8),
                        new SalesRecord("2024-02", "Mouse", 25.00, 25),
                        new SalesRecord("2024-02", "Keyboard", 75.00, 18),
                        new SalesRecord("2024-03", "Laptop", 1200.00, 12),
                        new SalesRecord("2024-03", "Mouse", 25.00, 30),
                        new SalesRecord("2024-03", "Keyboard", 75.00, 22),
                        new SalesRecord("2024-04", "Laptop", 1200.00, 10),
                        new SalesRecord("2024-04", "Mouse", 25.00, 28),
                        new SalesRecord("2024-04", "Keyboard", 75.00, 20));

        @Tool(
                name = "load_sales_data",
                description =
                        "Load sample sales data for analysis. Returns dataset with columns: date,"
                                + " product, amount, quantity")
        public String loadSalesData() {
            StringBuilder sb = new StringBuilder();
            sb.append("Sales Data Loaded Successfully\n");
            sb.append("================================\n");
            sb.append(String.format("Total Records: %d\n", SALES_DATA.size()));
            sb.append("\nSample Records:\n");
            sb.append(
                    String.format(
                            "%-10s %-15s %10s %10s\n", "Date", "Product", "Amount($)", "Quantity"));
            sb.append("--------------------------------------------------------\n");

            for (int i = 0; i < Math.min(5, SALES_DATA.size()); i++) {
                SalesRecord record = SALES_DATA.get(i);
                sb.append(
                        String.format(
                                "%-10s %-15s %10.2f %10d\n",
                                record.date, record.product, record.amount, record.quantity));
            }

            sb.append("\nData Summary:\n");
            sb.append("- Date Range: 2024-01 to 2024-04\n");
            sb.append("- Products: Laptop, Mouse, Keyboard\n");
            sb.append("- Fields: date, product, amount, quantity\n");

            return sb.toString();
        }

        @Tool(
                name = "calculate_statistics",
                description =
                        "Calculate statistical metrics (mean, median, std dev, min, max) for a"
                                + " numeric field")
        public String calculateStatistics(
                @ToolParam(
                                name = "field",
                                description = "Field name to analyze: 'amount' or 'quantity'")
                        String field) {

            if (!field.equals("amount") && !field.equals("quantity")) {
                return "Error: Invalid field. Please use 'amount' or 'quantity'";
            }

            List<Double> values =
                    SALES_DATA.stream()
                            .map(r -> field.equals("amount") ? r.amount : (double) r.quantity)
                            .sorted()
                            .collect(Collectors.toList());

            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double median =
                    values.size() % 2 == 0
                            ? (values.get(values.size() / 2 - 1) + values.get(values.size() / 2))
                                    / 2.0
                            : values.get(values.size() / 2);
            double variance =
                    values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
            double stdDev = Math.sqrt(variance);
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Statistics for '%s':\n", field));
            sb.append("================================\n");
            sb.append(String.format("Mean (Average):      %.2f\n", mean));
            sb.append(String.format("Median:              %.2f\n", median));
            sb.append(String.format("Standard Deviation:  %.2f\n", stdDev));
            sb.append(String.format("Minimum:             %.2f\n", min));
            sb.append(String.format("Maximum:             %.2f\n", max));
            sb.append(String.format("Range:               %.2f\n", max - min));
            sb.append(String.format("Sample Size:         %d\n", values.size()));

            // Interpretation
            sb.append("\nInterpretation:\n");
            double cv = (stdDev / mean) * 100; // Coefficient of variation
            if (cv < 20) {
                sb.append("- Low variability: Data points are close to the mean\n");
            } else if (cv < 50) {
                sb.append("- Moderate variability: Some spread in the data\n");
            } else {
                sb.append("- High variability: Data points are widely spread\n");
            }

            return sb.toString();
        }

        @Tool(
                name = "analyze_trend",
                description = "Analyze trend in data over time for a specific field")
        public String analyzeTrend(
                @ToolParam(
                                name = "field",
                                description = "Field name to analyze: 'amount' or 'quantity'")
                        String field) {

            if (!field.equals("amount") && !field.equals("quantity")) {
                return "Error: Invalid field. Please use 'amount' or 'quantity'";
            }

            // Group by month and sum
            Map<String, Double> monthlyData = new LinkedHashMap<>();
            for (SalesRecord record : SALES_DATA) {
                monthlyData.merge(
                        record.date,
                        field.equals("amount") ? record.amount * record.quantity : record.quantity,
                        Double::sum);
            }

            List<Double> values = new ArrayList<>(monthlyData.values());
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Trend Analysis for '%s':\n", field));
            sb.append("================================\n");

            // Calculate trend
            double firstValue = values.get(0);
            double lastValue = values.get(values.size() - 1);
            double change = lastValue - firstValue;
            double percentChange = (change / firstValue) * 100;

            sb.append("\nMonthly Data:\n");
            for (Map.Entry<String, Double> entry : monthlyData.entrySet()) {
                sb.append(String.format("  %s: %.2f\n", entry.getKey(), entry.getValue()));
            }

            sb.append("\nTrend Summary:\n");
            sb.append(String.format("  First Period: %.2f\n", firstValue));
            sb.append(String.format("  Last Period:  %.2f\n", lastValue));
            sb.append(String.format("  Change:       %.2f (%.1f%%)\n", change, percentChange));

            String trend;
            if (percentChange > 10) {
                trend = "INCREASING";
                sb.append("\n✓ Trend: INCREASING - Strong upward trend detected\n");
            } else if (percentChange < -10) {
                trend = "DECREASING";
                sb.append("\n✓ Trend: DECREASING - Downward trend detected\n");
            } else {
                trend = "STABLE";
                sb.append("\n✓ Trend: STABLE - Values remain relatively constant\n");
            }

            // Business insights
            sb.append("\nBusiness Insights:\n");
            if (trend.equals("INCREASING")) {
                sb.append("- Positive growth momentum\n");
                sb.append("- Consider increasing inventory\n");
                sb.append("- Opportunity for expansion\n");
            } else if (trend.equals("DECREASING")) {
                sb.append("- Declining performance needs attention\n");
                sb.append("- Review pricing and marketing strategies\n");
                sb.append("- Investigate root causes\n");
            } else {
                sb.append("- Stable performance\n");
                sb.append("- Maintain current strategies\n");
                sb.append("- Look for optimization opportunities\n");
            }

            return sb.toString();
        }

        @Tool(
                name = "generate_chart",
                description = "Generate chart visualization description for the data")
        public String generateChart(
                @ToolParam(name = "chart_type", description = "Chart type: 'bar', 'line', or 'pie'")
                        String chartType,
                @ToolParam(
                                name = "field",
                                description = "Field to visualize: 'amount' or 'quantity'")
                        String field) {

            StringBuilder sb = new StringBuilder();
            sb.append(
                    String.format(
                            "%s Chart: %s Analysis\n", capitalize(chartType), capitalize(field)));
            sb.append("================================\n\n");

            if (chartType.equalsIgnoreCase("bar")) {
                // Product comparison
                Map<String, Double> productData = new LinkedHashMap<>();
                for (SalesRecord record : SALES_DATA) {
                    productData.merge(
                            record.product,
                            field.equals("amount")
                                    ? record.amount * record.quantity
                                    : record.quantity,
                            Double::sum);
                }

                sb.append("Product Performance Comparison:\n\n");
                double maxValue =
                        productData.values().stream()
                                .mapToDouble(Double::doubleValue)
                                .max()
                                .orElse(1.0);

                for (Map.Entry<String, Double> entry : productData.entrySet()) {
                    int barLength = (int) ((entry.getValue() / maxValue) * 40);
                    sb.append(
                            String.format(
                                    "%-12s |%s %.2f\n",
                                    entry.getKey(), "█".repeat(barLength), entry.getValue()));
                }

                sb.append("\nKey Insights:\n");
                String topProduct =
                        productData.entrySet().stream()
                                .max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey)
                                .orElse("N/A");
                sb.append(String.format("- Top performer: %s\n", topProduct));
                sb.append("- Clear performance differences between products\n");

            } else if (chartType.equalsIgnoreCase("line")) {
                // Trend over time
                Map<String, Double> monthlyData = new LinkedHashMap<>();
                for (SalesRecord record : SALES_DATA) {
                    monthlyData.merge(
                            record.date,
                            field.equals("amount")
                                    ? record.amount * record.quantity
                                    : record.quantity,
                            Double::sum);
                }

                sb.append("Trend Over Time:\n\n");
                double maxValue =
                        monthlyData.values().stream()
                                .mapToDouble(Double::doubleValue)
                                .max()
                                .orElse(1.0);

                for (Map.Entry<String, Double> entry : monthlyData.entrySet()) {
                    int lineHeight = (int) ((entry.getValue() / maxValue) * 10);
                    sb.append(
                            String.format(
                                    "%s: %s%.2f\n",
                                    entry.getKey(),
                                    " ".repeat(lineHeight) + "●",
                                    entry.getValue()));
                }

                sb.append("\nKey Insights:\n");
                sb.append("- Time series shows clear pattern\n");
                sb.append("- Useful for identifying seasonal trends\n");

            } else if (chartType.equalsIgnoreCase("pie")) {
                // Market share
                Map<String, Double> productData = new LinkedHashMap<>();
                for (SalesRecord record : SALES_DATA) {
                    productData.merge(
                            record.product,
                            field.equals("amount")
                                    ? record.amount * record.quantity
                                    : record.quantity,
                            Double::sum);
                }

                double total = productData.values().stream().mapToDouble(Double::doubleValue).sum();
                sb.append("Market Share Distribution:\n\n");

                for (Map.Entry<String, Double> entry : productData.entrySet()) {
                    double percentage = (entry.getValue() / total) * 100;
                    sb.append(
                            String.format(
                                    "%-12s: %.1f%% (%.2f)\n",
                                    entry.getKey(), percentage, entry.getValue()));
                }

                sb.append("\nKey Insights:\n");
                sb.append("- Shows relative contribution of each product\n");
                sb.append("- Helps identify market concentration\n");
            }

            return sb.toString();
        }

        @Tool(
                name = "create_report",
                description = "Create comprehensive analysis report with all findings")
        public String createReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════════════════\n");
            sb.append("        SALES DATA ANALYSIS REPORT\n");
            sb.append("═══════════════════════════════════════════════\n\n");

            sb.append(
                    "Report Generated: "
                            + java.time.LocalDateTime.now()
                                    .format(
                                            java.time.format.DateTimeFormatter.ofPattern(
                                                    "yyyy-MM-dd HH:mm:ss"))
                            + "\n\n");

            sb.append("1. EXECUTIVE SUMMARY\n");
            sb.append("───────────────────────────────────────────────\n");
            sb.append("This report provides comprehensive analysis of sales data\n");
            sb.append("covering Q1 2024 (January to April).\n\n");

            sb.append("2. DATA OVERVIEW\n");
            sb.append("───────────────────────────────────────────────\n");
            sb.append("Total Records: " + SALES_DATA.size() + "\n");
            sb.append("Time Period: 2024-01 to 2024-04\n");
            sb.append("Products Analyzed: Laptop, Mouse, Keyboard\n\n");

            sb.append("3. KEY METRICS\n");
            sb.append("───────────────────────────────────────────────\n");

            // Calculate totals
            double totalRevenue = SALES_DATA.stream().mapToDouble(r -> r.amount * r.quantity).sum();
            int totalQuantity = SALES_DATA.stream().mapToInt(r -> r.quantity).sum();

            sb.append(String.format("Total Revenue:    $%.2f\n", totalRevenue));
            sb.append(String.format("Total Units Sold: %d\n", totalQuantity));
            sb.append(
                    String.format("Average Order:    $%.2f\n\n", totalRevenue / SALES_DATA.size()));

            sb.append("4. PRODUCT PERFORMANCE\n");
            sb.append("───────────────────────────────────────────────\n");

            Map<String, ProductStats> productStats = new LinkedHashMap<>();
            for (SalesRecord record : SALES_DATA) {
                productStats
                        .computeIfAbsent(record.product, k -> new ProductStats())
                        .add(record.amount * record.quantity, record.quantity);
            }

            for (Map.Entry<String, ProductStats> entry : productStats.entrySet()) {
                ProductStats stats = entry.getValue();
                sb.append(
                        String.format(
                                "%-12s: Revenue $%.2f, Units %d\n",
                                entry.getKey(), stats.revenue, stats.units));
            }

            sb.append("\n5. RECOMMENDATIONS\n");
            sb.append("───────────────────────────────────────────────\n");
            sb.append("• Focus on high-performing products\n");
            sb.append("• Monitor trends for early warning signs\n");
            sb.append("• Consider seasonal promotions\n");
            sb.append("• Optimize inventory based on demand patterns\n\n");

            sb.append("═══════════════════════════════════════════════\n");
            sb.append("              END OF REPORT\n");
            sb.append("═══════════════════════════════════════════════\n");

            return sb.toString();
        }

        private static String capitalize(String str) {
            return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
        }
    }

    /** Sales record data structure. */
    static class SalesRecord {
        String date;
        String product;
        double amount;
        int quantity;

        SalesRecord(String date, String product, double amount, int quantity) {
            this.date = date;
            this.product = product;
            this.amount = amount;
            this.quantity = quantity;
        }
    }

    /** Product statistics helper. */
    static class ProductStats {
        double revenue = 0;
        int units = 0;

        void add(double rev, int qty) {
            revenue += rev;
            units += qty;
        }
    }
}
