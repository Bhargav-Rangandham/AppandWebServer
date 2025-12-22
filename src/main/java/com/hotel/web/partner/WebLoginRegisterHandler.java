package com.hotel.web.partner;

import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class WebLoginRegisterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public WebLoginRegisterHandler(DbConfig dbConfig) {
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

        String body;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            body = sb.toString();
        }

        Map<String, String> params = parseForm(body);
        String path = exchange.getRequestURI().getPath();

        try {
            if (path.equals("/forgotpassword")) {
                handleForgotPassword(exchange, params);
            } else if (path.equals("/webgetprofile") && params.containsKey("email")) {
                handleGetProfile(exchange, params.get("email").trim().toLowerCase());
            } else if (params.containsKey("email") && params.containsKey("password") && params.size() == 2) {
                handleLogin(exchange, params);
            } else {
                handleRegister(exchange, params);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500,
                    "{\"status\":\"error\",\"message\":\"Internal server error\"}");
        }
    }

    // ================== LOGIN ==================
    private void handleLogin(HttpExchange exchange, Map<String, String> params)
            throws IOException, SQLException {

        String email = params.getOrDefault("email", "").trim().toLowerCase();
        String rawPassword = params.getOrDefault("password", "").trim();

        if (email.isEmpty() || rawPassword.isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"Email and password are required\"}");
            return;
        }

        String query = "SELECT * FROM partner_data WHERE LOWER(Email)=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, email);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    sendResponse(exchange, 404,
                            "{\"status\":\"error\",\"message\":\"Email is wrong or user not present\"}");
                    return;
                }

                String storedHash = rs.getString("Password");
                String status = rs.getString("Status");

                // ✅ bcrypt verification
                if (!PasswordUtil.verifyPassword(rawPassword, storedHash)) {
                    sendResponse(exchange, 401,
                            "{\"status\":\"error\",\"message\":\"Password is incorrect\"}");
                    return;
                }

                if (!"Active".equalsIgnoreCase(status)) {
                    sendResponse(exchange, 403,
                            "{\"status\":\"error\",\"message\":\"Inactive or deleted user. Please reach out to customer support\"}");
                    return;
                }

                // Successful login (unchanged response logic)
                Map<String, String> partnerDetails = new LinkedHashMap<>();
                ResultSetMetaData meta = rs.getMetaData();

                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String key = meta.getColumnName(i);
                    String val = rs.getString(i) != null ? rs.getString(i) : "";
                    partnerDetails.put(key, val);
                }

                StringBuilder sb = new StringBuilder(
                        "{\"status\":\"success\",\"message\":\"Login successful\",");
                for (Map.Entry<String, String> entry : partnerDetails.entrySet()) {
                    sb.append("\"").append(entry.getKey()).append("\":\"")
                            .append(entry.getValue().replace("\"", "\\\""))
                            .append("\",");
                }
                sb.setLength(sb.length() - 1);
                sb.append("}");

                sendResponse(exchange, 200, sb.toString());
            }
        }
    }

    // ================== GET PROFILE ==================
    private void handleGetProfile(HttpExchange exchange, String email)
            throws IOException, SQLException {

        String query = "SELECT * FROM partner_data WHERE LOWER(Email)=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, email);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, String> profile = new LinkedHashMap<>();
                    ResultSetMetaData meta = rs.getMetaData();

                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String key = meta.getColumnName(i);
                        String val = rs.getString(i) != null ? rs.getString(i) : "";
                        profile.put(key, val);
                    }

                    StringBuilder sb = new StringBuilder("{");
                    for (Map.Entry<String, String> entry : profile.entrySet()) {
                        sb.append("\"").append(entry.getKey()).append("\":\"")
                                .append(entry.getValue().replace("\"", "\\\""))
                                .append("\",");
                    }
                    sb.setLength(sb.length() - 1);
                    sb.append("}");

                    sendResponse(exchange, 200, sb.toString());
                    return;
                }
            }
        }

        sendResponse(exchange, 404,
                "{\"status\":\"error\",\"message\":\"Partner not found\"}");
    }

    // ================== REGISTER ==================
    private void handleRegister(HttpExchange exchange, Map<String, String> params)
            throws IOException, SQLException {

        String email = params.getOrDefault("email", "").trim().toLowerCase();
        String partnerName = capitalize(params.getOrDefault("partner_name", ""));
        String businessName = capitalize(params.getOrDefault("business_name", ""));
        String address = capitalize(params.getOrDefault("address", ""));
        String city = capitalize(params.getOrDefault("city", ""));
        String state = capitalize(params.getOrDefault("state", ""));
        String country = capitalize(params.getOrDefault("country", ""));
        String contactNumber = params.getOrDefault("contact_number", "").trim();
        String rawPassword = params.getOrDefault("password", "").trim();
        String pincode = params.getOrDefault("pincode", "").trim();
        String gstNumber = params.getOrDefault("gst_number", "").trim();

        List<String> missingFields = new ArrayList<>();

        if (partnerName.isEmpty()) missingFields.add("partner_name");
        if (businessName.isEmpty()) missingFields.add("business_name");
        if (email.isEmpty()) missingFields.add("email");
        if (rawPassword.isEmpty()) missingFields.add("password");
        if (contactNumber.isEmpty()) missingFields.add("contact_number");
        if (address.isEmpty()) missingFields.add("address");
        if (city.isEmpty()) missingFields.add("city");
        if (state.isEmpty()) missingFields.add("state");
        if (country.isEmpty()) missingFields.add("country");
        if (pincode.isEmpty()) missingFields.add("pincode");
        if (gstNumber.isEmpty()) missingFields.add("gst_number");

        if (!missingFields.isEmpty()) {
            sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"missing_fields\":" + missingFields + "}");
            return;
        }

        // ✅ Hash password
        String hashedPassword = PasswordUtil.hashPassword(rawPassword);

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection()) {

            String checkQuery = "SELECT Partner_ID FROM partner_data WHERE LOWER(Email)=?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkQuery)) {
                checkStmt.setString(1, email);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        sendResponse(exchange, 409,
                                "{\"status\":\"error\",\"message\":\"Email already registered\"}");
                        return;
                    }
                }
            }

            String uniqueID = "PR" + (new Random().nextInt(90000) + 10000);
            Timestamp registrationDate = new Timestamp(System.currentTimeMillis());

            String insertQuery =
                    "INSERT INTO partner_data " +
                    "(Partner_ID, Partner_Name, Business_Name, Email, Password, Contact_Number, " +
                    "Address, City, State, Country, Pincode, GST_Number, Registration_Date, Status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement insertStmt = conn.prepareStatement(insertQuery)) {
                insertStmt.setString(1, uniqueID);
                insertStmt.setString(2, partnerName);
                insertStmt.setString(3, businessName);
                insertStmt.setString(4, email);
                insertStmt.setString(5, hashedPassword); // ✅ bcrypt
                insertStmt.setString(6, contactNumber);
                insertStmt.setString(7, address);
                insertStmt.setString(8, city);
                insertStmt.setString(9, state);
                insertStmt.setString(10, country);
                insertStmt.setString(11, pincode);
                insertStmt.setString(12, gstNumber);
                insertStmt.setTimestamp(13, registrationDate);
                insertStmt.setString(14, "Active");
                insertStmt.executeUpdate();
            }
        }

        sendResponse(exchange, 200,
                "{\"status\":\"success\",\"message\":\"Registration successful\"}");
    }

    // ================== FORGOT PASSWORD ==================
    private void handleForgotPassword(HttpExchange exchange, Map<String, String> params)
            throws IOException, SQLException {

        String email = params.get("email").trim().toLowerCase();
        String rawNewPassword = params.get("newPassword").trim();

        // ✅ Hash new password
        String hashedPassword = PasswordUtil.hashPassword(rawNewPassword);

        String updateQuery = "UPDATE partner_data SET Password=? WHERE LOWER(Email)=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateQuery)) {

            stmt.setString(1, hashedPassword);
            stmt.setString(2, email);

            int updated = stmt.executeUpdate();
            if (updated == 0) {
                sendResponse(exchange, 404,
                        "{\"status\":\"error\",\"message\":\"Email not found\"}");
                return;
            }
        }

        sendResponse(exchange, 200,
                "{\"status\":\"success\",\"message\":\"Password updated successfully\"}");
    }

    // ================== UTIL ==================
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;

        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                map.put(
                        URLDecoder.decode(parts[0], "UTF-8"),
                        URLDecoder.decode(parts[1], "UTF-8")
                );
            }
        }
        return map;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String message)
            throws IOException {

        exchange.getResponseHeaders().add(
                "Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
