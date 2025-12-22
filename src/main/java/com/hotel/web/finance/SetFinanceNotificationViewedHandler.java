package com.hotel.web.finance;

import com.sun.net.httpserver.HttpHandler;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;

public class SetFinanceNotificationViewedHandler implements HttpHandler {

	private final DbConfig dbConfig;

    public SetFinanceNotificationViewedHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String partnerId = exchange.getRequestHeaders().getFirst("Authorization");

        try (Connection conn = dbConfig.getPartnerDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE Partner_Finance SET Notification_Viewed = 1 WHERE Partner_ID = ?"
             )) {

            stmt.setString(1, partnerId);
            stmt.executeUpdate();

        } catch (Exception e) {
            e.printStackTrace();
        }

        byte[] out = "{\"success\":true}".getBytes();
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }
}
