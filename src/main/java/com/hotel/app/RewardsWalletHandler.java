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
    private final ObjectMapper mapper = new ObjectMapper();

    public RewardsWalletHandler(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    private Connection getConnection() throws SQLException {
        return dbConfig.getCustomerDataSource().getConnection();
    }

    // =====================================================
    // MAIN ROUTER (FIXED — NON-BREAKING)
    // =====================================================
    @Override
    public void handle(HttpExchange exchange) throws IOException {

        ObjectNode response;
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            if ("GET".equalsIgnoreCase(method) && path.startsWith("/wallet")) {
                response = handleWalletRequest(exchange);

            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/coupon/validate")) {
                response = handleCouponValidate(exchange);

            } else {
                response = mapper.createObjectNode();
                response.put("error", "Invalid API endpoint");
            }

        } catch (Exception e) {
            e.printStackTrace();
            response = mapper.createObjectNode();
            response.put("error", e.getMessage());
        }

        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // =====================================================
    // COUPON VALIDATION (FIXED, ISOLATED)
    // POST /coupon/validate
    // =====================================================
    private ObjectNode handleCouponValidate(HttpExchange exchange) throws Exception {

        String body = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        ObjectNode req = (ObjectNode) mapper.readTree(body);

        String userId = req.path("userId").asText("").trim();
        String couponCode = req.path("couponCode").asText("").trim();
        double baseAmount = req.path("baseAmount").asDouble(0);

        ObjectNode resp = mapper.createObjectNode();

        if (userId.isEmpty() || couponCode.isEmpty()) {
            resp.put("valid", false);
            resp.put("message", "Missing userId or couponCode");
            return resp;
        }

        try (Connection conn = getConnection()) {

            String sql = """
                SELECT coupon_id, discount_type, discount_value,
                       max_discount, min_order_value, usage_limit_per_user
                FROM coupons
                WHERE UPPER(coupon_code) = UPPER(?)
                  AND status='active'
                  AND valid_from <= NOW()
                  AND valid_to >= NOW()
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, couponCode);
                ResultSet rs = ps.executeQuery();

                if (!rs.next()) {
                    resp.put("valid", false);
                    resp.put("message", "Invalid or expired coupon");
                    return resp;
                }

                String couponId = rs.getString("coupon_id");
                String discountType = rs.getString("discount_type");
                double discountValue = rs.getDouble("discount_value");

                Double maxDiscount =
                        rs.getObject("max_discount") == null ? null : rs.getDouble("max_discount");

                Double minOrderValue =
                        rs.getObject("min_order_value") == null ? null : rs.getDouble("min_order_value");

                Integer usageLimit =
                        rs.getObject("usage_limit_per_user") == null ? null : rs.getInt("usage_limit_per_user");

                if (minOrderValue != null && baseAmount < minOrderValue) {
                    resp.put("valid", false);
                    resp.put("message", "Minimum order value not met");
                    return resp;
                }

                int usedCount = getCouponUsage(conn, couponId, userId);
                if (usageLimit != null && usageLimit > 0 && usedCount >= usageLimit) {
                    resp.put("valid", false);
                    resp.put("message", "Coupon usage limit reached");
                    return resp;
                }

                double discountAmount;
                if ("percentage".equalsIgnoreCase(discountType)) {
                    discountAmount = baseAmount * (discountValue / 100.0);
                    if (maxDiscount != null) {
                        discountAmount = Math.min(discountAmount, maxDiscount);
                    }
                } else {
                    discountAmount = discountValue;
                }

                double discountedAmount = Math.max(0, baseAmount - discountAmount);

                // ❗ IMPORTANT:
                // Usage is intentionally NOT incremented here.
                // It must be done only after successful payment.

                resp.put("valid", true);
                resp.put("couponTitle", couponCode);
                resp.put("discountAmount", discountAmount);
                resp.put("discountedAmount", discountedAmount);
            }
        }

        return resp;
    }

    // =====================================================
    // COUPON USAGE (READ-ONLY HERE)
    // =====================================================
    private int getCouponUsage(Connection conn, String couponId, String userId) throws SQLException {
        String sql = "SELECT usage_count FROM coupon_usage WHERE coupon_id=? AND user_id=? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, couponId);
            ps.setString(2, userId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("usage_count") : 0;
        }
    }

    // =====================================================
    // UTIL: parse query
    // =====================================================
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

    // =====================================================
    // UTIL: referral code generator (UNCHANGED)
    // =====================================================
    private String generateReferralCode(String userId) {
        String base = userId + "|REFERRAL_SALT";
        int hash = Math.abs(base.hashCode());
        String base36 = Integer.toString(hash, 36).toUpperCase();
        String hashPart = base36.length() > 5 ? base36.substring(0, 5) : base36;
        int numericSuffix = hash % 1000;
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

                String insertWallet = "INSERT INTO wallets(wallet_id, user_id, balance) VALUES(?,?,0.00)";
                try (PreparedStatement ps = conn.prepareStatement(insertWallet)) {
                    ps.setString(1, walletId);
                    ps.setString(2, userId);
                    ps.executeUpdate();
                }

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
