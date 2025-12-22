package com.hotel.utilities;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Central DB configuration + DataSource manager.
 * Initialized once at application startup.
 */
public final class DbConfig {

    // ===== DB URLs =====
    private final String customerDbUrl;
    private final String partnerDbUrl;

    // ===== Credentials =====
    private final String username;
    private final String password;

    // ===== Image Config =====
    private final String imageBaseUrl;
    private final String hotelImagesPath;

    // ===== DataSources =====
    private final HikariDataSource customerDataSource;
    private final HikariDataSource partnerDataSource;

    // ===== Constructor =====
    public DbConfig(String customerDbUrl,
                    String partnerDbUrl,
                    String username,
                    String password,
                    String imageBaseUrl,
                    String hotelImagesPath) {

        if (customerDbUrl == null || partnerDbUrl == null ||
            username == null || password == null ||
            imageBaseUrl == null || hotelImagesPath == null) {
            throw new IllegalArgumentException("DbConfig parameters must not be null");
        }

        this.customerDbUrl = customerDbUrl;
        this.partnerDbUrl = partnerDbUrl;
        this.username = username;
        this.password = password;
        this.imageBaseUrl = imageBaseUrl;
        this.hotelImagesPath = hotelImagesPath;

        // Initialize pools
        this.customerDataSource = createDataSource(customerDbUrl);
        this.partnerDataSource = createDataSource(partnerDbUrl);
    }

    // ===== HikariCP Setup =====
    private HikariDataSource createDataSource(String jdbcUrl) {

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);

        // Explicit driver (recommended for core Java apps)
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Pool tuning (safe defaults)
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);

        // MySQL optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        return new HikariDataSource(config);
    }

    // ===== Public Accessors =====
    public DataSource getCustomerDataSource() {
        return customerDataSource;
    }

    public DataSource getPartnerDataSource() {
        return partnerDataSource;
    }

    public String getImageBaseUrl() {
        return imageBaseUrl;
    }

    public String getHotelImagesPath() {
        return hotelImagesPath;
    }

    // ===== Graceful Shutdown =====
    public void close() {
        if (!customerDataSource.isClosed()) {
            customerDataSource.close();
        }
        if (!partnerDataSource.isClosed()) {
            partnerDataSource.close();
        }
    }
}