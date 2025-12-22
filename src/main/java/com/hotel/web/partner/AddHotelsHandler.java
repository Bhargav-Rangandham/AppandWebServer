package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.json.*;

public class AddHotelsHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public AddHotelsHandler(DbConfig dbConfig) {
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

        String hotelId = params.get("hotel_id");
        boolean isUpdate = hotelId != null && !hotelId.trim().isEmpty() && hotelExists(hotelId);

        if (!isUpdate) {
            hotelId = "HOTEL_" + System.currentTimeMillis();
        }

        try {
            // Handle images (Web Base64 JSON)
            if (params.containsKey("images")) {
                String imagesJson = params.get("images");
                JSONObject json = new JSONObject(imagesJson);
                List<String> savedUrls = new ArrayList<>();

                for (String category : json.keySet()) {
                    String safeCategory = category.replaceAll("[/\\\\]", "_"); // sanitize
                    File dir = new File(dbConfig.getHotelImagesPath() + "\\" + hotelId + "\\" + safeCategory);
                    if (!dir.exists()) dir.mkdirs(); // create intermediate directories

                    JSONArray arr = json.getJSONArray(category);
                    for (int i = 0; i < arr.length(); i++) {
                        String base64 = arr.getString(i);
                        byte[] data = Base64.getDecoder().decode(base64);

                        String fileName = safeCategory + "_" + i + ".jpg"; // safe filename
                        File f = new File(dir, fileName);
                        Files.write(f.toPath(), data);

                        savedUrls.add("http://localhost:8080/hotel_images/" + hotelId + "/" + safeCategory + "/" + fileName);
                    }
                }
                params.put("hotel_images", String.join(",", savedUrls));
            }

            boolean success = isUpdate ? updateHotelInDB(hotelId, params) : addHotelToDB(hotelId, params);

            if (success) {
                String msg = isUpdate ? "Hotel updated successfully!" : "Hotel added successfully!";
                sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"" + msg + "\"}");
            } else {
                sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Failed to save hotel.\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private boolean hotelExists(String hotelId) {
        String sql = "SELECT COUNT(*) FROM Hotels_info WHERE Hotel_ID = ?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hotelId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean addHotelToDB(String hotelId, Map<String, String> params) throws SQLException {
        String sql = "INSERT INTO Hotels_info (" +
                "Hotel_ID, Partner_ID, Hotel_Name, Hotel_Type, Room_Type, Address, City, State, Country, Pincode," +
                "Hotel_Location, Total_Rooms, Available_Rooms, Room_Price, Amenities, Description, Policies, Rating, " +
                "Hotel_Contact, About_This_Property, Hotel_Images, Customization, Status)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setHotelParamsForInsert(stmt, hotelId, params);
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean updateHotelInDB(String hotelId, Map<String, String> params) throws SQLException {
        String sql = "UPDATE Hotels_info SET " +
                "Hotel_Name=?, Hotel_Type=?, Room_Type=?, Address=?, City=?, State=?, Country=?, Pincode=?," +
                "Hotel_Location=?, Total_Rooms=?, Available_Rooms=?, Room_Price=?, Amenities=?, Description=?, Policies=?," +
                "Rating=?, Hotel_Contact=?, About_This_Property=?, Hotel_Images=?, Customization=?, Status=? " +
                "WHERE Hotel_ID=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setHotelParamsForUpdate(stmt, params);
            stmt.setString(22, hotelId); // corrected index
            return stmt.executeUpdate() > 0;
        }
    }

    private void setHotelParamsForInsert(PreparedStatement stmt, String hotelId, Map<String, String> params) throws SQLException {
        stmt.setString(1, hotelId);
        stmt.setString(2, params.getOrDefault("partner_id", ""));
        stmt.setString(3, params.getOrDefault("hotel_name", ""));
        stmt.setString(4, params.getOrDefault("hotel_type", ""));
        stmt.setString(5, params.getOrDefault("room_type", "Standard"));
        stmt.setString(6, params.getOrDefault("address", ""));
        stmt.setString(7, params.getOrDefault("city", ""));
        stmt.setString(8, params.getOrDefault("state", ""));
        stmt.setString(9, params.getOrDefault("country", ""));
        stmt.setString(10, params.getOrDefault("pincode", ""));
        stmt.setString(11, params.getOrDefault("hotel_location", ""));
        stmt.setInt(12, Integer.parseInt(params.getOrDefault("total_rooms", "0")));
        stmt.setInt(13, Integer.parseInt(params.getOrDefault("available_rooms", params.getOrDefault("total_rooms", "0"))));
        stmt.setString(14, params.getOrDefault("room_price", ""));
        stmt.setString(15, params.getOrDefault("amenities", ""));
        stmt.setString(16, params.getOrDefault("description", ""));
        stmt.setString(17, params.getOrDefault("policies", ""));
        stmt.setDouble(18, Double.parseDouble(params.getOrDefault("rating", "0.0")));
        stmt.setString(19, params.getOrDefault("hotel_contact", ""));
        stmt.setString(20, params.getOrDefault("about_this_property", ""));
        stmt.setString(21, params.getOrDefault("hotel_images", null));
        stmt.setString(22, params.getOrDefault("customization", "No"));
        stmt.setString(23, params.getOrDefault("status", "Active"));
    }

    private void setHotelParamsForUpdate(PreparedStatement stmt, Map<String, String> params) throws SQLException {
        stmt.setString(1, params.getOrDefault("hotel_name", ""));
        stmt.setString(2, params.getOrDefault("hotel_type", ""));
        stmt.setString(3, params.getOrDefault("room_type", "Standard"));
        stmt.setString(4, params.getOrDefault("address", ""));
        stmt.setString(5, params.getOrDefault("city", ""));
        stmt.setString(6, params.getOrDefault("state", ""));
        stmt.setString(7, params.getOrDefault("country", ""));
        stmt.setString(8, params.getOrDefault("pincode", ""));
        stmt.setString(9, params.getOrDefault("hotel_location", ""));
        stmt.setInt(10, Integer.parseInt(params.getOrDefault("total_rooms", "0")));
        stmt.setInt(11, Integer.parseInt(params.getOrDefault("available_rooms", params.getOrDefault("total_rooms", "0"))));
        stmt.setString(12, params.getOrDefault("room_price", ""));
        stmt.setString(13, params.getOrDefault("amenities", ""));
        stmt.setString(14, params.getOrDefault("description", ""));
        stmt.setString(15, params.getOrDefault("policies", ""));
        stmt.setDouble(16, Double.parseDouble(params.getOrDefault("rating", "0.0")));
        stmt.setString(17, params.getOrDefault("hotel_contact", ""));
        stmt.setString(18, params.getOrDefault("about_this_property", ""));
        stmt.setString(19, params.getOrDefault("hotel_images", null));
        stmt.setString(20, params.getOrDefault("customization", "No"));
        stmt.setString(21, params.getOrDefault("status", "Active"));
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
                String key = URLDecoder.decode(parts[0], "UTF-8"); // preserve case
                String val = URLDecoder.decode(parts[1], "UTF-8");
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
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
