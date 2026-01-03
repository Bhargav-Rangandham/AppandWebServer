package com.hotel.app;

import com.hotel.security.PasswordUtil;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;

public class RegisterHandler implements HttpHandler {

    private final DbConfig dbConfig;

    // ✅ Inject DbConfig via constructor
    public RegisterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            // ===== Read JSON body =====
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject json = new JSONObject(sb.toString());

            String email = json.getString("email");
            String firstName = json.getString("firstName");
            String lastName = json.getString("lastName");
            String gender = json.getString("gender");
            String mobile = json.getString("mobile");
            String address = json.getString("address");
            String rawPassword = json.getString("password"); // 👈 raw from Flutter
            String consent = json.getString("consent");

            // ✅ Hash password in backend
            String hashedPassword = PasswordUtil.hashPassword(rawPassword);

            // ===== Get pooled DB connection =====
            try (Connection conn =
                         dbConfig.getCustomerDataSource().getConnection()) {

                // ===== Check if email exists =====
                String checkSql = "SELECT 1 FROM User_Info WHERE User_Email = ?";
                try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setString(1, email);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            sendResponse(exchange, 400, "Email already exists");
                            return;
                        }
                    }
                }

                // ===== Generate new User_ID =====
                String newId = "CR9087601";
                String idSql = "SELECT User_ID FROM User_Info ORDER BY User_ID DESC LIMIT 1";
                try (Statement stmt = conn.createStatement();
                     ResultSet idRs = stmt.executeQuery(idSql)) {

                    if (idRs.next()) {
                        String lastId = idRs.getString("User_ID");
                        int numericPart = Integer.parseInt(lastId.substring(2)) + 1;
                        newId = "CR" + numericPart;
                    }
                }

                // ===== Insert new user =====
                String insertSql = """
                        INSERT INTO User_Info
                        (User_ID, User_Email, Password, FirstName, LastName,
                         Gender, Mobile_Number, Address, Consent)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, newId);
                    insertStmt.setString(2, email);
                    insertStmt.setString(3, hashedPassword); // ✅ bcrypt hash
                    insertStmt.setString(4, firstName.toUpperCase());
                    insertStmt.setString(5, lastName.toUpperCase());
                    insertStmt.setString(6, gender);
                    insertStmt.setString(7, mobile);
                    insertStmt.setString(8, address);
                    insertStmt.setString(9, consent);
                    insertStmt.executeUpdate();
                }
                
                String walletSql = """
                        INSERT INTO wallets
                        (wallet_id, user_id, balance, status)
                        VALUES (?, ?, ?, ?)
                        """;

                try (PreparedStatement walletStmt = conn.prepareStatement(walletSql)) {
                    walletStmt.setString(1, UUID.randomUUID().toString());
                    walletStmt.setString(2, newId);
                    walletStmt.setBigDecimal(3, new java.math.BigDecimal("200.00"));
                    walletStmt.setString(4, "active");
                    walletStmt.executeUpdate();
                }
            }
            
            sendResponse(exchange, 200, "Registration Successful");

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "Registration Failed");
        }
    }

    private void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
