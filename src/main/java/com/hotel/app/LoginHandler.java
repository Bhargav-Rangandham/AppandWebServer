package com.hotel.app;

import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class LoginHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public LoginHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        try {
            JSONObject json = new JSONObject(readRequestBody(exchange));

            if ("/login".equals(path)) {
                handleLogin(exchange, json);
                return;
            }

            if ("/app/forgot-password/verify".equals(path)) {
                handleForgotVerify(exchange, json);
                return;
            }

            if ("/app/forgot-password/change".equals(path)) {
                handleChangePassword(exchange, json);
                return;
            }

            exchange.sendResponseHeaders(404, -1);

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500,
                    new JSONObject()
                            .put("error", "server_error")
                            .put("message", "Internal Server Error")
                            .toString());
        }
    }

    /* ================= LOGIN (UNCHANGED) ================= */

    private void handleLogin(HttpExchange exchange, JSONObject json) throws Exception {

        String email = json.getString("email").trim().toLowerCase();
        String rawPassword = json.getString("password");

        if (!email.endsWith("@gmail.com")) {
            sendJsonResponse(exchange, 400,
                    new JSONObject().put("error", "invalid_email").toString());
            return;
        }

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {

            String sql = "SELECT * FROM User_Info WHERE User_Email = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, email);

                try (ResultSet rs = ps.executeQuery()) {

                    if (!rs.next()) {
                        sendJsonResponse(exchange, 404,
                                new JSONObject().put("error", "user_not_exists").toString());
                        return;
                    }

                    if ("Inactive".equalsIgnoreCase(rs.getString("Status"))) {
                        sendJsonResponse(exchange, 403,
                                new JSONObject().put("error", "inactive").toString());
                        return;
                    }

                    if (!PasswordUtil.verifyPassword(rawPassword, rs.getString("Password"))) {
                        sendJsonResponse(exchange, 401,
                                new JSONObject().put("error", "wrong_password").toString());
                        return;
                    }

                    JSONObject user = new JSONObject();
                    user.put("userId", rs.getString("User_ID"));
                    user.put("firstName", rs.getString("FirstName"));
                    user.put("lastName", rs.getString("LastName"));
                    user.put("email", rs.getString("User_Email"));
                    user.put("mobile", rs.getString("Mobile_Number"));
                    user.put("address", rs.getString("Address"));

                    sendJsonResponse(exchange, 200, user.toString());
                }
            }
        }
    }

    /* ================= FORGOT PASSWORD VERIFY (FIXED) ================= */

    private void handleForgotVerify(HttpExchange exchange, JSONObject json) throws Exception {

        String email = json.getString("email").trim().toLowerCase();
        String inputMobile = normalizeMobile(json.getString("mobile"));

        boolean matched = false;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {

            String sql = """
                SELECT Mobile_Number FROM User_Info
                WHERE User_Email = ?
                  AND Status = 'Active'
            """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String dbMobile = normalizeMobile(rs.getString("Mobile_Number"));
                        matched = inputMobile.equals(dbMobile);
                    }
                }
            }
        }

        sendJsonResponse(exchange, 200,
                new JSONObject().put("matched", matched).toString());
    }

    /* ================= CHANGE PASSWORD (UNCHANGED) ================= */

    private void handleChangePassword(HttpExchange exchange, JSONObject json) throws Exception {

        String email = json.getString("email").trim().toLowerCase();
        String newPassword = json.getString("newPassword");

        String hashedPassword = PasswordUtil.hashPassword(newPassword);

        int updated;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection()) {

            String sql = """
                UPDATE User_Info
                SET Password = ?
                WHERE User_Email = ?
                  AND Status = 'Active'
            """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, hashedPassword);
                ps.setString(2, email);
                updated = ps.executeUpdate();
            }
        }

        sendJsonResponse(exchange, 200,
                new JSONObject().put("success", updated > 0).toString());
    }

    /* ================= UTIL METHODS ================= */

    private String normalizeMobile(String mobile) {
        if (mobile == null) return "";

        // Remove +, -, spaces
        mobile = mobile.replaceAll("[^0-9]", "");

        // Remove country code if present (91)
        if (mobile.length() > 10) {
            mobile = mobile.substring(mobile.length() - 10);
        }

        return mobile;
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void sendJsonResponse(HttpExchange exchange, int status, String response)
            throws IOException {

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
