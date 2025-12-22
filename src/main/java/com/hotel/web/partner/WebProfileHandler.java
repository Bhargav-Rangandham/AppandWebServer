package com.hotel.web.partner;

import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class WebProfileHandler implements HttpHandler {

    private static final Set<String> readOnlyFields = Set.of("Email", "Status", "Registration_Date");

    private final DbConfig dbConfig;

    public WebProfileHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }
    
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

        String body = readRequestBody(exchange);
        Map<String, String> params = parseForm(body);
        String path = exchange.getRequestURI().getPath();

        try {
            switch (path) {
                case "/webgetprofile":
                    handleGetProfile(exchange, params);
                    break;
                case "/webupdateprofile":
                    handleUpdateProfile(exchange, params);
                    break;
                case "/webchangepassword":
                    handleChangePassword(exchange, params);
                    break;
                case "/webdeleteprofile":
                    handleDeleteProfile(exchange, params);
                    break;
                default:
                    sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Invalid endpoint\"}");
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    //================== GET PROFILE ==================
    private void handleGetProfile(HttpExchange exchange, Map<String, String> params) throws IOException, SQLException {
        String loggedInEmail = params.getOrDefault("loggedInEmail", "").trim().toLowerCase();
        if (loggedInEmail.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Logged-in email is required\"}");
            return;
        }

        String query = "SELECT * FROM partner_data WHERE LOWER(Email)=?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, loggedInEmail);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, String> partnerData = new LinkedHashMap<>();
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String key = meta.getColumnName(i);
                        String val = rs.getString(i) != null ? rs.getString(i) : "";
                        partnerData.put(key, val);
                    }
                    StringBuilder json = new StringBuilder("{\"status\":\"success\",\"data\":{");
                    for (Map.Entry<String, String> entry : partnerData.entrySet()) {
                        json.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                                .append(escapeJson(entry.getValue())).append("\",");
                    }
                    if (json.charAt(json.length() - 1) == ',') json.setLength(json.length() - 1);
                    json.append("}}");
                    sendResponse(exchange, 200, json.toString());
                    return;
                }
            }
        }
        sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Partner not found\"}");
    }

    //================== UPDATE PROFILE ==================
    private void handleUpdateProfile(HttpExchange exchange, Map<String, String> params) throws IOException, SQLException {
        String loggedInEmail = params.getOrDefault("loggedInEmail", "").trim().toLowerCase();
        if (loggedInEmail.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Logged-in email is required\"}");
            return;
        }

        List<String> columns = new ArrayList<>();
        String colQuery = "SELECT * FROM partner_data LIMIT 1";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement colStmt = conn.prepareStatement(colQuery);
             ResultSet rs = colStmt.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnName(i));
        }

        StringBuilder setClause = new StringBuilder();
        List<String> values = new ArrayList<>();
        for (String col : columns) {
            if (params.containsKey(col) && !readOnlyFields.contains(col)) {
                setClause.append(col).append("=?,");
                values.add(params.get(col).trim());
            }
        }

        if (setClause.length() == 0) {
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"No editable fields provided\"}");
            return;
        }
        setClause.setLength(setClause.length() - 1);

        String updateQuery = "UPDATE partner_data SET " + setClause + " WHERE LOWER(Email)=?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement updateStmt = conn.prepareStatement(updateQuery)) {
            int index = 1;
            for (String val : values) updateStmt.setString(index++, val);
            updateStmt.setString(index, loggedInEmail);
            int updated = updateStmt.executeUpdate();
            if (updated == 0) sendResponse(exchange, 404, "{\"status\":\"error\",\"message\":\"Partner not found\"}");
            else sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"Profile updated successfully\"}");
        }
    }

    //================== CHANGE PASSWORD ==================
    private void handleChangePassword(HttpExchange exchange, Map<String, String> params)
            throws IOException, SQLException {

        String loggedInEmail = params.getOrDefault("loggedInEmail", "").trim().toLowerCase();
        String currentPassword = params.getOrDefault("currentPassword", "");
        String newPassword = params.getOrDefault("newPassword", "");

        if (loggedInEmail.isEmpty() || currentPassword.isEmpty() || newPassword.isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"Email, current password and new password are required\"}");
            return;
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {

            // 1️⃣ Fetch stored bcrypt hash
            String storedHash;
            String checkQuery = "SELECT Password FROM partner_data WHERE LOWER(Email)=?";

            try (PreparedStatement stmt = conn.prepareStatement(checkQuery)) {
                stmt.setString(1, loggedInEmail);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        sendResponse(exchange, 404,
                                "{\"status\":\"error\",\"message\":\"User not found\"}");
                        return;
                    }
                    storedHash = rs.getString("Password");
                }
            }

            // 2️⃣ Verify CURRENT password using bcrypt
            if (!PasswordUtil.verifyPassword(currentPassword, storedHash)) {
                sendResponse(exchange, 401,
                        "{\"status\":\"error\",\"message\":\"Current password does not match\"}");
                return;
            }

            // 3️⃣ Hash NEW password
            String newHashedPassword = PasswordUtil.hashPassword(newPassword);

            // 4️⃣ Update DB with hashed password
            String updateQuery = "UPDATE partner_data SET Password=? WHERE LOWER(Email)=?";
            try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
                stmt.setString(1, newHashedPassword);
                stmt.setString(2, loggedInEmail);
                stmt.executeUpdate();
            }
        }

        sendResponse(exchange, 200,
                "{\"status\":\"success\",\"message\":\"Password updated successfully\"}");
    }


    //================== DELETE PROFILE ==================
    private void handleDeleteProfile(HttpExchange exchange, Map<String, String> params)
            throws IOException, SQLException {

        String loggedInEmail = params.getOrDefault("loggedInEmail", "").trim().toLowerCase();

        if (loggedInEmail.isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"Logged-in email is required\"}");
            return;
        }

        String updateQuery =
                "UPDATE partner_data SET Status='Inactive' WHERE LOWER(Email)=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateQuery)) {

            stmt.setString(1, loggedInEmail);
            int updated = stmt.executeUpdate();

            if (updated == 0) {
                sendResponse(exchange, 404,
                        "{\"status\":\"error\",\"message\":\"Partner not found\"}");
                return;
            }
        }

        // ✅ Tell frontend to logout immediately
        sendResponse(exchange, 200,
                "{"
              + "\"status\":\"success\","
              + "\"message\":\"Account deactivated successfully\","
              + "\"logout\":true"
              + "}");
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                String key = java.net.URLDecoder.decode(parts[0], "UTF-8");
                String val = java.net.URLDecoder.decode(parts[1], "UTF-8");
                map.put(key, val);
            }
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
