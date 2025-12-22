package com.hotel.web.finance;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class GetPartnerTransactionsHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public GetPartnerTransactionsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String partnerId;

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            partnerId = parseForm(readBody(exchange)).get("partner_id");
        } else {
            partnerId = queryToMap(exchange.getRequestURI().getQuery()).get("partner_id");
        }

        if (partnerId == null || partnerId.isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"partner_id is required\"}");
            return;
        }

        List<Map<String, Object>> txList = new ArrayList<>();

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {

            String sql = "SELECT * FROM Partner_Transactions WHERE Partner_ID=? ORDER BY Transaction_Date DESC";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, partnerId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {

                String status = rs.getString("Status");
                double withdrawal = rs.getDouble("Withdrawal_Amount");

                if ("Failed".equalsIgnoreCase(status)) {
                    adjustFinanceForFailed(conn, partnerId, withdrawal);
                }

                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("Transaction_ID", rs.getString("Transaction_ID"));
                obj.put("Transaction_Date", rs.getString("Transaction_Date"));
                obj.put("Total_Amount", rs.getDouble("Total_Amount"));
                obj.put("Withdrawal_Amount", withdrawal);
                obj.put("Balance_Amount", rs.getDouble("Balance_Amount"));
                obj.put("Status", status);
                obj.put("Transaction_Type", rs.getString("Transaction_Type")); // <-- NEW
                obj.put("Comments", rs.getString("Comments"));


                txList.add(obj);
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500,
                    "{\"status\":\"error\",\"message\":\"" +
                            escape(e.getMessage()) + "\"}");
            return;
        }

        String response = "{\"status\":\"success\",\"transactions\":" + listToJson(txList) + "}";
        sendResponse(exchange, 200, response);
    }

    // ======================================================================
    // AUTO REVERSE FINANCE WHEN FAILED
    // ======================================================================
    private void adjustFinanceForFailed(Connection conn, String partnerId, double amount) {
        try {
            String checkSql =
                    "UPDATE Partner_Finance " +
                            "SET Pending_Payout = Pending_Payout + ?, " +
                            "Paid_Payout = Paid_Payout - ? " +
                            "WHERE Partner_ID = ? AND Paid_Payout >= ?";
            PreparedStatement ps = conn.prepareStatement(checkSql);
            ps.setDouble(1, amount);
            ps.setDouble(2, amount);
            ps.setString(3, partnerId);
            ps.setDouble(4, amount);
            ps.executeUpdate();
        } catch (Exception ignored) {
            ignored.printStackTrace();
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
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
                map.put(URLDecoder.decode(kv[0], "UTF-8"),
                        URLDecoder.decode(kv[1], "UTF-8"));
            }
        }
        return map;
    }

    private Map<String, String> queryToMap(String query) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], "UTF-8"),
                        URLDecoder.decode(kv[1], "UTF-8"));
            }
        }
        return map;
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private String listToJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (Map<String, Object> obj : list) {
            sb.append("{");
            for (String k : obj.keySet()) {
                sb.append("\"").append(k).append("\":\"")
                        .append(String.valueOf(obj.get(k)).replace("\"", "\\\""))
                        .append("\",");
            }
            sb.setLength(sb.length() - 1);
            sb.append("},");
        }
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        sb.append("]");
        return sb.toString();
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
