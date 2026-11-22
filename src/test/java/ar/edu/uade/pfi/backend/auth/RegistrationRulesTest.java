package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** Reglas de contraseña y correo al registrarse, y rechazo de un registro con motivo. */
class RegistrationRulesTest {
  private static final String GOOD = "Clave-Segura-123!";

  private final TokenService tokenService =
      new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);
  private final List<String[]> rejectionMails = new ArrayList<>();
  private boolean mailFails;
  private AuthService service;
  private final TokenService.Claims admin = new TokenService.Claims("admin-id", "admin@example.com", "Admin", List.of("ADMIN"));

  @BeforeEach
  void setUp() {
    service =
        new AuthService(new PasswordHasher(), tokenService, mock(PostgresAuthStoreService.class), false, 604800, new MockEnvironment(), false, null);
    service.setEmailDelivery(
        new EmailDeliveryService() {
          @Override
          public void sendVerificationCode(String toEmail, String purpose, String code, java.time.Duration validFor) {}

          @Override
          public void sendRegistrationRejected(String toEmail, String fullName, String reason) {
            if (mailFails) throw new EmailDeliveryException("boom", null);
            rejectionMails.add(new String[] {toEmail, reason});
          }
        });
  }

  private static int status(Runnable action) {
    return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
  }

  private RegisterRequest request(String email, String password) {
    return new RegisterRequest("Dra. Regla", email, password, "MN-1", "Radiologia", "UADE");
  }

  @Test
  void thePolicyRequiresLengthSpecialCharacterLettersDigitsAndNoLeadingNumber() {
    assertNull(PasswordPolicy.problem(GOOD, "a@example.com"));
    assertNotNull(PasswordPolicy.problem("Ab1!", "a@example.com"));
    assertTrue(PasswordPolicy.problem("1Clave-Segura!", "a@example.com").contains("empezar con un número"));
    assertTrue(PasswordPolicy.problem("ClaveSegura123", "a@example.com").contains("carácter especial"));
    assertTrue(PasswordPolicy.problem("Clave-Segura-!", "a@example.com").contains("letras y números"));
    assertTrue(PasswordPolicy.problem("1234-5678!", "a@example.com").contains("empezar con un número"));
  }

  @Test
  void anyWellFormedEmailIsAcceptedAndMalformedOnesAreNot() {
    for (String ok : List.of("a@gmail.com", "dra.perez@hospital.org.ar", "x+y@freny.com.ar", "a_b@uade.edu.ar")) {
      assertTrue(PasswordPolicy.validEmail(ok), ok);
    }
    for (String bad : List.of("sin-arroba", "a@b", "@gmail.com", "a@@gmail.com", "a b@gmail.com", "a@gmail.", "a@gmail.c")) {
      assertFalse(PasswordPolicy.validEmail(bad), bad);
    }
  }

  @Test
  void registrationRejectsAWeakPasswordOrABadEmailBeforeCreatingAnything() {
    assertEquals(400, status(() -> service.register(request("ok@example.com", "corta1"))));
    assertEquals(400, status(() -> service.register(request("ok@example.com", "1Clave-Segura!"))));
    assertEquals(400, status(() -> service.register(request("no-es-correo", GOOD))));
    assertNotNull(service.register(request("ok@example.com", GOOD)));
  }

  @Test
  void anAdminRejectsAPendingRegistrationWithAReasonThatIsStoredAndEmailed() {
    service.register(request("pend@example.com", GOOD));
    AuthService.RejectionResult result = service.rejectRegistration(admin, "pend@example.com", "La matrícula no coincide con el registro.");
    assertTrue(result.emailSent());
    assertEquals("pend@example.com", rejectionMails.get(0)[0]);
    assertTrue(rejectionMails.get(0)[1].contains("matrícula"));
    TokenService.Claims doctor = new TokenService.Claims("d", "pend@example.com", "Dra. Regla", List.of("PENDING_APPROVAL"));
    assertEquals("La matrícula no coincide con el registro.", service.registrationRejectionFor(doctor).orElseThrow().reason());
    assertEquals(1, service.listRegistrationRejections(admin).size());
  }

  @Test
  void anUnverifiedRejectedAccountSeesTheReasonWhenTryingToSignIn() {
    service.register(request("pend4@example.com", GOOD));
    service.rejectRegistration(admin, "pend4@example.com", "La matrícula no coincide con el registro.");
    Object response = service.login("pend4@example.com", GOOD);
    assertTrue(response instanceof ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse);
    var pending = (ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse) response;
    assertEquals("", pending.challengeId());
    assertTrue(pending.message().contains("matrícula"));
  }

  @Test
  void aFailedEmailStillStoresTheReasonAndSaysSo() {
    service.register(request("pend2@example.com", GOOD));
    mailFails = true;
    AuthService.RejectionResult result = service.rejectRegistration(admin, "pend2@example.com", "Falta la especialidad declarada.");
    assertFalse(result.emailSent());
    assertTrue(service.registrationRejectionFor(new TokenService.Claims("d", "pend2@example.com", "x", List.of())).isPresent());
  }

  @Test
  void theReasonIsRequiredOnlyAdminsCanRejectAndApprovingClearsTheRejection() {
    service.register(request("pend3@example.com", GOOD));
    assertEquals(400, status(() -> service.rejectRegistration(admin, "pend3@example.com", "corto")));
    TokenService.Claims notAdmin = new TokenService.Claims("d", "x@example.com", "x", List.of("DOCTOR"));
    assertEquals(403, status(() -> service.rejectRegistration(notAdmin, "pend3@example.com", "Motivo suficientemente largo.")));
    assertEquals(404, status(() -> service.rejectRegistration(admin, "nadie@example.com", "Motivo suficientemente largo.")));
    service.rejectRegistration(admin, "pend3@example.com", "Motivo suficientemente largo.");
    service.activateProfessional(admin, "pend3@example.com", true);
    assertTrue(service.registrationRejectionFor(new TokenService.Claims("d", "pend3@example.com", "x", List.of())).isEmpty());
    assertEquals(409, status(() -> service.rejectRegistration(admin, "pend3@example.com", "Motivo suficientemente largo.")));
  }
}
