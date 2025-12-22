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

    // ✅ Inject DbConfig via constructor
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

    // ============================================================
    // HANDLE BOOKING
    // ============================================================
    private void handleBooking(HttpExchange exchange) throws IOException {

        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody()))
                .lines().collect(Collectors.joining("\n"));

        Map<String, Object> data;
        try {
            data = objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            sendResponse(exchange, 400, json("error", "Invalid JSON payload: " + e.getMessage()));
            return;
        }

        boolean isPgMode = data.containsKey("Selected_Room_Type") || data.containsKey("Monthly_Price");

        String bookingId = generateBookingId();
        data.put("Booking_ID", bookingId);

        String userId = str(data.get("User_ID"));

        // Prices
        double originalAmount = toDouble(data.getOrDefault("Total_Price", data.get("Original_Total_Price")));
        double finalAmount = toDouble(data.get("Final_Payable_Amount"));
        double amountPaidOnline = toDouble(data.get("Amount_Paid_Online"));
        double dueAtHotel = toDouble(data.get("Due_Amount_At_Hotel"));

        // Wallet + Coupons
        double walletRequested = toDouble(data.get("Wallet_Amount"));
        String walletFlag = str(data.getOrDefault("Wallet_Used", "No"));

        double couponDiscount = toDouble(data.get("Coupon_Discount_Amount"));
        String couponCode = str(data.get("Coupon_Code"));

        String paymentMethodType = str(data.get("Payment_Type"));
        String paidVia = str(data.get("Paid_Via"));
        String paymentStatus = normalizePaymentStatus(str(data.get("Payment_Status")));

        // Auto generate transaction ID only for online
        String transactionId = null;
        if (!paymentMethodType.equalsIgnoreCase("Pay at Hotel")) {
            transactionId = "TXN" + System.currentTimeMillis();
        }

        // Ensure wallet rule still applies
        if (originalAmount > 0 && walletRequested > 0) {
            double maxWalletAllowed = originalAmount * 0.5;
            if (walletRequested > maxWalletAllowed) {
                walletRequested = maxWalletAllowed;
            }
        }

        double actualWalletDebited = 0;

        Connection conn = null;

        try {
        	conn = dbConfig.getCustomerDataSource().getConnection();
            conn.setAutoCommit(false);

            boolean isPayAtHotel = "Pay at Hotel".equalsIgnoreCase(paymentMethodType);

            if (!isPayAtHotel && "Yes".equalsIgnoreCase(walletFlag) && walletRequested > 0 && !userId.isBlank()) {
                actualWalletDebited = handleWalletUsage(conn, userId, bookingId, walletRequested);
            }

            if (!couponCode.isEmpty()) {
                handleCouponUsage(conn, userId, couponCode);
            }

            // FINAL FIXED SQL → 34 VALUES ONLY
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
                    int persons = toInt(data.get("Persons"));
                    int months = toInt(data.get("Months"));

                    ps.setInt(11, persons);
                    ps.setInt(12, persons);
                    ps.setInt(13, 0);
                    ps.setInt(14, 1);
                    ps.setInt(15, months);
                    ps.setDouble(16, 0);
                }

                ps.setDouble(17, toDouble(data.getOrDefault("All_Days_Price", data.get("All_Months_Price"))));
                ps.setDouble(18, toDouble(data.get("GST")));

                ps.setDouble(19, originalAmount);
                ps.setDouble(20, finalAmount);
                ps.setDouble(21, amountPaidOnline);
                ps.setDouble(22, dueAtHotel);

                ps.setString(23, paymentMethodType);
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
            sendResponse(exchange, 200, json("message", "Booking stored successfully", "booking_id", bookingId));

        } catch (Exception e) {
            e.printStackTrace();
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            sendResponse(exchange, 500, json("error", "Booking failed: " + e.getMessage()));
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private java.sql.Date parseSqlDate(Object val) {
        if (val == null) return null;

        String input = val.toString().trim();

        try {
            // Normalize separators
            input = input.replace("/", "-").replace(".", "-");

            // Already correct format: yyyy-MM-dd
            if (input.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String[] p = input.split("-");
                String yyyy = p[0];
                String mm = String.format("%02d", Integer.parseInt(p[1]));
                String dd = String.format("%02d", Integer.parseInt(p[2]));
                return java.sql.Date.valueOf(yyyy + "-" + mm + "-" + dd);
            }

            // Format like dd-MM-yyyy or d-M-yyyy
            if (input.matches("\\d{1,2}-\\d{1,2}-\\d{4}")) {
                String[] p = input.split("-");
                String dd = String.format("%02d", Integer.parseInt(p[0]));
                String mm = String.format("%02d", Integer.parseInt(p[1]));
                String yyyy = p[2];
                return java.sql.Date.valueOf(yyyy + "-" + mm + "-" + dd);
            }

            // Format like MM-dd-yyyy (USA)
            if (input.matches("\\d{1,2}-\\d{1,2}-\\d{4}")) {
                String[] p = input.split("-");
                String mm = String.format("%02d", Integer.parseInt(p[0]));
                String dd = String.format("%02d", Integer.parseInt(p[1]));
                String yyyy = p[2];
                return java.sql.Date.valueOf(yyyy + "-" + mm + "-" + dd);
            }

        } catch (Exception e) {
            System.err.println("Invalid date format: " + input + " (" + e.getMessage() + ")");
        }

        return null;
    }


    // WALLET HANDLING
    private double handleWalletUsage(Connection conn, String userId, String bookingId, double requestedAmount) throws SQLException {

        if (requestedAmount <= 0) return 0.0;

        String walletId = null;
        double balance = 0.0;

        PreparedStatement ps = conn.prepareStatement("SELECT wallet_id, balance FROM wallets WHERE user_id=? LIMIT 1");
        ps.setString(1, userId);
        ResultSet rs = ps.executeQuery();

        if (rs.next()) {
            walletId = rs.getString("wallet_id");
            balance = rs.getDouble("balance");
        }

        if (walletId == null) {
            walletId = UUID.randomUUID().toString();
            ps = conn.prepareStatement("INSERT INTO wallets(wallet_id,user_id,balance,status) VALUES(?,?,0.00,'active')");
            ps.setString(1, walletId);
            ps.setString(2, userId);
            ps.executeUpdate();
        }

        double debit = Math.min(balance, requestedAmount);
        double newBalance = balance - debit;

        ps = conn.prepareStatement("UPDATE wallets SET balance=? WHERE wallet_id=?");
        ps.setDouble(1, newBalance);
        ps.setString(2, walletId);
        ps.executeUpdate();

        if (debit > 0) {
            ps = conn.prepareStatement("""
                    INSERT INTO wallet_transactions 
                    (txn_id, wallet_id, type, amount, direction, reference_id, status, description, balance_after_txn)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """);
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, walletId);
            ps.setString(3, "booking_payment");
            ps.setDouble(4, debit);
            ps.setString(5, "debit");
            ps.setString(6, bookingId);
            ps.setString(7, "success");
            ps.setString(8, "Wallet used for booking " + bookingId);
            ps.setDouble(9, newBalance);
            ps.executeUpdate();
        }

        return debit;
    }

    // COUPON TRACKING
    private void handleCouponUsage(Connection conn, String userId, String code) throws SQLException {

        PreparedStatement ps = conn.prepareStatement("SELECT coupon_id FROM coupons WHERE coupon_code=? LIMIT 1");
        ps.setString(1, code);
        ResultSet rs = ps.executeQuery();

        String couponId = null;
        if (rs.next()) couponId = rs.getString("coupon_id");
        if (couponId == null) return;

        ps = conn.prepareStatement("SELECT usage_id FROM coupon_usage WHERE coupon_id=? AND user_id=? LIMIT 1");
        ps.setString(1, couponId);
        ps.setString(2, userId);
        rs = ps.executeQuery();

        if (rs.next()) {
            ps = conn.prepareStatement("UPDATE coupon_usage SET usage_count=usage_count+1, last_used_at=NOW() WHERE coupon_id=? AND user_id=?");
            ps.setString(1, couponId);
            ps.setString(2, userId);
        } else {
            ps = conn.prepareStatement("INSERT INTO coupon_usage(usage_id,coupon_id,user_id,usage_count) VALUES(?,?,?,1)");
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, couponId);
            ps.setString(3, userId);
        }
        ps.executeUpdate();
    }

    private void handleUpdatePayment(HttpExchange exchange) throws IOException {

        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody()))
                .lines().collect(Collectors.joining("\n"));

        Map<String, Object> payload = objectMapper.readValue(body, Map.class);

        String bookingId = str(payload.get("Booking_ID"));
        String newPaymentStatus = normalizePaymentStatus(str(payload.get("Payment_Status")));

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE bookings_info SET Payment_Status=? WHERE Booking_ID=?")) {

            ps.setString(1, newPaymentStatus);
            ps.setString(2, bookingId);
            ps.executeUpdate();

        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
            return;
        }

        sendResponse(exchange, 200, json("message", "Payment updated successfully"));
    }


    // UTIL HELPERS
    private String generateBookingId() {
        return "BKG" + (100000 + new Random().nextInt(900000));
    }

    private double toDouble(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(o.toString().replace(",", "")); } catch (Exception e) { return 0; }
    }

    private int toInt(Object o) {
        if (o == null) return 0;
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }

    private String normalizePaymentStatus(String status) {
        if (status == null) return "Pending";
        status = status.toLowerCase();
        if (status.contains("paid") || status.contains("success")) return "Paid";
        if (status.contains("failed")) return "Failed";
        return "Pending";
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
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private String json(String k, String v) {
        return "{\"" + k + "\":\"" + v + "\"}";
    }

    private String json(String k1, String v1, String k2, String v2) {
        return "{\"" + k1 + "\":\"" + v1 + "\", \"" + k2 + "\":\"" + v2 + "\"}";
    }
}
