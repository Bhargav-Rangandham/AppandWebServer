package com.hotel.web.finance;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class UpdateBankDetailsHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public UpdateBankDetailsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private static final Set<String> VALID_PAYOUT_TYPES =
            Set.of("Daily", "Weekly", "Fornight", "Monthly", "Quarterly");

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
            sendResponse(exchange, 405, "{\"status\":\"error\",\"message\":\"Only POST allowed\"}");
            return;
        }

        String body = readBody(exchange);
        Map<String, String> params = parseForm(body);

        String partnerId = params.get("partner_id");
        if (partnerId == null || partnerId.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"partner_id is required\"}");
            return;
        }

        String accountHolderName = params.getOrDefault("Account_Holder_Name", "");
        String bankName = params.getOrDefault("Bank_Name", "");
        String accountNumber = params.getOrDefault("Account_Number", "");
        String ifscSwift = params.getOrDefault("IFSC_SWIFT", "");
        String accountType = params.getOrDefault("Account_Type", "");
        String panTaxId = params.getOrDefault("PAN_Tax_ID", "");
        String payoutType = params.getOrDefault("Payout_Type", "");

        if (!VALID_PAYOUT_TYPES.contains(payoutType)) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid payout type\"}");
            return;
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {

            // 🔹 Check unique Account Number
            if (exists(conn, "SELECT Partner_ID FROM Partner_Finance WHERE Account_Number = ? AND Partner_ID <> ?",
                    accountNumber, partnerId)) {
                sendResponse(exchange, 409,
                        "{\"status\":\"error\",\"message\":\"Account Number already registered by another partner\"}");
                return;
            }

            // 🔹 Check unique PAN number
            if (exists(conn, "SELECT Partner_ID FROM Partner_Finance WHERE PAN_Tax_ID = ? AND Partner_ID <> ?",
                    panTaxId, partnerId)) {
                sendResponse(exchange, 409,
                        "{\"status\":\"error\",\"message\":\"PAN / Tax ID already registered by another partner\"}");
                return;
            }

            boolean alreadyExists = exists(conn,
                    "SELECT Partner_ID FROM Partner_Finance WHERE Partner_ID = ?", partnerId);

            if (alreadyExists) {
                String sql = """
                        UPDATE Partner_Finance SET
                        Account_Holder_Name=?, Bank_Name=?, Account_Number=?, IFSC_SWIFT=?,
                        Account_Type=?, PAN_Tax_ID=?, Payout_Type=?
                        WHERE Partner_ID=?
                        """;

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, accountHolderName);
                    ps.setString(2, bankName);
                    ps.setString(3, accountNumber);
                    ps.setString(4, ifscSwift);
                    ps.setString(5, accountType);
                    ps.setString(6, panTaxId);
                    ps.setString(7, payoutType);
                    ps.setString(8, partnerId);
                    ps.executeUpdate();
                }

            } else {

                String sql = """
                        INSERT INTO Partner_Finance
                        (Partner_ID, Account_Holder_Name, Bank_Name, Account_Number, IFSC_SWIFT,
                         Account_Type, PAN_Tax_ID, Payout_Type)
                        VALUES (?,?,?,?,?,?,?,?)
                        """;

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, partnerId);
                    ps.setString(2, accountHolderName);
                    ps.setString(3, bankName);
                    ps.setString(4, accountNumber);
                    ps.setString(5, ifscSwift);
                    ps.setString(6, accountType);
                    ps.setString(7, panTaxId);
                    ps.setString(8, payoutType);
                    ps.executeUpdate();
                }
            }

            sendResponse(exchange, 200,
                    "{\"status\":\"success\",\"message\":\"Bank / finance details saved successfully\"}");

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500,
                    "{\"status\":\"error\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private boolean exists(Connection conn, String sql, String value) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean exists(Connection conn, String sql, String v1, String v2) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, v1);
            ps.setString(2, v2);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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
            if (kv.length == 2)
                map.put(java.net.URLDecoder.decode(kv[0], "UTF-8"),
                        java.net.URLDecoder.decode(kv[1], "UTF-8"));
        }
        return map;
    }

    private String escape(String s) {
        return s.replace("\"", "\\\"");
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
