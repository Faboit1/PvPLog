package top.cheesesmp.duelcore.party;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Party passwords are stored as a salted PBKDF2-HMAC-SHA256 hash, never as plain text:
 * {@code p1$<salt>$<hash>} (base64, 52 characters, fits dc_parties.password VARCHAR(64)). Hashing takes a few
 * milliseconds on purpose, so callers run it off the main thread.
 */
public final class PartyPasswords {

    public static final int MAX_LENGTH = 32;

    private static final String PREFIX = "p1$";
    private static final int ITERATIONS = 20_000;
    private static final int SALT_BYTES = 12;
    private static final int HASH_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();

    private PartyPasswords() {
    }

    /** 1–32 visible characters, no spaces. */
    public static boolean valid(@Nullable String password) {
        if (password == null || password.isEmpty() || password.length() > MAX_LENGTH) return false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return PREFIX + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(derive(password, salt));
    }

    /** True when {@code password} matches a hash made by {@link #hash}; false for anything malformed. */
    public static boolean verify(String password, @Nullable String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return false;
        String[] parts = stored.substring(PREFIX.length()).split("\\$");
        if (parts.length != 2) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            return MessageDigest.isEqual(expected, derive(password, salt));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }
}
