package ar.edu.uade.pfi.backend.auth;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
  /** Contraseñas nuevas: 600 000 vueltas (recomendación OWASP para PBKDF2-HMAC-SHA256). */
  private static final int ITERATIONS = 600_000;
  /** Las cuentas anteriores guardan su cantidad de vueltas en el propio hash; si falta, eran 120 000. */
  private static final int LEGACY_ITERATIONS = 120_000;
  private static final int KEY_LENGTH = 256;
  private final SecureRandom random = new SecureRandom();

  public String hash(String password) {
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    byte[] derived = derive(password, salt, ITERATIONS);
    return ITERATIONS
        + ":"
        + Base64.getEncoder().encodeToString(salt)
        + ":"
        + Base64.getEncoder().encodeToString(derived);
  }

  public boolean verify(String password, String encoded) {
    String[] parts = encoded.split(":");
    if (parts.length != 3) {
      return false;
    }
    int iterations;
    try {
      iterations = Integer.parseInt(parts[0]);
    } catch (NumberFormatException ex) {
      iterations = LEGACY_ITERATIONS;
    }
    if (iterations < 1 || iterations > 5_000_000) {
      return false;
    }
    byte[] salt = Base64.getDecoder().decode(parts[1]);
    byte[] expected = Base64.getDecoder().decode(parts[2]);
    byte[] actual = derive(password, salt, iterations);
    if (expected.length != actual.length) {
      return false;
    }
    int diff = 0;
    for (int i = 0; i < expected.length; i++) {
      diff |= expected[i] ^ actual[i];
    }
    return diff == 0;
  }

  private byte[] derive(String password, byte[] salt, int iterations) {
    try {
      PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
      throw new IllegalStateException("Could not hash password", ex);
    }
  }
}
