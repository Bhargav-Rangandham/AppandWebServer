package com.hotel.web.finance;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.DecimalFormat;
import java.util.*;

public class GetPartnerFinanceHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public GetPartnerFinanceHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private static final DecimalFormat df = new DecimalFormat("#.##");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS & allowed methods
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) &&
            !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String partnerId;
        try {
            partnerId = extractPartnerId(exchange);
        } catch (Exception e) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"invalid request\"}");
            return;
        }

        if (partnerId == null || partnerId.isBlank()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"partner_id is required\"}");
            return;
        }
        partnerId = partnerId.trim();

        try {
            // 1) Fetch partner finance row (BANK + commission + paid/pending etc.)
            Map<String, Object> partnerMap = fetchPartnerFinanceRow(partnerId);
            if (partnerMap.isEmpty()) {
                sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Partner not found\"}");
                return;
            }

            // 2) Commission percentage MUST come from DB (Partner_Finance.Commission_Percentage).
            //    If DB returns NULL treat as 0.0 (do NOT use any other hard-coded fallback).
            double commissionPercent = 0.0;
            Object cpObj = partnerMap.get("Commission_Percentage");
            if (cpObj != null) {
                try { commissionPercent = Double.parseDouble(cpObj.toString()); }
                catch (Exception ignored) { commissionPercent = 0.0; }
            }

            // 3) Fetch bookings for partner and compute totals using commissionPercent
            BookingAggregation agg = fetchBookingsAndCompute(partnerId, commissionPercent);

            // Recognized totals (only COMPLETED bookings)
            double recognizedRevenue = agg.recognizedRevenue;
            double provisionalRevenue = agg.provisionalRevenue;

            double commissionAmount = recognizedRevenue * commissionPercent / 100.0;
            double netRevenue = recognizedRevenue - commissionAmount;

            double paidPayout = toDouble(partnerMap.getOrDefault("Paid_Payout", 0.0));
            double pendingPayout = Math.max(0.0, netRevenue - paidPayout);

            // 5) Build response
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("partner_id", partnerId);

            // Recognized (authoritative) revenue — COMPLETED only
            result.put("Total_Revenue", df.format(recognizedRevenue));
            // Provisional (informational) revenue — PENDING + CONFIRMED
            result.put("Provisional_Revenue", df.format(provisionalRevenue));

            result.put("Commission_Percentage", df.format(commissionPercent));
            result.put("Commission_Amount", df.format(commissionAmount));
            result.put("Net_Revenue", df.format(netRevenue));
            result.put("Pending_Payout", df.format(pendingPayout));
            result.put("Paid_Payout", df.format(paidPayout));

            // Counts
            result.put("Total_Bookings", agg.count);
            result.put("Completed_Bookings", agg.completed);
            result.put("Cancelled_Bookings", agg.cancelled);
            result.put("Provisional_Bookings", agg.provisionalCount);

            // Bank details - map DB columns to frontend keys (safe defaults)
            result.put("Account_Holder_Name", partnerMap.getOrDefault("Account_Holder_Name", ""));
            result.put("Bank_Name", partnerMap.getOrDefault("Bank_Name", ""));
            result.put("Account_Number", partnerMap.getOrDefault("Account_Number", ""));
            result.put("IFSC_SWIFT", partnerMap.getOrDefault("IFSC_SWIFT", ""));
            result.put("Account_Type", partnerMap.getOrDefault("Account_Type", ""));
            result.put("PAN_Tax_ID", partnerMap.getOrDefault("PAN_Tax_ID", ""));
            result.put("Payout_Type", partnerMap.getOrDefault("Payout_Type", ""));
            result.put("Last_Payout_Date", partnerMap.getOrDefault("Last_Payout_Date", ""));

            // Attach per-booking list (all statuses). Per-booking Commission_Amount/Net_Revenue computed.
            result.put("Bookings", agg.bookings);

            sendResponse(exchange, 200, toJson(result));

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    // Fetch partner finance row from partner_info.Partner_Finance
    private Map<String, Object> fetchPartnerFinanceRow(String partnerId) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();

        String sql = """
                SELECT Partner_ID, Account_Holder_Name, Bank_Name, Account_Number,
                       IFSC_SWIFT, Account_Type, PAN_Tax_ID, Payout_Type,
                       Total_Revenue, Commission_Percentage, Net_Revenue,
                       Pending_Payout, Paid_Payout, Last_Payout_Date
                FROM Partner_Finance
                WHERE Partner_ID = ?
                """;

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, partnerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return map;
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                for (int i = 1; i <= cols; i++) {
                    String col = md.getColumnLabel(i);
                    Object val = rs.getObject(i);
                    map.put(col, val != null ? val : null);
                }
            }
        }
        return map;
    }

    // Query bookings_info for partner and compute per-booking commission/net and aggregates
    private BookingAggregation fetchBookingsAndCompute(String partnerId, double commissionPercent) throws Exception {
        BookingAggregation agg = new BookingAggregation();

        // Select relevant columns from bookings_info. Include Total_Price (revenue).
        String sql = """
                SELECT Booking_ID, Hotel_ID, Hotel_Name, Hotel_Type, Guest_Name, Email, User_ID,
                       Check_In_Date, Check_Out_Date, Guest_Count, Adults, Children, Total_Rooms_Booked,
                       Total_Days_at_Stay, Room_Price_Per_Day, All_Days_Price, GST, Original_Amount, Final_Payable_Amount,
                       Amount_Paid_Online, Due_Amount_At_Hotel, Payment_Method_Type, Paid_Via, Transaction_ID, Hotel_Address, 
                       Booking_Status, Hotel_Contact, Payment_Status, Refund_Status, Wallet_Used, Wallet_Amount_Deducted,
                       Coupon_Code, Coupon_Discount_Amount, Room_Price_Per_Month, Months
                FROM bookings_info
                WHERE Partner_ID = ?
                ORDER BY Check_In_Date DESC
                """;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, partnerId);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();

                while (rs.next()) {
                    agg.count++;

                    double totalPrice = 0.0;
                    try {
                        Object tpObj = rs.getObject("Original_Amount");
                        if (tpObj != null) totalPrice = Double.parseDouble(tpObj.toString());
                    } catch (Exception ignored) { totalPrice = 0.0; }

                    String status = rs.getString("Booking_Status");
                    if (status != null) {
                        if (status.equalsIgnoreCase("CANCELLED")) agg.cancelled++;
                        else if (status.equalsIgnoreCase("COMPLETED")) {
                            agg.completed++;
                            // Recognized revenue only from completed bookings
                            agg.recognizedRevenue += totalPrice;
                        } else if (status.equalsIgnoreCase("PENDING") || status.equalsIgnoreCase("CONFIRMED")) {
                            agg.provisionalCount++;
                            agg.provisionalRevenue += totalPrice;
                        } else {
                            // other statuses treat as provisional (you can refine)
                            agg.provisionalCount++;
                            agg.provisionalRevenue += totalPrice;
                        }
                    } else {
                        agg.provisionalCount++;
                        agg.provisionalRevenue += totalPrice;
                    }

                    Map<String, Object> booking = new LinkedHashMap<>();
                    // add all selected columns to booking map
                    for (int i = 1; i <= colCount; i++) {
                        String colLabel = md.getColumnLabel(i);
                        Object val = rs.getObject(i);
                        booking.put(colLabel, val != null ? val : "");
                    }

                    // compute commission & net for this booking using commissionPercent from partner table
                    // We compute per-booking commission/net for visibility; totals only include completed as above.
                    double commissionAmt = totalPrice * commissionPercent / 100.0;
                    double netAmt = totalPrice - commissionAmt;

                    booking.put("Commission_Amount", df.format(commissionAmt));
                    booking.put("Net_Revenue", df.format(netAmt));

                    agg.bookings.add(booking);
                }
            }
        }

        return agg;
    }

    // Helper to extract partner_id from GET query or POST form
    private String extractPartnerId(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = readBody(exchange);
            Map<String, String> map = parseForm(body);
            return map.get("partner_id");
        } else {
            Map<String, String> q = queryToMap(exchange.getRequestURI().getQuery());
            return q.get("partner_id");
        }
    }

    // Small aggregator class
    private static class BookingAggregation {
        double recognizedRevenue = 0.0; // sum of completed bookings
        double provisionalRevenue = 0.0; // sum of pending/confirmed
        int count = 0; // total bookings returned
        int completed = 0;
        int cancelled = 0;
        int provisionalCount = 0;
        List<Map<String, Object>> bookings = new ArrayList<>();
    }

    // ---------- Helpers ----------
    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String k = URLDecoder.decode(kv[0], "UTF-8");
                String v = URLDecoder.decode(kv[1], "UTF-8");
                map.put(k, v);
            }
        }
        return map;
    }

    private Map<String, String> queryToMap(String query) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
            }
        }
        return map;
    }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n","\\n")
                .replace("\r","\\r")
                .replace("\t","\\t");
    }

    // Simple JSON serializer (keeps same style as your original)
    private String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            Map<?,?> map = (Map<?,?>) obj;
            for (Map.Entry<?,?> e : map.entrySet()) {
                sb.append("\"").append(escape(String.valueOf(e.getKey()))).append("\":");
                sb.append(toJson(e.getValue())).append(",");
            }
            if (sb.charAt(sb.length()-1)==',') sb.setLength(sb.length()-1);
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) obj;
            for (Object o : list) {
                sb.append(toJson(o)).append(",");
            }
            if (sb.charAt(sb.length()-1)==',') sb.setLength(sb.length()-1);
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return String.valueOf(obj);
        } else {
            return "\"" + escape(String.valueOf(obj)) + "\"";
        }
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
