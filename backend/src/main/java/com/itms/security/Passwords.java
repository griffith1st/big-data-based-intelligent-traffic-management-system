package com.itms.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;

/** BCrypt is shared by initialization, administration and login. */
public final class Passwords {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private Passwords() {}
    public static boolean isHash(String value) {
        return value != null && value.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    }
    public static String encode(String value) { return ENCODER.encode(value); }
    public static boolean matches(String raw, String encoded) {
        return raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 72
                && isHash(encoded) && ENCODER.matches(raw, encoded);
    }
    public static boolean validNewPassword(String value) {
        return value != null && value.length() >= 6 && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
