package com.hotel.app;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

public class PgsHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public PgsHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        try {
            // Serve images
            if (path.startsWith("/hotel_images/")) {
                String filePath = path.substring("/hotel_images/".length());
                serveImage(exchange, filePath);
                return;
            }

            // GET paying_guest (Rest of the method remains unchanged)
            if (path.startsWith("/paying_guest")) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method Not Allowed");
                    return;
                }

                List<Map<String, Object>> pgsList = new ArrayList<>();
                String sql = "SELECT * FROM paying_guest_info WHERE Status = 'Active'";

                try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {

                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();

                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            String key = meta.getColumnLabel(i);
                            Object val = rs.getObject(i);
                            row.put(key, val == null ? "" : val.toString());
                        }

                        // Process PG_Images
                        String imgValue = (String) row.get("PG_Images");
                        List<String> imageList = new ArrayList<>();

                        if (imgValue != null && !imgValue.isEmpty()) {
                            String[] urls = imgValue.split(",");

                            for (String u : urls) {
                                if (u == null) continue;
                                String orig = u.trim();
                                if (orig.isEmpty()) continue;

                                // Strip brackets/quotes
                                while (orig.startsWith("[") || orig.startsWith("\"")) orig = orig.substring(1);
                                while (orig.endsWith("]") || orig.endsWith("\"")) orig = orig.substring(0, orig.length() - 1);
                                orig = orig.trim().replace("\\", "/"); 

                                // Full URL case
                                if (orig.startsWith("http://") || orig.startsWith("https://")) {
                                    String full = orig.replace("localhost", "10.0.2.2").trim();
                                    imageList.add(full);
                                    continue;
                                }

                                // Encode each path segment
                                String clean = orig.replaceAll("\\.\\.", "").replaceAll("//+", "/");
                                if (clean.startsWith("/")) clean = clean.substring(1);
                                String[] segments = clean.split("/");
                                StringBuilder encoded = new StringBuilder();
                                for (String seg : segments) {
                                    if (seg.isEmpty()) continue;
                                    String enc = URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20");
                                    if (encoded.length() > 0) encoded.append("/");
                                    encoded.append(enc);
                                }

                                if (encoded.length() > 0) {
                                    String finalUrl = "http://10.0.2.2:8080/hotel_images/" + encoded.toString();
                                    imageList.add(finalUrl);
                                }
                            }
                        }

                        row.put("PG_Images", imageList);
                        pgsList.add(row);
                    }

                    sendJson(exchange, pgsList);

                } catch (SQLException e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Database error: " + e.getMessage());
                }

                return;
            }

            sendError(exchange, 404, "Unknown endpoint: " + path);
        } catch (Exception ex) {
            ex.printStackTrace();
            sendError(exchange, 500, "Internal server error: " + ex.getMessage());
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
        }    }

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
                if (e.getValue() instanceof List) {
                    List<?> l = (List<?>) e.getValue();
                    sb.append("[");
                    for (int k = 0; k < l.size(); k++) {
                        sb.append("\"").append(escape(String.valueOf(l.get(k)))).append("\"");
                        if (k < l.size() - 1) sb.append(",");
                    }
                    sb.append("]");
                } else {
                    sb.append("\"").append(escape(String.valueOf(e.getValue()))).append("\"");
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