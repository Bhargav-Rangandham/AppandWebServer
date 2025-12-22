package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;


public class AddPgHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public AddPgHandler(DbConfig dbConfig) {
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
        Map<String, String> params;
        try {
            params = parseForm(body);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid form encoding\"}");
            return;
        }

        // If images JSON included, parse and save images first, then inject pg_images param as CSV of URLs
        try {
            if (params.containsKey("images") && params.get("images") != null && !params.get("images").trim().isEmpty()) {
                String imagesJson = params.get("images");
                try {
                    JSONObject json = new JSONObject(imagesJson);
                    List<String> savedUrls = new ArrayList<>();

                    // pg_id may be provided for update; if not, we'll generate one (but we need it to save images).
                    String pgId = params.getOrDefault("pg_id", "").trim();
                    if (pgId.isEmpty()) {
                        pgId = "PG_" + System.currentTimeMillis();
                        params.put("pg_id", pgId);
                    }

                    for (String category : json.keySet()) {
                        String safeCategory = category.replaceAll("[/\\\\]", "_"); // sanitize
                        File dir = new File(dbConfig.getHotelImagesPath() + File.separator + pgId + File.separator + safeCategory);
                        if (!dir.exists()) dir.mkdirs();

                        JSONArray arr = json.getJSONArray(category);
                        for (int i = 0; i < arr.length(); i++) {
                            String base64 = arr.getString(i);
                            if (base64 == null || base64.trim().isEmpty()) continue;
                            byte[] data = Base64.getDecoder().decode(base64);

                            String fileName = safeCategory + "_" + i + ".jpg";
                            File f = new File(dir, fileName);

                            // Write file (creates or overwrites)
                            try (OutputStream os = Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                                os.write(data);
                            }

                            // Build accessible URL (adjust host / port / path if needed)
                            String url = "http://localhost:8080/hotel_images/" + pgId + "/" + safeCategory + "/" + fileName;
                            savedUrls.add(url);
                        }
                    }

                    if (!savedUrls.isEmpty()) {
                        params.put("pg_images", String.join(",", savedUrls));
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    // don't fail the whole request for image parse error: just continue without images
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // Ensure PG_ID exists for insert (if empty, create a new one)
        String incomingPgId = params.getOrDefault("pg_id", "").trim();
        boolean isUpdate = false;
        if (!incomingPgId.isEmpty()) {
            // Check DB whether PG exists
            isUpdate = pgExists(incomingPgId);
        } else {
            // generate new PG_ID
            incomingPgId = "PG_" + System.currentTimeMillis();
            params.put("pg_id", incomingPgId);
        }

        try {
            boolean success = isUpdate ? updatePGInDB(incomingPgId, params) : addPGToDB(incomingPgId, params);
            if (success) {
                String msg = isUpdate ? "PG updated successfully!" : "PG added successfully!";
                sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"" + escapeJson(msg) + "\"}");
            } else {
                sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"Failed to save pg.\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ----------------- DB helpers -----------------

    private boolean pgExists(String pgId) {
        String sql = "SELECT COUNT(*) FROM paying_guest_info WHERE PG_ID = ?";
        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {

            st.setString(1, pgId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean addPGToDB(String pgId, Map<String, String> params) throws SQLException {
        // Insert must match your table columns and order exactly
        String sql = "INSERT INTO paying_guest_info (" +
                "PG_ID, Partner_ID, PG_Name, PG_Type, Room_Type, Address, City, State, Country, Pincode, " +
                "Total_Single_Sharing_Rooms, Total_Double_Sharing_Rooms, Total_Three_Sharing_Rooms, " +
                "Total_Four_ShARING_Rooms, Total_Five_ShARING_Rooms, Hotel_Location, Available_Rooms, Room_Price, " +
                "Amenities, Description, Policies, Rating, PG_Contact, About_This_PG, PG_Images, Status" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        // Fix column names that may have been pasted with inconsistent capitalization/spelling:
        sql = sql.replace("Total_Four_ShARING_Rooms", "Total_Four_Sharing_Rooms")
                 .replace("Total_Five_ShARING_Rooms", "Total_Five_Sharing_Rooms");

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setPGParamsForInsert(stmt, pgId, params);
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean updatePGInDB(String pgId, Map<String, String> params) throws SQLException {
        String sql = "UPDATE paying_guest_info SET " +
                "PG_Name=?, Partner_ID=?, PG_Type=?, Room_Type=?, Address=?, City=?, State=?, Country=?, Pincode=?, " +
                "Total_Single_Sharing_Rooms=?, Total_Double_Sharing_Rooms=?, Total_Three_Sharing_Rooms=?, " +
                "Total_Four_Sharing_Rooms=?, Total_Five_Sharing_Rooms=?, Hotel_Location=?, Available_Rooms=?, Room_Price=?, " +
                "Amenities=?, Description=?, Policies=?, Rating=?, PG_Contact=?, About_This_PG=?, PG_Images=?, Status=? " +
                "WHERE PG_ID=?";

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setPGParamsForUpdate(stmt, params);
            stmt.setString(26, pgId); // WHERE PG_ID=?
            return stmt.executeUpdate() > 0;
        }
    }

    // ----------------- Param bindings -----------------

    private void setPGParamsForInsert(PreparedStatement stmt, String pgId, Map<String, String> params) throws SQLException {
        // Map exactly to the INSERT column order
        stmt.setString(1, pgId);
        stmt.setString(2, params.getOrDefault("partner_id", ""));
        stmt.setString(3, params.getOrDefault("pg_name", ""));
        stmt.setString(4, params.getOrDefault("pg_type", ""));
        stmt.setString(5, params.getOrDefault("room_type", "")); // CSV like "Single Sharing,Double Sharing"
        stmt.setString(6, params.getOrDefault("address", ""));
        stmt.setString(7, params.getOrDefault("city", ""));
        stmt.setString(8, params.getOrDefault("state", ""));
        stmt.setString(9, params.getOrDefault("country", ""));
        stmt.setString(10, params.getOrDefault("pincode", ""));

        stmt.setInt(11, parseIntSafe(params.get("total_single_sharing_rooms")));
        stmt.setInt(12, parseIntSafe(params.get("total_double_sharing_rooms")));
        stmt.setInt(13, parseIntSafe(params.get("total_three_sharing_rooms"))); // fixed key
        stmt.setInt(14, parseIntSafe(params.get("total_four_sharing_rooms")));
        stmt.setInt(15, parseIntSafe(params.get("total_five_sharing_rooms")));

        // Hotel_Location stored as "lat,lng"
        stmt.setString(16, params.getOrDefault("pg_location", params.getOrDefault("hotel_location", "")));
        stmt.setInt(17, parseIntSafe(params.get("available_rooms")));

        stmt.setString(18, params.getOrDefault("room_price", ""));
        stmt.setString(19, params.getOrDefault("amenities", ""));
        stmt.setString(20, params.getOrDefault("description", ""));
        stmt.setString(21, params.getOrDefault("policies", ""));
        stmt.setDouble(22, parseDoubleSafe(params.get("rating")));
        stmt.setString(23, params.getOrDefault("pg_contact", ""));
        // Flutter uses 'about_this_property' — table column is About_This_PG
        stmt.setString(24, params.getOrDefault("about_this_property", params.getOrDefault("about_this_pg", "")));
        stmt.setString(25, params.getOrDefault("pg_images", null));
        stmt.setString(26, params.getOrDefault("status", "Active"));
    }

    private void setPGParamsForUpdate(PreparedStatement stmt, Map<String, String> params) throws SQLException {
        // This method must set params in the same order used in UPDATE ... SET ...
        stmt.setString(1, params.getOrDefault("pg_name", ""));
        stmt.setString(2, params.getOrDefault("partner_id", ""));
        stmt.setString(3, params.getOrDefault("pg_type", ""));
        stmt.setString(4, params.getOrDefault("room_type", ""));
        stmt.setString(5, params.getOrDefault("address", ""));
        stmt.setString(6, params.getOrDefault("city", ""));
        stmt.setString(7, params.getOrDefault("state", ""));
        stmt.setString(8, params.getOrDefault("country", ""));
        stmt.setString(9, params.getOrDefault("pincode", ""));

        stmt.setInt(10, parseIntSafe(params.get("total_single_sharing_rooms")));
        stmt.setInt(11, parseIntSafe(params.get("total_double_sharing_rooms")));
        stmt.setInt(12, parseIntSafe(params.get("total_three_sharing_rooms")));
        stmt.setInt(13, parseIntSafe(params.get("total_four_sharing_rooms")));
        stmt.setInt(14, parseIntSafe(params.get("total_five_sharing_rooms")));

        stmt.setString(15, params.getOrDefault("pg_location", params.getOrDefault("hotel_location", "")));
        stmt.setInt(16, parseIntSafe(params.get("available_rooms")));

        stmt.setString(17, params.getOrDefault("room_price", ""));
        stmt.setString(18, params.getOrDefault("amenities", ""));
        stmt.setString(19, params.getOrDefault("description", ""));
        stmt.setString(20, params.getOrDefault("policies", ""));
        stmt.setDouble(21, parseDoubleSafe(params.get("rating")));
        stmt.setString(22, params.getOrDefault("pg_contact", ""));
        stmt.setString(23, params.getOrDefault("about_this_property", params.getOrDefault("about_this_pg", "")));
        stmt.setString(24, params.getOrDefault("pg_images", null));
        stmt.setString(25, params.getOrDefault("status", "Active"));
    }

    // ----------------- Utilities -----------------

    private int parseIntSafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDoubleSafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    // parse application/x-www-form-urlencoded into a map (keys and values decoded)
    private Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                String key = URLDecoder.decode(parts[0], "UTF-8");
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
