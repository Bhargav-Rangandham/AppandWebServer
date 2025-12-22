package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.sql.*;
import java.util.*;

public class HotelsHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public HotelsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        try {
            // Serve images
            if (path.startsWith("/hotel_images/")) {
                String fileName = path.substring("/hotel_images/".length());
                serveImage(exchange, fileName);
                return;
            }

            // Serve hotel data
            if (path.startsWith("/hotels")) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method Not Allowed");
                    return;
                }

                String query = uri.getQuery();
                String typeFilter = null;
                boolean detailsPage = false;
                String hotelId = null;

                if (query != null) {
                    if (query.contains("type=")) {
                        typeFilter = URLDecoder.decode(query.split("type=")[1].split("&")[0],
                                StandardCharsets.UTF_8);
                    }
                    if (query.contains("hotel_id=")) {
                        detailsPage = true;
                        hotelId = URLDecoder.decode(query.split("hotel_id=")[1].split("&")[0],
                                StandardCharsets.UTF_8);
                    }
                }

                List<Map<String, Object>> hotels = new ArrayList<>();

                String sql = "SELECT Hotel_ID, Partner_ID, Hotel_Name, Hotel_Type, Room_Type, Address, City, State, Country, " +
                        "Pincode, Hotel_Location, Total_Rooms, Available_Rooms, Room_Price, Amenities, Description, " +
                        "Policies, Rating, Hotel_Contact, About_This_Property, Hotel_Images, Customization, Status " +
                        "FROM Hotels_info WHERE Status = 'Active'";

                if (typeFilter != null && !typeFilter.trim().isEmpty()) {
                    sql += " AND Hotel_Type = ?";
                }
                if (detailsPage && hotelId != null) {
                    sql += " AND Hotel_ID = ?";
                }

                try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {

                    int paramIndex = 1;
                    if (typeFilter != null && !typeFilter.trim().isEmpty()) {
                        stmt.setString(paramIndex++, typeFilter);
                    }
                    if (detailsPage && hotelId != null) {
                        stmt.setString(paramIndex++, hotelId);
                    }

                    ResultSet rs = stmt.executeQuery();
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();

                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            String key = meta.getColumnLabel(i);
                            Object val = rs.getObject(i);
                            row.put(key, val == null ? "" : val.toString());
                        }

                        // Handle Hotel Images properly
                        String imgValue = (String) row.get("Hotel_Images");
                        List<String> imageList = new ArrayList<>();
                        if (imgValue != null && !imgValue.isEmpty()) {
                            String[] imageNames = imgValue.split(",");
                            for (String name : imageNames) {
                                name = name.trim();
                                if (!name.startsWith("http")) {
                                    name = "http://10.0.2.2:8080/hotel_images/" + name;
                                }
                                imageList.add(name);
                            }
                        }

                        // Always return full list
                        row.put("Hotel_Images", imageList);

                        hotels.add(row);
                    }

                    sendJson(exchange, hotels);

                } catch (SQLException e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Database error: " + e.getMessage());
                }
                return;
            }

            sendError(exchange, 404, "Unknown endpoint: " + path);
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void serveImage(HttpExchange exchange, String fileName) throws IOException {
        fileName = fileName.replaceAll("[/\\\\]+", "");
        File file = new File(dbConfig.getHotelImagesPath(), fileName);

        if (!file.exists() || file.isDirectory()) {
            sendError(exchange, 404, "Image not found");
            return;
        }

        String contentType = Files.probeContentType(file.toPath());
        if (contentType == null) contentType = "application/octet-stream";

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, file.length());

        try (OutputStream os = exchange.getResponseBody(); FileInputStream fis = new FileInputStream(file)) {
            fis.transferTo(os);
        }
    }

    private void sendJson(HttpExchange exchange, List<Map<String, Object>> data) throws IOException {
        String json = toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        String err = "{\"error\":\"" + escape(msg) + "\"}";
        byte[] bytes = err.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String toJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> m = list.get(i);
            sb.append("{");
            int j = 0;
            for (var e : m.entrySet()) {
                sb.append("\"").append(escape(e.getKey())).append("\":");
                Object val = e.getValue();
                if (val instanceof List) {
                    sb.append("[");
                    List<?> l = (List<?>) val;
                    for (int k = 0; k < l.size(); k++) {
                        sb.append("\"").append(escape(String.valueOf(l.get(k)))).append("\"");
                        if (k < l.size() - 1) sb.append(",");
                    }
                    sb.append("]");
                } else {
                    sb.append("\"").append(escape(String.valueOf(val))).append("\"");
                }
                if (j++ < m.size() - 1) sb.append(",");
            }
            sb.append("}");
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
