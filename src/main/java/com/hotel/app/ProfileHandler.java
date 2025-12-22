package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.sql.*;
import java.util.*;

public class ProfileHandler implements HttpHandler {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final DbConfig dbConfig;

    // ✅ Inject DbConfig via constructor
    public ProfileHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                handleGetProfile(exchange);

            } else if ("POST".equalsIgnoreCase(method)) {
                InputStream input = exchange.getRequestBody();
                Map<String, Object> request = mapper.readValue(input, Map.class);

                if (request.containsKey("status") && "Inactive".equalsIgnoreCase((String) request.get("status"))) {
                    handleDeactivateAccount(exchange, request);
                } else {
                    handleUpdateProfile(exchange, request);
                }

            } else {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    // ------------------ FETCH PROFILE ------------------
    private void handleGetProfile(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String email = null;

        if (query != null && query.contains("email=")) {
            email = query.split("email=")[1].trim();
            email = java.net.URLDecoder.decode(email, "UTF-8");
        }

        if (email == null || email.isEmpty()) {
            sendResponse(exchange, 400, Map.of("error", "Missing email parameter"));
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT * FROM User_Info WHERE LOWER(User_Email) = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, email.toLowerCase().trim());

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Map<String, Object> userData = new HashMap<>();
                userData.put("userId", rs.getString("User_ID"));
                userData.put("email", rs.getString("User_Email"));
                userData.put("firstName", rs.getString("FirstName"));
                userData.put("lastName", rs.getString("LastName"));
                userData.put("phone", rs.getString("Mobile_Number"));
                userData.put("address", rs.getString("Address"));
                userData.put("Room_Type", rs.getString("Room_Type"));
                userData.put("Meal_Preference", rs.getString("Meal_Preference"));
                userData.put("Add_ons", rs.getString("Add_ons"));
                userData.put("Budget_Min", rs.getString("Budget_Min"));
                userData.put("Budget_Max", rs.getString("Budget_Max"));
                userData.put("Travel_Style", rs.getString("Travel_Style"));
                userData.put("Stay_Preference", rs.getString("Stay_Preference"));
                userData.put("For_You", rs.getString("For_You"));
                userData.put("Location_Preference", rs.getString("Location_Preference"));
                userData.put("status", rs.getString("Status"));

                sendResponse(exchange, 200, userData);
            } else {
                sendResponse(exchange, 404, Map.of("error", "User not found"));
            }

        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    // ------------------ UPDATE PROFILE ------------------
    private void handleUpdateProfile(HttpExchange exchange, Map<String, Object> request) throws IOException {

        String email = ((String) request.get("email")).toLowerCase().trim();
        
        boolean updated;

        try (Connection conn = getConnection()) {
            updated = updateUserInDB(conn, request, email);
        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            return;
        }

        if (updated) {
            sendResponse(exchange, 200, Map.of("message", "Profile updated successfully"));
        } else {
            sendResponse(exchange, 404, Map.of("message", "User not found or no changes made"));
        }
    }

    private boolean updateUserInDB(Connection conn, Map<String, Object> data, String email) throws SQLException {
        String sql = "UPDATE User_Info SET FirstName=?, LastName=?, Mobile_Number=?, Address=? WHERE LOWER(User_Email)=?";

        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, getSafeString(data.get("firstName")));
        stmt.setString(2, getSafeString(data.get("lastName")));
        stmt.setString(3, getSafeString(data.get("phone")));
        stmt.setString(4, getSafeString(data.get("address")));
        stmt.setString(5, email.trim());

        int affected = stmt.executeUpdate();
        return affected > 0;
    }

    // ------------------ NEW: DEACTIVATE ACCOUNT ------------------
    private void handleDeactivateAccount(HttpExchange exchange, Map<String, Object> request) throws IOException {

        String email = ((String) request.get("email")).toLowerCase().trim();
        boolean updated;

        try (Connection conn = getConnection()) {
            String sql = "UPDATE User_Info SET Status = 'Inactive' WHERE LOWER(User_Email) = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, email);

            updated = stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            sendResponse(exchange, 500, Map.of("error", e.getMessage()));
            return;
        }

        if (updated) {
            sendResponse(exchange, 200, Map.of("message", "Account deactivated successfully"));
        } else {
            sendResponse(exchange, 404, Map.of("message", "User not found"));
        }
    }

    // DB connection
    private Connection getConnection() throws SQLException {
    	Connection conn = dbConfig.getCustomerDataSource().getConnection();
        try { Class.forName("com.mysql.cj.jdbc.Driver"); }
        catch (ClassNotFoundException e) { throw new SQLException("MySQL JDBC Driver not found."); }
        return conn;
    }

    private String getSafeString(Object val) {
        return val == null ? "" : val.toString().trim();
    }

    private void sendResponse(HttpExchange exchange, int status, Map<String, ?> response) throws IOException {
        String json = mapper.writeValueAsString(response);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, json.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(json.getBytes()); }
    }
}
