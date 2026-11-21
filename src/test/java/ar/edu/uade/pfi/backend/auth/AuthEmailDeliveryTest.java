package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** Verification-code delivery: real channel when configured, historical email-demo otherwise. */
class AuthEmailDeliveryTest {
  private final PasswordHasher passwordHasher = new PasswordHasher();
  private final TokenService tokenService =
      new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);

  private static final class RecordingSender implements EmailDeliveryService {
    final List<String[]> sent = new ArrayList<>();
    boolean fail;

    @Override
    public void sendVerificationCode(String to, String purpose, String code, Duration validFor) {
      if (fail) {
        throw new EmailDeliveryException("boom", null);
      }
      sent.add(new String[] {to, purpose, code});
    }
  }

  private AuthService service(RecordingSender sender) {
    AuthService service =
        new AuthService(
            passwordHasher,
            tokenService,
            mock(PostgresAuthStoreService.class),
            false,
            604800,
            new MockEnvironment(),
            false,
            null);
    if (sender != null) {
      service.setEmailDelivery(sender);
    }
    return service;
  }

  private static RegisterRequest request(String email) {
    return new RegisterRequest("Dra. Prueba", email, "Clave-Segura-123!", "MN-1", "Radiologia", "UADE");
  }

  @Test
  void withSmtpRegistrationSendsTheCodeAndNeverReturnsIt() {
    RecordingSender sender = new RecordingSender();
    PendingAuthResponse response = service(sender).register(request("Dra.Prueba@Example.com"));
    assertEquals("email", response.channel());
    assertNull(response.devVerificationCode());
    assertEquals(1, sender.sent.size());
    assertEquals("dra.prueba@example.com", sender.sent.get(0)[0]);
    assertEquals("REGISTER", sender.sent.get(0)[1]);
    assertTrue(sender.sent.get(0)[2].matches("\\d{6}"));
  }

  @Test
  void theEmailedCodeVerifiesTheAccountButAWrongCodeDoesNot() {
    RecordingSender sender = new RecordingSender();
    AuthService service = service(sender);
    PendingAuthResponse response = service.register(request("a@example.com"));
    String code = sender.sent.get(0)[2];
    String wrong = code.equals("000000") ? "000001" : "000000";
    assertThrows(ResponseStatusException.class, () -> service.verify(response.challengeId(), wrong));
    service.verify(response.challengeId(), code);
  }

  @Test
  void aFailedSendRemovesTheChallengeAndReportsServiceUnavailable() {
    RecordingSender sender = new RecordingSender();
    sender.fail = true;
    AuthService service = service(sender);
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.register(request("b@example.com")));
    assertEquals(503, ex.getStatusCode().value());
    assertFalse(ex.getReason().contains("boom"));
  }

  @Test
  void codeRequestsAreThrottledPerAddress() {
    RecordingSender sender = new RecordingSender();
    AuthService service = service(sender);
    service.register(request("c@example.com"));
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.login("c@example.com", "Clave-Segura-123!"));
    assertEquals(429, ex.getStatusCode().value());
    assertEquals(1, sender.sent.size());
  }

  @Test
  void withoutSmtpTheHistoricalEmailDemoChannelIsKeptAndNothingIsSent() {
    PendingAuthResponse response = service(null).register(request("d@example.com"));
    assertEquals("email-demo", response.channel());
    assertNull(response.devVerificationCode());
  }

  @Test
  void smtpServiceRefusesToStartWithoutRequiredSettings() {
    assertThrows(
        IllegalStateException.class,
        () -> new SmtpEmailDeliveryService("", 587, "u", "p", "from@example.com", "MIRA", true, false, 1000));
    assertThrows(
        IllegalStateException.class,
        () -> new SmtpEmailDeliveryService("smtp.example.com", 587, "u", "", "from@example.com", "MIRA", true, false, 1000));
    assertThrows(
        IllegalStateException.class,
        () -> new SmtpEmailDeliveryService("smtp.example.com", 587, "u", "p", "not-an-email", "MIRA", true, false, 1000));
  }

  @Test
  void messageBodyContainsTheCodeAndTheExpiry() {
    String body = SmtpEmailDeliveryService.body("REGISTER", "123456", Duration.ofMinutes(10));
    assertTrue(body.contains("123456"));
    assertTrue(body.contains("10 minutos"));
  }
}
