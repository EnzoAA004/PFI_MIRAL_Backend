package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

/** Self-service password change: policy, throttling, session revocation and persistence. */
class PasswordChangeTest {
  private static final String EMAIL = "dra.clave@example.com";
  private static final String OLD = "Clave-Segura-123!";

  private final PasswordHasher passwordHasher = new PasswordHasher();
  private final TokenService tokenService =
      new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);
  private final PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
  private final List<String> codes = new ArrayList<>();
  private AuthService service;
  private TokenResponse session;
  private TokenService.Claims claims;

  @BeforeEach
  void registerAndVerify() {
    service =
        new AuthService(
            passwordHasher, tokenService, store, false, 604800, new MockEnvironment(), false, null);
    service.setEmailDelivery(
        (to, purpose, code, validFor) -> codes.add(code));
    PendingAuthResponse pending =
        service.register(new RegisterRequest("Dra. Clave", EMAIL, OLD, "MN-1", "Radiologia", "UADE"));
    session = service.verify(pending.challengeId(), codes.get(0));
    claims = new TokenService.Claims(session.user().id(), EMAIL, "Dra. Clave", List.of("REVIEWER"));
  }

  private static int status(Runnable action) {
    return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
  }

  @Test
  void changesThePasswordPersistsItAndKeepsTheCallerSignedIn() {
    TokenResponse fresh = service.changePassword(claims, OLD, "Nueva-Clave-2026");
    assertNotEquals(session.refreshToken(), fresh.refreshToken());
    verify(store).updatePasswordHash(org.mockito.ArgumentMatchers.eq(EMAIL), org.mockito.ArgumentMatchers.anyString());
    assertEquals(401, status(() -> service.login(EMAIL, OLD)));
    assertTrue(service.login(EMAIL, "Nueva-Clave-2026") instanceof TokenResponse);
  }

  @Test
  void everyRefreshTokenOfTheAccountIsRevokedAndAFreshOneIsIssued() {
    TokenResponse fresh = service.changePassword(claims, OLD, "Nueva-Clave-2026");
    verify(store).revokeRefreshTokensForEmail(EMAIL);
    verify(store).saveRefreshToken(
        org.mockito.ArgumentMatchers.eq(fresh.refreshToken()),
        org.mockito.ArgumentMatchers.eq(EMAIL),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void aWrongCurrentPasswordIsRejectedAndNothingChanges() {
    assertEquals(403, status(() -> service.changePassword(claims, "otra-clave-9999", "Nueva-Clave-2026")));
    assertTrue(service.login(EMAIL, OLD) instanceof TokenResponse);
  }

  @Test
  void theNewPasswordMustMeetThePolicy() {
    assertEquals(400, status(() -> service.changePassword(claims, OLD, "corta1")));
    assertEquals(400, status(() -> service.changePassword(claims, OLD, "soloLetrasLargas")));
    assertEquals(400, status(() -> service.changePassword(claims, OLD, "12345678901234")));
    assertEquals(400, status(() -> service.changePassword(claims, OLD, OLD)));
    assertEquals(400, status(() -> service.changePassword(claims, OLD, EMAIL.toUpperCase())));
  }

  @Test
  void wrongCurrentPasswordAttemptsAreThrottled() {
    for (int i = 0; i < 5; i++) {
      assertEquals(403, status(() -> service.changePassword(claims, "mala-clave-000", "Nueva-Clave-2026")));
    }
    assertEquals(429, status(() -> service.changePassword(claims, OLD, "Nueva-Clave-2026")));
  }

  @Test
  void theDemoAccountPasswordCannotBeChanged() {
    TokenService.Claims demo =
        new TokenService.Claims("demo", AuthService.DEMO_ACCOUNT_EMAIL, "Demo", List.of("REVIEWER"));
    assertEquals(403, status(() -> service.changePassword(demo, "Demo1234!", "Nueva-Clave-2026")));
  }

  @Test
  void policyMessagesNameTheProblem() {
    assertTrue(AuthService.newPasswordProblem("corta1", OLD, EMAIL).contains("10 caracteres"));
    assertEquals(null, AuthService.newPasswordProblem("Nueva-Clave-2026", OLD, EMAIL));
  }
}
