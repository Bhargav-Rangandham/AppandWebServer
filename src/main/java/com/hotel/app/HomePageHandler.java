package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class HomePageHandler implements HttpHandler {

    private final DbConfig dbConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ✅ Inject DbConfig
    public HomePageHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        addCorsHeaders(exchange);

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, "{\"error\":\"Only GET allowed\"}");
            return;
        }

        URI requestURI = exchange.getRequestURI();
        String query = requestURI.getQuery();

        String hotelType = null;
        String searchQuery = null;

        if (query != null && !query.isEmpty()) {
            for (String param : query.split("&")) {
                if (param.isBlank()) continue;
                String[] pair = param.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length > 1
                        ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                        : "";
                if ("type".equalsIgnoreCase(key)) {
                    hotelType = value.trim();
                } else if ("query".equalsIgnoreCase(key) || "q".equalsIgnoreCase(key)) {
                    searchQuery = value.trim();
                }
            }
        }

        String normalizedType = hotelType == null
                ? ""
                : hotelType.replaceAll("[_\\-\\s]", "").toLowerCase();

        if ("payingguest".equals(normalizedType)) {
            handlePayingGuestRequest(exchange, searchQuery);
            return;
        }

        handleHotelRequest(exchange, hotelType, searchQuery);
    }

    // =================== HOTELS ===================
    private void handleHotelRequest(HttpExchange exchange,
                                    String hotelType,
                                    String searchQuery) throws IOException {

        List<Map<String, Object>> hotels = new ArrayList<>();

        String baseSql = """
            SELECT Hotel_ID, Partner_ID, Hotel_Name, Hotel_Type, Room_Type,
                   Address, City, State, Country, Pincode, Hotel_Location,
                   Total_Rooms, Available_Rooms, Room_Price, Amenities,
                   Description, Policies, Rating, Hotel_Contact,
                   About_This_Property, Hotel_Images, Customization, Status
            FROM Hotels_info
            WHERE Status = 'Active'
            """;

        StringBuilder sql = new StringBuilder(baseSql);

        if (hotelType != null && !hotelType.isBlank()) {
            sql.append(" AND LOWER(Hotel_Type) = LOWER(?)");
        }

        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append("""
                AND (
                    LOWER(Hotel_Name) LIKE ?
                    OR LOWER(City) LIKE ?
                    OR LOWER(State) LIKE ?
                    OR LOWER(Country) LIKE ?
                )
            """);
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            int idx = 1;
            if (hotelType != null && !hotelType.isBlank()) {
                stmt.setString(idx++, hotelType);
            }
            if (searchQuery != null && !searchQuery.isBlank()) {
                String p = "%" + searchQuery.toLowerCase() + "%";
                stmt.setString(idx++, p);
                stmt.setString(idx++, p);
                stmt.setString(idx++, p);
                stmt.setString(idx++, p);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> hotel = new LinkedHashMap<>();

                    hotel.put("Hotel_ID", rs.getString("Hotel_ID"));
                    hotel.put("Partner_ID", rs.getString("Partner_ID"));
                    hotel.put("Hotel_Name", rs.getString("Hotel_Name"));
                    hotel.put("Hotel_Type", rs.getString("Hotel_Type"));
                    hotel.put("Room_Type", rs.getString("Room_Type"));
                    hotel.put("Address", rs.getString("Address"));
                    hotel.put("City", rs.getString("City"));
                    hotel.put("State", rs.getString("State"));
                    hotel.put("Country", rs.getString("Country"));
                    hotel.put("Pincode", rs.getString("Pincode"));
                    hotel.put("Hotel_Location", rs.getString("Hotel_Location"));
                    hotel.put("Total_Rooms", rs.getObject("Total_Rooms"));
                    hotel.put("Available_Rooms", rs.getObject("Available_Rooms"));
                    hotel.put("Room_Price", rs.getObject("Room_Price"));
                    hotel.put("Amenities", rs.getString("Amenities"));
                    hotel.put("Description", rs.getString("Description"));
                    hotel.put("Policies", rs.getString("Policies"));
                    hotel.put("Rating", rs.getObject("Rating"));
                    hotel.put("Hotel_Contact", rs.getString("Hotel_Contact"));
                    hotel.put("About_This_Property", rs.getString("About_This_Property"));
                    hotel.put("Customization", rs.getString("Customization"));
                    hotel.put("Status", rs.getString("Status"));

                    hotel.put("Hotel_Images",
                            buildImageList(rs.getString("Hotel_Images")));

                    hotels.add(hotel);
                }
            }

            sendJsonResponse(exchange, 200,
                    objectMapper.writeValueAsString(hotels));

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500,
                    "{\"error\":\"Internal error\"}");
        }
    }

    // =================== PAYING GUEST ===================
    private void handlePayingGuestRequest(HttpExchange exchange,
                                          String searchQuery) throws IOException {

        List<Map<String, Object>> pgs = new ArrayList<>();

        String baseSql = """
            SELECT PG_ID, Partner_ID, PG_Name, PG_Type, Room_Type,
                   Address, City, State, Country, Pincode,
                   Total_Single_Sharing_Rooms,
                   Total_Double_SHARING_ROOMS,
                   Total_Three_SHARING_ROOMS,
                   Total_FOUR_SHARING_ROOMS,
                   Total_FIVE_SHARING_ROOMS,
                   Hotel_Location, Available_Rooms, Room_Price,
                   Amenities, Description, Policies, Rating,
                   PG_Contact, About_This_PG, PG_Images, Status
            FROM paying_guest_info
            WHERE Status = 'Active'
        """;

        StringBuilder sql = new StringBuilder(baseSql);

        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append("""
                AND (
                    LOWER(PG_Name) LIKE ?
                    OR LOWER(City) LIKE ?
                    OR LOWER(State) LIKE ?
                    OR LOWER(Country) LIKE ?
                )
            """);
        }

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            if (searchQuery != null && !searchQuery.isBlank()) {
                String p = "%" + searchQuery.toLowerCase() + "%";
                stmt.setString(1, p);
                stmt.setString(2, p);
                stmt.setString(3, p);
                stmt.setString(4, p);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> pg = new LinkedHashMap<>();

                    pg.put("PG_ID", rs.getString("PG_ID"));
                    pg.put("Partner_ID", rs.getString("Partner_ID"));
                    pg.put("PG_Name", rs.getString("PG_Name"));
                    pg.put("PG_Type", rs.getString("PG_Type"));
                    pg.put("Room_Type", rs.getString("Room_Type"));
                    pg.put("Address", rs.getString("Address"));
                    pg.put("City", rs.getString("City"));
                    pg.put("State", rs.getString("State"));
                    pg.put("Country", rs.getString("Country"));
                    pg.put("Pincode", rs.getString("Pincode"));

                    pg.put("Total_Single_Sharing_Rooms", rs.getObject("Total_Single_Sharing_Rooms"));
                    pg.put("Total_Double_SHARING_ROOMS", rs.getObject("Total_Double_SHARING_ROOMS"));
                    pg.put("Total_Three_SHARING_ROOMS", rs.getObject("Total_Three_SHARING_ROOMS"));
                    pg.put("Total_FOUR_SHARING_ROOMS", rs.getObject("Total_FOUR_SHARING_ROOMS"));
                    pg.put("Total_FIVE_SHARING_ROOMS", rs.getObject("Total_FIVE_SHARING_ROOMS"));

                    pg.put("Hotel_Location", rs.getString("Hotel_Location"));
                    pg.put("Available_Rooms", rs.getObject("Available_Rooms"));
                    pg.put("Room_Price", rs.getObject("Room_Price"));
                    pg.put("Amenities", rs.getString("Amenities"));
                    pg.put("Description", rs.getString("Description"));
                    pg.put("Policies", rs.getString("Policies"));
                    pg.put("Rating", rs.getObject("Rating"));
                    pg.put("PG_Contact", rs.getString("PG_Contact"));
                    pg.put("About_This_PG", rs.getString("About_This_PG"));
                    pg.put("Status", rs.getString("Status"));

                    pg.put("PG_Images",
                            buildImageList(rs.getString("PG_Images")));

                    pgs.add(pg);
                }
            }

            sendJsonResponse(exchange, 200,
                    objectMapper.writeValueAsString(pgs));

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500,
                    "{\"error\":\"Internal error\"}");
        }
    }

    // =================== HELPERS ===================
    private List<String> buildImageList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) return list;

        for (String p : raw.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) continue;

            if (t.startsWith("http://") || t.startsWith("https://")) {
                list.add(t);
            } else {
                list.add(dbConfig.getImageBaseUrl()
                        + t.replaceAll("^/+", ""));
            }
        }
        return list;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private void sendJsonResponse(HttpExchange exchange, int status, String json)
            throws IOException {

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
