package com.hotel.web.finance;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.sql.Date;
import java.util.*;

public class RequestPayoutHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public RequestPayoutHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private static final double MIN_WITHDRAWAL = 5000.0;
    private static final double FALLBACK_COMMISSION_PERCENT = 15.0;

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = readBody(exchange);
        Map<String, String> params = parseForm(body);

        String partnerId = params.get("partner_id");
        double requestedAmount;

        try {
            requestedAmount = Double.parseDouble(params.getOrDefault("amount", "0"));
        } catch (Exception e) {
            requestedAmount = 0;
        }

        /** DEFAULT COMMENT AUTO HANDLING **/
        String comments = params.getOrDefault("comments", "").trim();
        if (comments.isEmpty())
            comments = "User Requested Payment";

        if (partnerId == null || partnerId.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"partner_id is required\"}");
            return;
        }

        if (requestedAmount < MIN_WITHDRAWAL) {
            sendResponse(exchange, 200,
                    "{\"status\":\"error\",\"message\":\"Minimum withdrawal ₹" + MIN_WITHDRAWAL + "\"}");
            return;
        }

        Connection finConn = null;
        Connection bookConn = null;
        boolean oldAutoCommit = true;

        try {

            /** 1️⃣ COMPUTE COMPLETED BOOKINGS REVENUE **/
            bookConn = dbConfig.getCustomerDataSource().getConnection();
            double totalRevenue = computeTotalRevenueFromBookings(bookConn, partnerId);
            totalRevenue = round2(totalRevenue);

            /** 2️⃣ FETCH FINANCE ROW WITH LOCK **/
            finConn = dbConfig.getPartnerDataSource().getConnection();
            oldAutoCommit = finConn.getAutoCommit();
            finConn.setAutoCommit(false);

            double commissionPercent = 0;
            String selectSQL =
                    "SELECT Commission_Percentage, Paid_Payout, Pending_Payout FROM Partner_Finance WHERE Partner_ID=? FOR UPDATE";

            try (PreparedStatement ps = finConn.prepareStatement(selectSQL)) {
                ps.setString(1, partnerId);
                ResultSet rs = ps.executeQuery();

                if (!rs.next()) {
                    finConn.rollback();
                    sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Partner not found\"}");
                    return;
                }

                commissionPercent = rs.getDouble("Commission_Percentage");
                if (commissionPercent <= 0)
                    commissionPercent = FALLBACK_COMMISSION_PERCENT;
            }

            /** Commission & Net revenue **/
            double commissionAmount = round2(totalRevenue * commissionPercent / 100.0);
            double netRevenue = round2(totalRevenue - commissionAmount);

            requestedAmount = round2(requestedAmount);

            /** Compute balance (this is the Balance_Amount that will be stored in Partner_Transactions) **/
            double balanceAmount = round2(netRevenue - requestedAmount);
            if (balanceAmount < 0) balanceAmount = 0.0;

            /** Validate requested amount does not exceed available pending (netRevenue) **/
            if (requestedAmount > netRevenue) {
                finConn.rollback();
                sendResponse(exchange, 200,
                        "{\"status\":\"error\",\"message\":\"Requested amount exceeds available payout (" + netRevenue + ")\"}");
                return;
            }

            /** 3️⃣ UPDATE FINANCE TABLE — mapping explicitly as you requested:
                 Pending_Payout = Balance_Amount (from transaction)
                 Paid_Payout    = Withdrawal_Amount (requestedAmount for THIS transaction)
                 Also update Total_Revenue, Net_Revenue, Commission_Percentage, Last_Payout_Date **/
            String updateFinanceSQL = """
                    UPDATE Partner_Finance
                    SET Total_Revenue = ?,
                        Commission_Percentage = ?,
                        Net_Revenue = ?,
                        Pending_Payout = ?,
                        Paid_Payout = ?,
                        Last_Payout_Date = ?
                    WHERE Partner_ID = ?
                    """;

            Date txDate = new java.sql.Date(System.currentTimeMillis());

            try (PreparedStatement upd = finConn.prepareStatement(updateFinanceSQL)) {
                upd.setDouble(1, totalRevenue);                     // Total_Revenue
                upd.setDouble(2, commissionPercent);                // Commission_Percentage
                upd.setDouble(3, netRevenue);                       // Net_Revenue
                upd.setDouble(4, balanceAmount);                    // Pending_Payout <- Balance_Amount
                upd.setDouble(5, requestedAmount);                  // Paid_Payout    <- Withdrawal_Amount (this tx)
                upd.setDate(6, txDate);                             // Last_Payout_Date <- Transaction_Date
                upd.setString(7, partnerId);
                upd.executeUpdate();
            }

            /** 4️⃣ INSERT TRANSACTION **/
            String txId = "TX_" + System.currentTimeMillis();

            String insert = """
                    INSERT INTO Partner_Transactions
                    (Partner_ID, Transaction_ID, Transaction_Date, Total_Amount, Withdrawal_Amount, Balance_Amount,
                     Status, Transaction_Type, Comments)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

            try (PreparedStatement ins = finConn.prepareStatement(insert)) {
                ins.setString(1, partnerId);
                ins.setString(2, txId);
                ins.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                ins.setDouble(4, netRevenue);         // Total_Amount -> Net revenue
                ins.setDouble(5, requestedAmount);    // Withdrawal_Amount
                ins.setDouble(6, balanceAmount);      // Balance_Amount
                ins.setString(7, "Requested");
                ins.setString(8, "PAYOUT");
                ins.setString(9, comments);
                ins.executeUpdate();
            }

            /** 5️⃣ HANDLE IF STATUS LATER BECOMES FAILED
             * This rollback logic will:
             *  - add the transaction's Withdrawal_Amount back to Partner_Finance.Pending_Payout
             *  - subtract Withdrawal_Amount from Partner_Finance.Paid_Payout
             *  - set Partner_Transactions.Withdrawal_Amount = 0
             *
             * We update rows for this partner where the transaction status is 'Failed'.
             */
            String failureSQL = """
                    UPDATE Partner_Finance F
                    JOIN Partner_Transactions T ON F.Partner_ID = T.Partner_ID
                    SET
                        F.Pending_Payout = F.Pending_Payout + T.Withdrawal_Amount,
                        F.Paid_Payout = F.Paid_Payout - T.Withdrawal_Amount,
                        T.Withdrawal_Amount = 0
                    WHERE T.Status = 'Failed' AND T.Partner_ID = ?
                    """;

            try (PreparedStatement ps = finConn.prepareStatement(failureSQL)) {
                ps.setString(1, partnerId);
                ps.executeUpdate();
            }

            finConn.commit();

            sendResponse(exchange, 200,
                    "{\"status\":\"success\",\"message\":\"Payout requested\",\"transaction_id\":\"" + txId + "\"}");

        } catch (Exception e) {
            e.printStackTrace();
            try { if (finConn != null) finConn.rollback(); } catch (Exception ignored) {}
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        } finally {
            try { if (finConn != null) { finConn.setAutoCommit(oldAutoCommit); finConn.close(); }} catch (Exception ignored) {}
            try { if (bookConn != null) bookConn.close(); } catch (Exception ignored) {}
        }
    }

    /** Helpers **/

    private double computeTotalRevenueFromBookings(Connection conn, String partnerId) throws Exception {
        String sql = """
            SELECT SUM(Original_Amount) AS total
            FROM bookings_info
            WHERE Partner_ID=? AND Booking_Status='COMPLETED'""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, partnerId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getDouble("total") : 0.0;
        }
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private String readBody(HttpExchange exchange) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line; while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null) return map;
        for (String p : body.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
