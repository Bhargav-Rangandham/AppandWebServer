package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class BookingHistoryHandler implements HttpHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DbConfig dbConfig;

    public BookingHistoryHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "GET" -> handleBookingHistory(exchange);
            case "PUT" -> handlePutRequests(exchange);
            default -> sendResponse(exchange, 405, json("error", "Method Not Allowed"));
        }
    }

    private void handlePutRequests(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI().normalize();

        if (uri.getPath().endsWith("/cancel-booking")) {
            handleCancelBooking(exchange);
        } else if (uri.getPath().endsWith("/update-booking-dates")) {
            handleUpdateBookingDates(exchange);
        } else {
            sendResponse(exchange, 404, json("error", "Invalid API path"));
        }
    }

    // -------------------- GET HISTORY --------------------
    private void handleBookingHistory(HttpExchange exchange) throws IOException {

        Map<String, String> params = decodeParams(exchange.getRequestURI().getQuery());

        String email = params.getOrDefault("email", "").trim();
        String userId = params.getOrDefault("userId", "").trim();

        boolean showUpcoming = params.getOrDefault("includeUpcoming", "false")
                .trim().equalsIgnoreCase("true");

        if (email.isEmpty() && userId.isEmpty()) {
            sendResponse(exchange, 400, json("error", "Missing email or userId"));
            return;
        }

        String sql = """
                SELECT * FROM Bookings_Info
                WHERE (Email=? OR User_ID=?)
                ORDER BY Check_In_Date DESC
                """;

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, email);
            stmt.setString(2, userId);

            ResultSet rs = stmt.executeQuery();

            LocalDate today = LocalDate.now();

            while (rs.next()) {

                LocalDate checkIn =
                        rs.getDate("Check_In_Date") != null
                                ? rs.getDate("Check_In_Date").toLocalDate()
                                : null;

                LocalDate checkOut =
                        rs.getDate("Check_Out_Date") != null
                                ? rs.getDate("Check_Out_Date").toLocalDate()
                                : null;

                String status = Optional.ofNullable(rs.getString("Booking_Status"))
                        .orElse("")
                        .trim()
                        .toUpperCase();

                boolean include = false;

                if (showUpcoming) {
                    /*
                     UPCOMING:
                     - Check-in today or future
                     - Status Pending / Confirmed
                    */
                    if (checkIn != null &&
                            (status.equals("PENDING") || status.equals("CONFIRMED")) &&
                            (checkIn.isEqual(today) || checkIn.isAfter(today))) {
                        include = true;
                    }

                } else {
                    /*
                     PAST:
                     - Checkout before today
                     - OR completed / cancelled
                     - OR checkout NULL but check-in in the past
                    */
                    if (checkOut != null && checkOut.isBefore(today)) {
                        include = true;
                    } else if (checkOut == null && checkIn != null && checkIn.isBefore(today)) {
                        include = true;
                    } else if (status.equals("COMPLETED") || status.equals("CANCELLED")) {
                        include = true;
                    }
                }

                if (include) {
                    results.add(mapRow(rs));
                }
            }

            sendResponse(exchange, 200, objectMapper.writeValueAsString(results));

        } catch (Exception e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    // -------------------- DATE CHANGE --------------------
    private void handleUpdateBookingDates(HttpExchange exchange) throws IOException {
        Map<String, Object> data = objectMapper.readValue(exchange.getRequestBody(), Map.class);

        String bookingId = Objects.toString(data.get("Booking_ID"), "");
        String newCheckIn = Objects.toString(data.get("Check_In_Date"), "");
        String newCheckOut = Objects.toString(data.get("Check_Out_Date"), "");

        if (bookingId.isBlank() || newCheckIn.isBlank() || newCheckOut.isBlank()) {
            sendResponse(exchange, 400, json("error", "Missing parameters"));
            return;
        }

        String fetchSql = "SELECT Room_Price_Per_Day, GST FROM Bookings_Info WHERE Booking_ID=?";
        String updateSql = """
                UPDATE Bookings_Info SET
                Check_In_Date=?, 
                Check_Out_Date=?, 
                Total_Days_at_Stay=?, 
                Final_Payable_Amount=?,
                Booking_Status='PENDING'
                WHERE Booking_ID=?
                """;

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement fetch = conn.prepareStatement(fetchSql);
             PreparedStatement update = conn.prepareStatement(updateSql)) {

            fetch.setString(1, bookingId);
            ResultSet rs = fetch.executeQuery();

            if (!rs.next()) {
                sendResponse(exchange, 404, json("error", "Booking not found"));
                return;
            }

            LocalDate in = LocalDate.parse(newCheckIn);
            LocalDate out = LocalDate.parse(newCheckOut);
            long days = ChronoUnit.DAYS.between(in, out);

            if (days <= 0) {
                sendResponse(exchange, 400, json("error", "Invalid stay duration"));
                return;
            }

            double price = rs.getDouble("Room_Price_Per_Day") * days + rs.getDouble("GST");

            update.setDate(1, java.sql.Date.valueOf(in));
            update.setDate(2, java.sql.Date.valueOf(out));
            update.setInt(3, (int) days);
            update.setDouble(4, price);
            update.setString(5, bookingId);
            update.executeUpdate();

            sendResponse(exchange, 200, json("success", "Dates updated successfully"));

        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    // -------------------- CANCEL BOOKING --------------------
    private void handleCancelBooking(HttpExchange exchange) throws IOException {
        Map<String, Object> body = objectMapper.readValue(exchange.getRequestBody(), Map.class);
        String bookingId = Objects.toString(body.get("Booking_ID"), "");

        if (bookingId.isBlank()) {
            sendResponse(exchange, 400, json("error", "Missing Booking ID"));
            return;
        }

        String sql = "UPDATE bookings_info SET Booking_Status='CANCELLED', Refund_Status='Refund Initiated' WHERE Booking_ID=?";

        try (Connection conn = dbConfig.getCustomerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, bookingId);
            stmt.executeUpdate();

            sendResponse(exchange, 200, json("success", "Booking cancelled"));

        } catch (SQLException e) {
            sendResponse(exchange, 500, json("error", e.getMessage()));
        }
    }

    // -------------------- MAP DB → JSON --------------------
    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();

        for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object value = rs.getObject(i);

            if (value instanceof java.sql.Date date)
                value = date.toLocalDate().toString();

            row.put(meta.getColumnLabel(i), value);
        }
        return row;
    }

    private Map<String, String> decodeParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;

        for (String p : query.split("&")) {
            String[] pair = p.split("=", 2); // <-- IMPORTANT: split once
            if (pair.length == 2) {
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                        .replaceAll("[\\s\\r\\n]+$", "") // remove trailing HTTP artifacts
                        .trim();
                map.put(key, value);
            }
        }
        return map;
    }


    private void sendResponse(HttpExchange ex, int code, Object body) throws IOException {
        String json = body instanceof String ? (String) body : objectMapper.writeValueAsString(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, json.getBytes().length);
        try (OutputStream os = ex.getResponseBody()) { os.write(json.getBytes()); }
    }

    private Map<String, Object> json(String k, Object v) {
        return Map.of(k, v);
    }
}