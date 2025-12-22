package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.sql.*;
import java.util.*;

public class AppFilterHandler implements HttpHandler {

	private final DbConfig dbConfig;

    // ✅ Inject DbConfig via constructor
    public AppFilterHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleFilterRequest(exchange);
        } else {
            sendResponse(exchange, "Only POST method is supported", 405);
        }
    }

    private void handleFilterRequest(HttpExchange exchange) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), "utf-8");
             BufferedReader br = new BufferedReader(isr)) {

            StringBuilder requestBody = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                requestBody.append(line);
            }

            String bodyStr = requestBody.toString().trim();
            if (bodyStr.isEmpty()) {
                sendJsonResponse(exchange, new JSONArray().toString(), 200);
                return;
            }

            JSONObject requestJson = new JSONObject(bodyStr);

            // New frontend sends: { "type": "...", "filters": {...}, "sortBy": "price_lowest" }
            JSONObject filters;
            String sortBy = "";

            if (requestJson.has("filters")) {
                filters = requestJson.getJSONObject("filters");
            } else {
                // Backward-compat: the client may have sent filters at top-level
                filters = requestJson;
            }

            if (requestJson.has("sortBy")) {
                sortBy = requestJson.getString("sortBy");
            } else if (filters.has("sortBy")) {
                sortBy = filters.getString("sortBy");
            }

            JSONArray result = fetchHotelsWithFilters(filters, sortBy);
            sendJsonResponse(exchange, result.toString(), 200);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, "Error: " + e.getMessage(), 500);
        }
    }

    private JSONArray fetchHotelsWithFilters(JSONObject filters, String sortBy) throws SQLException {
        JSONArray hotelsArray = new JSONArray();
        StringBuilder baseQuery = new StringBuilder("SELECT * FROM Hotels_info WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // City filter (flexible: check City and State)
        if (filters.has("city") && !filters.optString("city").trim().isEmpty()) {
            baseQuery.append(" AND (LOWER(City) LIKE ? OR LOWER(State) LIKE ?)");
            String cityLike = "%" + filters.getString("city").toLowerCase() + "%";
            params.add(cityLike);
            params.add(cityLike);
        }

        // Hotel Type filter
        if (filters.has("hotelType") && !filters.optString("hotelType").trim().isEmpty()) {
            baseQuery.append(" AND LOWER(Hotel_Type) = ?");
            params.add(filters.getString("hotelType").toLowerCase());
        }

        // Room Type filter
        if (filters.has("roomType") && !filters.optString("roomType").trim().isEmpty()) {
            baseQuery.append(" AND LOWER(Room_Type) = ?");
            params.add(filters.getString("roomType").toLowerCase());
        }

        // Price Range filter
        if (filters.has("minPrice") && filters.has("maxPrice")) {
            double minPrice = filters.getDouble("minPrice");
            double maxPrice = filters.getDouble("maxPrice");
            baseQuery.append(
                    " AND (CAST(REPLACE(REPLACE(Room_Price,'₹',''),',','') AS DECIMAL(10,2)) BETWEEN ? AND ?)"
            );
            params.add(minPrice);
            params.add(maxPrice);
        }

        // Rating filter (numeric)
        if (filters.has("rating")) {
            double rating = filters.getDouble("rating");
            baseQuery.append(" AND Rating >= ?");
            params.add(rating);
        }

        // Amenities filter: expects JSON array of strings
        if (filters.has("amenities")) {
            try {
                org.json.JSONArray amenities = filters.getJSONArray("amenities");
                for (int i = 0; i < amenities.length(); i++) {
                    baseQuery.append(" AND LOWER(Amenities) LIKE ?");
                    params.add("%" + amenities.getString(i).toLowerCase() + "%");
                }
            } catch (Exception ignored) {
            }
        }

        // Partner ID / Hotel ID
        if (filters.has("partnerId") && !filters.optString("partnerId").isEmpty()) {
            baseQuery.append(" AND Partner_ID = ?");
            params.add(filters.getString("partnerId"));
        }
        if (filters.has("hotelId") && !filters.optString("hotelId").isEmpty()) {
            baseQuery.append(" AND Hotel_ID = ?");
            params.add(filters.getString("hotelId"));
        }

        // Available rooms only (optional)
        if (filters.has("availableOnly") && filters.optBoolean("availableOnly", false)) {
            baseQuery.append(" AND Available_Rooms > 0");
        }

        // Customization filter (optional)
        if (filters.has("customization")) {
            String cust = filters.optString("customization");
            if (!cust.isEmpty()) {
                baseQuery.append(" AND Customization = ?");
                params.add(cust);
            }
        }

        // Status active only
        baseQuery.append(" AND Status = 'Active'");

        // Sorting
        String orderClause = "";
        if (sortBy != null) {
            switch (sortBy) {
                case "price_lowest":
                    orderClause = " ORDER BY CAST(REPLACE(REPLACE(Room_Price,'₹',''),',','') AS DECIMAL(10,2)) ASC";
                    break;
                case "price_highest":
                    orderClause = " ORDER BY CAST(REPLACE(REPLACE(Room_Price,'₹',''),',','') AS DECIMAL(10,2)) DESC";
                    break;
                case "top_rated":
                    orderClause = " ORDER BY Rating DESC";
                    break;
                default:
                    orderClause = "";
            }
        }

        String finalQuery = baseQuery.toString() + orderClause + " LIMIT 100"; // limit for performance

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(finalQuery)) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                JSONObject hotel = new JSONObject();
                for (int i = 1; i <= columnCount; i++) {
                    String column = meta.getColumnLabel(i);
                    Object value = rs.getObject(column);
                    if (value == null) hotel.put(column, JSONObject.NULL);
                    else hotel.put(column, value);
                }
                hotelsArray.put(hotel);
            }
        }

        return hotelsArray;
    }

    private void sendJsonResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
