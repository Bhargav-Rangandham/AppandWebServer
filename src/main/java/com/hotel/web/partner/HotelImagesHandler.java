package com.hotel.web.partner;

import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class HotelImagesHandler implements HttpHandler {

    private final DbConfig dbConfig;

    public HotelImagesHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        // ===== CORS preflight =====
        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"GET".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        if (!path.startsWith("/hotel_images/")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        // ===== Decode full relative path (KEEP subfolders) =====
        String relativePath = URLDecoder.decode(
                path.substring("/hotel_images/".length()),
                StandardCharsets.UTF_8
        );

        // ===== Normalize & prevent directory traversal =====
        Path imageRoot = Path.of(dbConfig.getHotelImagesPath()).normalize();
        Path resolvedPath = imageRoot.resolve(relativePath).normalize();

        // Security check
        if (!resolvedPath.startsWith(imageRoot)) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        File file = resolvedPath.toFile();

        if (!file.exists() || file.isDirectory()) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        String contentType = Files.probeContentType(file.toPath());
        if (contentType == null) contentType = "application/octet-stream";

        // ===== Headers =====
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        // Cache images for 7 days
        exchange.getResponseHeaders().set(
                "Cache-Control", "public, max-age=604800, immutable");

        exchange.sendResponseHeaders(200, file.length());

        try (OutputStream os = exchange.getResponseBody();
             FileInputStream fis = new FileInputStream(file)) {
            fis.transferTo(os);
        }
    }
}
