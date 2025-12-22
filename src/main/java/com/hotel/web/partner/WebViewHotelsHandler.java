package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class WebViewHotelsHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public WebViewHotelsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS headers
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "status=error&message=Method not allowed");
            return;
        }

        String body = readRequestBody(exchange);
        Map<String, String> params = parseForm(body);

        try {
            if (params.containsKey("hotel_ids")) {
                // Delete multiple hotels
                String idsStr = params.get("hotel_ids");
                List<String> hotelIds = Arrays.asList(idsStr.split(","));
                deleteHotelsFromDB(hotelIds);
                sendResponse(exchange, 200, "status=success&message=Hotels deleted successfully");
            } else if (params.containsKey("partner_id")) {
                // Fetch hotels
                String partnerId = params.get("partner_id");
                List<String> hotelRows = fetchHotelsFromDB(partnerId);
                String response = "status=success&data=" + String.join("\n", hotelRows);
                sendResponse(exchange, 200, response);
            } else {
                sendResponse(exchange, 400, "status=error&message=Missing parameters");
            }
        } catch (SQLException e) {
            sendResponse(exchange, 500, "status=error&message=" + escapeCell(e.getMessage()));
        }
    }

    private List<String> fetchHotelsFromDB(String partnerId) throws SQLException {
        List<String> hotelRows = new ArrayList<>();
        String sql = "SELECT Hotel_ID, Partner_ID, Hotel_Name, Hotel_Type, Address, City, State, Country, Pincode," +
                     "Total_Rooms, Room_Price, Amenities, Description, Rating, Hotel_Contact, Status " +
                     "FROM Hotels_info WHERE Partner_ID = ?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, partnerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int i = 1; i <= 16; i++) { // 16 columns
                        String val = rs.getString(i);
                        cells.add(escapeCell(val));
                    }
                    hotelRows.add(String.join("|", cells));
                }
            }
        }
        return hotelRows;
    }

    private void deleteHotelsFromDB(List<String> hotelIds) throws SQLException {
        if (hotelIds.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(hotelIds.size(), "?"));
        String sql = "DELETE FROM Hotels_info WHERE Hotel_ID IN (" + placeholders + ")";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < hotelIds.size(); i++) {
                stmt.setString(i + 1, hotelIds.get(i));
            }
            stmt.executeUpdate();
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
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
                String key = java.net.URLDecoder.decode(parts[0], "UTF-8").toLowerCase();
                String val = java.net.URLDecoder.decode(parts[1], "UTF-8");
                map.put(key, val);
            }
        }
        return map;
    }

    private String escapeCell(String s) {
        if (s == null) return "";
        return s.replace("&", "and").replace("=", ":").replace("|", "/").replace("\r", " ").replace("\n", " ");
    }

    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
