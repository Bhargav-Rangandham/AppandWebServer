package com.hotel.web.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.Date;

public class WebDashBoardHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public WebDashBoardHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        setCORS(exchange);

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendResponse(exchange, 405, "Invalid Method");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");

        String partnerId = (parts.length >= 4) ? parts[3] : null;

        if (partnerId == null || partnerId.isEmpty()) {
            sendResponse(exchange, 400, "Missing partnerId");
            return;
        }

        try {
            Map<String, Object> json = buildDashboardData(partnerId);
            sendJson(exchange, json);
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "Server Error: " + e.getMessage());
        }
    }

    // ======================= BUILD RESPONSE =======================

    private Map<String, Object> buildDashboardData(String partnerId) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();

        BookingData b = getBookingStats(partnerId);
        FinanceData f = getFinanceStats(partnerId);
        String payoutStatus = getLatestPayoutStatus(partnerId);

        map.put("totalBookings", b.total);
        map.put("pending", b.pending);
        map.put("confirmed", b.confirmed);
        map.put("cancelled", b.cancelled);
        map.put("completed", b.completed);

        map.put("totalRevenue", f.totalRevenue);
        map.put("netRevenue", f.netRevenue);
        map.put("pendingPayout", f.pendingPayout);
        map.put("paidPayout", f.paidPayout);
        map.put("payoutStatus", payoutStatus);

        // Booking Notification
        map.put("pendingNotifications", b.pending > 0 ? 1 : 0);

        // Finance Notification → ONLY for Success or Failed
        boolean showFinanceNotification =
                payoutStatus != null &&
                (payoutStatus.equalsIgnoreCase("Success") ||
                 payoutStatus.equalsIgnoreCase("Failed"));

        map.put("financeNotifications", showFinanceNotification ? 1 : 0);

        map.put("lastUpdated", new Date().toString());

        return map;
    }

    // ======================= BOOKING STATS =======================

    private BookingData getBookingStats(String partnerId) throws Exception {

        String sql =
                """
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN Booking_Status='PENDING'    THEN 1 ELSE 0 END) AS pending,
                    SUM(CASE WHEN Booking_Status='CONFIRMED'  THEN 1 ELSE 0 END) AS confirmed,
                    SUM(CASE WHEN Booking_Status='COMPLETED'  THEN 1 ELSE 0 END) AS completed,
                    SUM(CASE WHEN Booking_Status='CANCELLED'  THEN 1 ELSE 0 END) AS cancelled
                FROM bookings_info
                WHERE Partner_ID = ?
                """;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, partnerId);
            ResultSet rs = stmt.executeQuery();

            BookingData d = new BookingData();
            if (rs.next()) {
                d.total     = rs.getInt("total");
                d.pending   = rs.getInt("pending");
                d.confirmed = rs.getInt("confirmed");
                d.completed = rs.getInt("completed");
                d.cancelled = rs.getInt("cancelled");
            }
            return d;
        }
    }

    // ======================= FINANCE STATS =======================

    private FinanceData getFinanceStats(String partnerId) throws Exception {
        String sql =
                """
                SELECT Total_Revenue, Net_Revenue, Pending_Payout, Paid_Payout
                FROM Partner_Finance
                WHERE Partner_ID = ?
                """;

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, partnerId);
            ResultSet rs = stmt.executeQuery();

            FinanceData f = new FinanceData();
            if (rs.next()) {
                f.totalRevenue  = rs.getDouble("Total_Revenue");
                f.netRevenue    = rs.getDouble("Net_Revenue");
                f.pendingPayout = rs.getDouble("Pending_Payout");
                f.paidPayout    = rs.getDouble("Paid_Payout");
            }
            return f;
        }
    }
    
    // ======================= GET LATEST PAYOUT STATUS =======================

    private String getLatestPayoutStatus(String partnerId) throws Exception {

        String sql =
                """
                SELECT Status
                FROM Partner_Transactions
                WHERE Partner_ID = ?
                AND Transaction_Type = 'PAYOUT'
                ORDER BY Transaction_Date DESC
                LIMIT 1
                """;

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, partnerId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getString("Status");  // Pending, Requested, Success, Failed
            }
        }
        return null;
    }

    // ======================= DATA MODELS =======================

    static class BookingData {
        int total;
        int pending;
        int confirmed;
        int cancelled;
        int completed;
    }

    static class FinanceData {
        double totalRevenue;
        double netRevenue;
        double pendingPayout;
        double paidPayout;
    }

    // ======================= RESPONSE HELPERS =======================

    private void sendJson(HttpExchange ex, Object obj) throws IOException {
        String json = new ObjectMapper().writeValueAsString(obj);
        byte[] out  = json.getBytes();

        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private void sendResponse(HttpExchange ex, int code, String msg) throws IOException {
        byte[] out = msg.getBytes();
        ex.sendResponseHeaders(code, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private void setCORS(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
}
