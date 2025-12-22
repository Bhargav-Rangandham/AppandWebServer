package com.hotel.security;

import org.mindrot.jbcrypt.BCrypt;

public class PasswordUtil {

    private static final int SALT_ROUNDS = 12;

    // Hash password before storing
    public static String hashPassword(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(SALT_ROUNDS));
    }

    // Verify password during login
    public static boolean verifyPassword(String plainPassword, String hashedPassword) {
        return BCrypt.checkpw(plainPassword, hashedPassword);
    }
}
