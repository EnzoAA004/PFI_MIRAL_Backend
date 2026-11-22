package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** Límite de intentos de ingreso y compatibilidad de los hashes de contraseña anteriores. */
class LoginHardeningTest {
  private static final String EMAIL = "dra.limite@example.com";
  private static final String PASS = "Clave-Segura-123!";

  private final PasswordHasher hasher = new PasswordHasher();
  private final TokenService tokenService =
      new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);

  private AuthService verifiedService() {
    AuthService service =
        new AuthService(hasher, tokenService, mock(PostgresAuthStoreService.class), true, 604800, new MockEnvironment(), false, null);
    PendingAuthResponse pending = service.register(new RegisterRequest("Dra. Limite", EMAIL, PASS, "MN-1", "Radiologia", "UADE"));
    service.verify(pending.challengeId(), pending.devVerificationCode());
    return service;
  }

  private static int status(Runnable action) {
    return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
  }

  @Test
  void afterFiveWrongPasswordsTheAccountIsLockedForAWhileEvenWithTheRightOne() {
    AuthService service = verifiedService();
    for (int i = 0; i < AuthService.MAX_LOGIN_FAILURES; i++) {
      assertEquals(401, status(() -> service.login(EMAIL, "incorrecta-" + System.nanoTime())));
    }
    assertEquals(429, status(() -> service.login(EMAIL, PASS)));
  }

  @Test
  void aSuccessfulLoginResetsTheCounter() {
    AuthService service = verifiedService();
    for (int round = 0; round < 3; round++) {
      for (int i = 0; i < AuthService.MAX_LOGIN_FAILURES - 1; i++) {
        assertEquals(401, status(() -> service.login(EMAIL, "incorrecta")));
      }
      assertTrue(service.login(EMAIL, PASS) instanceof TokenResponse);
    }
  }

  @Test
  void newHashesUseMoreRoundsAndHashesFromBeforeStillVerify() throws Exception {
    assertTrue(hasher.hash("Clave-Segura-123!").startsWith("600000:"));
    assertTrue(hasher.verify("Clave-Segura-123!", hasher.hash("Clave-Segura-123!")));
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    byte[] derived =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(new PBEKeySpec("Vieja-Clave-1!".toCharArray(), salt, 120_000, 256))
            .getEncoded();
    String legacy = "120000:" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derived);
    assertTrue(hasher.verify("Vieja-Clave-1!", legacy));
    assertEquals(false, hasher.verify("otra", legacy));
  }
}
