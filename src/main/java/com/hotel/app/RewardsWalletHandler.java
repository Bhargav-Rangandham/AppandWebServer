package com.hotel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.utilities.DbConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RewardsWalletHandler implements HttpHandler {

	private final DbConfig dbConfig;

    // ✅ Inject DbConfig via constructor
    public RewardsWalletHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private Connection getConnection() throws SQLException {
    	Connection conn = dbConfig.getCustomerDataSource().getConnection();
        return conn;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        ObjectNode response = mapper.createObjectNode();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            if ("GET".equalsIgnoreCase(method) && "/wallet".equals(path)) {
                response = handleWalletRequest(exchange);
            } else {
                response.put("error", "Invalid API endpoint");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("error", e.getMessage());
        }

        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ----------------- UTIL: parse query -----------------
    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) return result;

        for (String pair : query.split("&")) {
            String[] keyValue = pair.split("=");
            String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
            String value = keyValue.length > 1
                    ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8)
                    : "";
            result.put(key, value);
        }
        return result;
    }

    // ----------------- UTIL: referral code generator -----------------
    /**
     * Referral code format:
     *  PREFIX "HB-"
     *  + Base36 hash of (userId + salt)
     *  + 3-digit suffix derived from hash (pseudo-random but deterministic)
     *
     * Example: HB-A1C9F237
     */
    private String generateReferralCode(String userId) {
        String base = userId + "|REFERRAL_SALT";
        int hash = Math.abs(base.hashCode()); // stable pseudo-random
        String base36 = Integer.toString(hash, 36).toUpperCase(); // e.g. "A1C9F"

        String hashPart = base36.length() > 5 ? base36.substring(0, 5) : base36;
        int numericSuffix = hash % 1000; // 0 to 999
        String suffix = String.format("%03d", numericSuffix);

        return "HB-" + hashPart + suffix;
    }

    // ----------------- MAIN: /wallet handler -----------------
    private ObjectNode handleWalletRequest(HttpExchange exchange) throws Exception {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String userId = params.get("userId");

        ObjectNode json = mapper.createObjectNode();

        if (userId == null || userId.isBlank()) {
            json.put("error", "Missing userId");
            json.put("walletExists", false);
            return json;
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            String walletId = null;
            double balance = 0.0;

            // 1) Check wallet
            String walletSql = "SELECT wallet_id, balance FROM wallets WHERE user_id=? LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(walletSql)) {
                ps.setString(1, userId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    walletId = rs.getString("wallet_id");
                    balance = rs.getDouble("balance");
                }
            }

            // 2) First-time user: create wallet + referral row
            if (walletId == null) {
                walletId = UUID.randomUUID().toString();

                // Create wallet
                String insertWallet = "INSERT INTO wallets(wallet_id, user_id, balance) VALUES(?,?,0.00)";
                try (PreparedStatement ps = conn.prepareStatement(insertWallet)) {
                    ps.setString(1, walletId);
                    ps.setString(2, userId);
                    ps.executeUpdate();
                }

                // Create empty referral tracking (no code column in table yet, only reward tracking)
                String insertReferral = "INSERT INTO referrals(referral_id, referrer_user_id, reward_status, reward_amount) VALUES(?,?,?,?)";
                try (PreparedStatement ps = conn.prepareStatement(insertReferral)) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, userId);
                    ps.setString(3, "not_eligible");
                    ps.setDouble(4, 0.00);
                    ps.executeUpdate();
                }

                conn.commit();
                json.put("walletCreated", true);
                balance = 0.0;
            } else {
                json.put("walletCreated", false);
            }

            // Wallet exists after this point
            json.put("walletExists", true);
            json.put("walletId", walletId);
            json.put("balance", balance);

            // 3) Referral code + stats
            String referralCode = generateReferralCode(userId);
            json.put("referralCode", referralCode);

            String referralStatsSql = """
                    SELECT COUNT(*) AS referred, COALESCE(SUM(reward_amount),0) AS totalReward
                    FROM referrals WHERE referrer_user_id=? AND reward_status='credited'
                    """;
            try (PreparedStatement ps = conn.prepareStatement(referralStatsSql)) {
                ps.setString(1, userId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    json.put("referralCount", rs.getInt("referred"));
                    json.put("referralEarnings", rs.getDouble("totalReward"));
                } else {
                    json.put("referralCount", 0);
                    json.put("referralEarnings", 0.0);
                }
            }

            // 4) Wallet transactions
            ArrayNode txArray = mapper.createArrayNode();
            String txSql = """
                    SELECT txn_id, type, amount, direction, status, description, created_at
                    FROM wallet_transactions
                    WHERE wallet_id=?
                    ORDER BY created_at DESC
                    """;
            try (PreparedStatement ps = conn.prepareStatement(txSql)) {
                ps.setString(1, walletId);
                ResultSet rs = ps.executeQuery();

                while (rs.next()) {
                    ObjectNode tx = mapper.createObjectNode();
                    tx.put("txnId", rs.getString("txn_id"));
                    tx.put("type", rs.getString("type"));
                    tx.put("amount", rs.getDouble("amount"));
                    tx.put("direction", rs.getString("direction"));
                    tx.put("status", rs.getString("status"));
                    tx.put("description", rs.getString("description"));
                    tx.put("createdAt", rs.getString("created_at"));
                    txArray.add(tx);
                }
            }
            json.set("transactions", txArray);

            // 5) Refunds
            ArrayNode refundArray = mapper.createArrayNode();
            String refundSql = """
                    SELECT refund_id, txn_id, refunded_amount, refund_method, status, created_at
                    FROM refunds
                    WHERE txn_id IN (SELECT txn_id FROM wallet_transactions WHERE wallet_id=?)
                    """;
            try (PreparedStatement ps = conn.prepareStatement(refundSql)) {
                ps.setString(1, walletId);
                ResultSet rs = ps.executeQuery();

                while (rs.next()) {
                    ObjectNode r = mapper.createObjectNode();
                    r.put("refundId", rs.getString("refund_id"));
                    r.put("txnId", rs.getString("txn_id"));
                    r.put("amount", rs.getDouble("refunded_amount"));
                    r.put("method", rs.getString("refund_method"));
                    r.put("status", rs.getString("status"));
                    r.put("createdAt", rs.getString("created_at"));
                    refundArray.add(r);
                }
            }
            json.set("refunds", refundArray);

            // 6) Coupons + usage + rules
            ArrayNode couponArray = mapper.createArrayNode();
            String couponSql = """
                SELECT c.*, COALESCE(u.usage_count,0) AS used
                FROM coupons c
                LEFT JOIN coupon_usage u ON c.coupon_id = u.coupon_id AND u.user_id=?
                WHERE c.status='active'
                  AND c.valid_from <= NOW()
                  AND c.valid_to >= NOW()
                """;

            try (PreparedStatement ps = conn.prepareStatement(couponSql)) {
                ps.setString(1, userId);
                ResultSet rs = ps.executeQuery();

                String ruleSql = "SELECT rule_type, rule_value FROM coupon_rules WHERE coupon_id=?";
                try (PreparedStatement rulePs = conn.prepareStatement(ruleSql)) {
                    while (rs.next()) {
                        ObjectNode c = mapper.createObjectNode();
                        String couponId = rs.getString("coupon_id");

                        c.put("couponId", couponId);
                        c.put("couponCode", rs.getString("coupon_code"));
                        c.put("title", rs.getString("title"));
                        c.put("description", rs.getString("description"));
                        c.put("termsConditions", rs.getString("terms_conditions"));
                        c.put("discountType", rs.getString("discount_type"));
                        c.put("discountValue", rs.getDouble("discount_value"));
                        c.put("maxDiscount",
                                rs.getObject("max_discount") == null ? null : rs.getDouble("max_discount"));
                        c.put("validFrom", rs.getString("valid_from"));
                        c.put("validTo", rs.getString("valid_to"));
                        c.put("usageLimitPerUser", rs.getInt("usage_limit_per_user"));
                        c.put("usageCountByUser", rs.getInt("used"));
                        c.put("minOrderValue", rs.getDouble("min_order_value"));
                        c.put("applicablePlatform", rs.getString("applicable_platform"));
                        c.put("status", rs.getString("status"));

                        // rules
                        ArrayNode rulesArray = mapper.createArrayNode();
                        rulePs.setString(1, couponId);
                        try (ResultSet ruleRs = rulePs.executeQuery()) {
                            while (ruleRs.next()) {
                                ObjectNode rule = mapper.createObjectNode();
                                rule.put("ruleType", ruleRs.getString("rule_type"));
                                rule.put("ruleValue", ruleRs.getString("rule_value"));
                                rulesArray.add(rule);
                            }
                        }
                        c.set("rules", rulesArray);

                        couponArray.add(c);
                    }
                }
            }
            json.set("coupons", couponArray);

            conn.commit();
        }

        return json;
    }
}
