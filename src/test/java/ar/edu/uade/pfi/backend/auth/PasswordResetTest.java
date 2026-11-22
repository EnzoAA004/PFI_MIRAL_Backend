package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** «Olvidé mi contraseña»: código, política, intentos, sesiones y que no se filtre quién existe. */
class PasswordResetTest {
  private static final String EMAIL = "dra.olvido@example.com";
  private static final String OLD = "Clave-Segura-123!";
  private static final String NEW = "Nueva-Clave-2026";

  private final TokenService tokenService =
      new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);
  private final PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
  private final List<String[]> sent = new ArrayList<>();
  private AuthService service;

  @BeforeEach
  void registerAndVerify() {
    service =
        new AuthService(new PasswordHasher(), tokenService, store, false, 604800, new MockEnvironment(), false, null);
    service.setEmailDelivery((to, purpose, code, validFor) -> sent.add(new String[] {to, purpose, code}));
    PendingAuthResponse pending =
        service.register(new RegisterRequest("Dra. Olvido", EMAIL, OLD, "MN-1", "Radiologia", "UADE"));
    service.verify(pending.challengeId(), sent.get(0)[2]);
    sent.clear();
    clearSendThrottle();
  }

  /** El registro acaba de gastar el cupo de «un código cada 30 s»; la prueba arranca con el cupo limpio. */
  private void clearSendThrottle() {
    try {
      java.lang.reflect.Field field = AuthService.class.getDeclaredField("codeSends");
      field.setAccessible(true);
      ((java.util.Map<?, ?>) field.get(service)).clear();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int status(Runnable action) {
    return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
  }

  /** Pasa el límite de «un código cada 30 segundos» entre pedidos de una misma prueba. */
  private PendingAuthResponse request() {
    return service.requestPasswordReset(EMAIL);
  }

  @Test
  void theCodeIsEmailedNeverReturnedAndTheNewPasswordWorks() {
    PendingAuthResponse response = request();
    assertNull(response.devVerificationCode());
    assertEquals(1, sent.size());
    assertEquals("RESET", sent.get(0)[1]);
    service.resetPassword(response.challengeId(), sent.get(0)[2], NEW);
    assertEquals(401, status(() -> service.login(EMAIL, OLD)));
    assertTrue(service.login(EMAIL, NEW) instanceof TokenResponse);
    verify(store).revokeRefreshTokensForEmail(EMAIL);
  }

  @Test
  void anUnknownEmailGetsTheSameAnswerAndNothingIsSent() {
    PendingAuthResponse known = request();
    sent.clear();
    PendingAuthResponse unknown = service.requestPasswordReset("nadie@example.com");
    assertEquals(known.message(), unknown.message());
    assertEquals(known.channel(), unknown.channel());
    assertTrue(sent.isEmpty());
    assertEquals(401, status(() -> service.resetPassword(unknown.challengeId(), "123456", NEW)));
  }

  @Test
  void aResetCodeCanNeverOpenASession() {
    PendingAuthResponse response = request();
    assertEquals(401, status(() -> service.verify(response.challengeId(), sent.get(0)[2])));
  }

  @Test
  void aWrongCodeIsRejectedAndAfterFiveAttemptsTheCodeIsBurned() {
    PendingAuthResponse response = request();
    String code = sent.get(0)[2];
    String wrong = code.equals("000000") ? "000001" : "000000";
    for (int i = 0; i < AuthService.MAX_RESET_CODE_ATTEMPTS; i++) {
      assertEquals(401, status(() -> service.resetPassword(response.challengeId(), wrong, NEW)));
    }
    assertEquals(429, status(() -> service.resetPassword(response.challengeId(), code, NEW)));
    assertEquals(401, status(() -> service.resetPassword(response.challengeId(), code, NEW)));
    assertTrue(service.login(EMAIL, OLD) instanceof TokenResponse);
  }

  @Test
  void theNewPasswordMustMeetThePolicyAndTheCodeSurvivesTheMistake() {
    PendingAuthResponse response = request();
    String code = sent.get(0)[2];
    assertEquals(400, status(() -> service.resetPassword(response.challengeId(), code, "corta1")));
    assertEquals(400, status(() -> service.resetPassword(response.challengeId(), code, "soloLetrasLargas")));
    service.resetPassword(response.challengeId(), code, NEW);
    assertTrue(service.login(EMAIL, NEW) instanceof TokenResponse);
  }

  @Test
  void theCodeWorksOnlyOnce() {
    PendingAuthResponse response = request();
    String code = sent.get(0)[2];
    service.resetPassword(response.challengeId(), code, NEW);
    assertEquals(401, status(() -> service.resetPassword(response.challengeId(), code, "Otra-Clave-2027")));
  }

  @Test
  void theDemoAccountCannotBeReset() {
    PendingAuthResponse response = service.requestPasswordReset(AuthService.DEMO_ACCOUNT_EMAIL);
    assertTrue(sent.isEmpty());
    assertEquals(401, status(() -> service.resetPassword(response.challengeId(), "123456", NEW)));
  }
}
