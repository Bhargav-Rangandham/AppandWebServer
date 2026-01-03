package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class BookingHandler implements HttpHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DbConfig dbConfig;

    public BookingHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/booking") && exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                handleBooking(exchange);
            } else if (path.equals("/updatePayment") && exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                handleUpdatePayment(exchange);
            } else {
                sendResponse(exchange, 404, json("error", "Invalid endpoint: " + path));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private void handleBooking(HttpExchange exchange) throws IOException {
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody()))
                .lines().collect(Collectors.joining("\n"));

        Map<String, Object> data;
        try {
            data = objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            sendResponse(exchange, 400, json("error", "Invalid JSON payload"));
            return;
        }

        boolean isPgMode = data.containsKey("Selected_Room_Type") || data.containsKey("Monthly_Price");
        String bookingId = generateBookingId();
        String userId = str(data.get("User_ID"));

        // Prices
        double originalAmount = toDouble(data.getOrDefault("Total_Price", data.get("Original_Total_Price")));
        double finalAmount = toDouble(data.get("Final_Payable_Amount"));
        double amountPaidOnline = toDouble(data.get("Amount_Paid_Online"));
        double dueAtHotel = toDouble(data.get("Due_Amount_At_Hotel"));

        // Payment Info Logic
        String paymentMethodType = str(data.get("Payment_Method_Type"));
        if (paymentMethodType.isEmpty()) {
            paymentMethodType = str(data.get("Payment_Type"));
        }
        
        boolean isOffline = paymentMethodType.equalsIgnoreCase("Pay at Hotel") || paymentMethodType.equalsIgnoreCase("Offline");
        
        // TRANSACTION ID LOGIC: 
        // 1. If UI provides an ID (even "NA"), use it.
        // 2. If UI provides nothing and it's Online, generate one.
        // 3. If UI provides nothing and it's Offline, set "NA".
        String transactionId = str(data.get("Transaction_ID"));
        if (transactionId.isEmpty()) {
            if (isOffline) {
                transactionId = "NA";
            } else {
                transactionId = "TXN" + System.currentTimeMillis();
            }
        }

        String paidVia = str(data.get("Paid_Via"));
        String paymentStatus = normalizePaymentStatus(str(data.get("Payment_Status")));

        // Wallet + Coupon Logic
        double walletRequested = toDouble(data.get("Wallet_Amount"));
        String walletFlagRequest = str(data.getOrDefault("Wallet_Used", "No"));
        String couponCode = str(data.get("Coupon_Code"));
        double couponDiscount = toDouble(data.get("Coupon_Discount_Amount"));

        double actualWalletDebited = 0;
        Connection conn = null;

        try {
            conn = dbConfig.getCustomerDataSource().getConnection();
            conn.setAutoCommit(false);

            // STRICT RULE: Wallet and Coupon allowed ONLY for Online payments
            if (!isOffline) {
                if ("Yes".equalsIgnoreCase(walletFlagRequest) && walletRequested > 0 && !userId.isBlank()) {
                    actualWalletDebited = handleWalletUsage(conn, userId, bookingId, walletRequested, originalAmount);
                }
                if (!couponCode.isEmpty()) {
                    handleCouponUsage(conn, userId, couponCode);
                }
            } else {
                // Force reset if UI accidentally sent them for offline
                actualWalletDebited = 0;
                couponCode = "";
                couponDiscount = 0;
            }

            String sql = """
                INSERT INTO bookings_info (
                  Partner_ID, Hotel_ID, Booking_ID, Hotel_Name, Hotel_Type, Guest_Name, Email, User_ID,
                  Check_In_Date, Check_Out_Date, Guest_Count, Adults, Children, Total_Rooms_Booked,
                  Total_Days_at_Stay, Room_Price_Per_Day, All_Days_Price, GST,
                  Original_Amount, Final_Payable_Amount, Amount_Paid_Online, Due_Amount_At_Hotel,
                  Payment_Method_Type, Paid_Via, Payment_Status, Transaction_ID,
                  Wallet_Used, Wallet_Amount_Deducted, Coupon_Code, Coupon_Discount_Amount,
                  Room_Type, Room_Price_Per_Month, Months, Hotel_Address, Hotel_Contact
                )
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, str(data.get("Partner_ID")));
                ps.setString(2, str(data.get("Hotel_ID")));
                ps.setString(3, bookingId);
                ps.setString(4, str(data.get("Hotel_Name")));
                ps.setString(5, isPgMode ? "PG" : str(data.get("Hotel_Type")));
                ps.setString(6, str(data.get("Guest_Name")));
                ps.setString(7, str(data.get("Email")));
                ps.setString(8, userId);
                ps.setDate(9, parseSqlDate(data.get("Check_In_Date")));
                ps.setDate(10, parseSqlDate(data.get("Check_Out_Date")));

                if (!isPgMode) {
                    ps.setInt(11, toInt(data.get("Guest_Count")));
                    ps.setInt(12, toInt(data.get("Adults")));
                    ps.setInt(13, toInt(data.get("Children")));
                    ps.setInt(14, toInt(data.get("Total_Rooms_Booked")));
                    ps.setInt(15, toInt(data.get("Total_Days_at_Stay")));
                    ps.setDouble(16, toDouble(data.get("Room_Price_Per_Day")));
                } else {
                    ps.setInt(11, toInt(data.get("Persons")));
                    ps.setInt(12, toInt(data.get("Persons")));
                    ps.setInt(13, 0);
                    ps.setInt(14, 1);
                    ps.setInt(15, toInt(data.get("Months")));
                    ps.setDouble(16, 0);
                }

                ps.setDouble(17, toDouble(data.getOrDefault("All_Days_Price", data.get("All_Months_Price"))));
                ps.setDouble(18, toDouble(data.get("GST")));
                ps.setDouble(19, originalAmount);
                ps.setDouble(20, finalAmount);
                ps.setDouble(21, amountPaidOnline);
                ps.setDouble(22, dueAtHotel);
                ps.setString(23, isOffline ? "Offline" : "Online");
                ps.setString(24, paidVia);
                ps.setString(25, paymentStatus);
                ps.setString(26, transactionId);
                ps.setString(27, actualWalletDebited > 0 ? "Yes" : "No");
                ps.setDouble(28, actualWalletDebited);
                ps.setString(29, couponCode);
                ps.setDouble(30, couponDiscount);
                ps.setString(31, str(data.getOrDefault("Room_Type", data.get("Selected_Room_Type"))));
                ps.setString(32, str(data.getOrDefault("Room_Price_Per_Month", data.get("Selected_Room_Price"))));
                ps.setInt(33, toInt(data.getOrDefault("Months", 1)));
                ps.setString(34, str(data.get("Hotel_Address")));
                ps.setString(35, str(data.get("Hotel_Contact")));

                ps.executeUpdate();
            }

            conn.commit();
            sendResponse(exchange, 200, json("message", "Success", "booking_id", bookingId));

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) {}
            e.printStackTrace();
            sendResponse(exchange, 500, json("error", e.getMessage()));
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private double handleWalletUsage(Connection conn, String userId, String bId, double req, double total) throws SQLException {
        double maxAllowed = total * 0.5;
        double finalReq = Math.min(req, maxAllowed);
        double balance = 0;
        String wId = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT wallet_id, balance FROM wallets WHERE user_id=?")) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                wId = rs.getString("wallet_id");
                balance = rs.getDouble("balance");
            }
        }
        if (wId == null || balance <= 0) return 0;
        double debit = Math.min(balance, finalReq);
        if (debit <= 0) return 0;
        try (PreparedStatement ps = conn.prepareStatement("UPDATE wallets SET balance = balance - ? WHERE wallet_id = ?")) {
            ps.setDouble(1, debit);
            ps.setString(2, wId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO wallet_transactions (txn_id, wallet_id, type, amount, direction, reference_id, status, description, balance_after_txn) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, wId);
            ps.setString(3, "booking_payment");
            ps.setDouble(4, debit);
            ps.setString(5, "debit");
            ps.setString(6, bId);
            ps.setString(7, "success");
            ps.setString(8, "Booking " + bId);
            ps.setDouble(9, balance - debit);
            ps.executeUpdate();
        }
        return debit;
    }

    private void handleCouponUsage(Connection conn, String uId, String code) throws SQLException {
        String cId = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT coupon_id FROM coupons WHERE coupon_code=?")) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) cId = rs.getString("coupon_id");
        }
        if (cId == null) return;
        String sql = "INSERT INTO coupon_usage (usage_id, coupon_id, user_id, usage_count) VALUES (?,?,?,1) " +
                     "ON DUPLICATE KEY UPDATE usage_count = usage_count + 1, last_used_at = NOW()";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, cId);
            ps.setString(3, uId);
            ps.executeUpdate();
        }
    }

    private void handleUpdatePayment(HttpExchange exchange) throws IOException {
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody())).lines().collect(Collectors.joining("\n"));
        Map<String, Object> payload = objectMapper.readValue(body, Map.class);
        String bId = str(payload.get("Booking_ID"));
        String status = normalizePaymentStatus(str(payload.get("Payment_Status")));
        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE bookings_info SET Payment_Status=? WHERE Booking_ID=?")) {
            ps.setString(1, status);
            ps.setString(2, bId);
            ps.executeUpdate();
            sendResponse(exchange, 200, json("message", "Updated"));
        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    private String generateBookingId() { return "BKG" + (100000 + new Random().nextInt(900000)); }
    private double toDouble(Object o) { if (o == null) return 0; try { return Double.parseDouble(o.toString().replace(",", "")); } catch (Exception e) { return 0; } }
    private int toInt(Object o) { if (o == null) return 0; try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; } }
    private String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private String normalizePaymentStatus(String s) {
        if (s == null || s.isEmpty()) return "Pending";
        String val = s.toLowerCase();
        if (val.contains("paid") || val.contains("success")) return "Paid";
        if (val.contains("failed")) return "Failed";
        return "Pending";
    }

    private java.sql.Date parseSqlDate(Object val) {
        if (val == null) return null;
        String input = val.toString().trim().replace("/", "-").replace(".", "-");
        try {
            if (input.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) return java.sql.Date.valueOf(input);
            if (input.matches("\\d{1,2}-\\d{1,2}-\\d{4}")) {
                String[] p = input.split("-");
                return java.sql.Date.valueOf(p[2] + "-" + String.format("%02d", Integer.parseInt(p[1])) + "-" + String.format("%02d", Integer.parseInt(p[0])));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void sendResponse(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
    }

    private String json(String k, String v) { return "{\"" + k + "\":\"" + v + "\"}"; }
    private String json(String k, String v, String k2, String v2) { return "{\"" + k + "\":\"" + v + "\",\"" + k2 + "\":\"" + v2 + "\"}"; }
}