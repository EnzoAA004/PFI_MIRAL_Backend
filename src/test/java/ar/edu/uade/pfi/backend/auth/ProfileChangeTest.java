package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfileChangeResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Cambio de datos del perfil: solicitud, autorización con código, edición, revisión y solo lectura. */
class ProfileChangeTest {
  private static final String EMAIL = "dra.perfil@example.com";
  private static final String ADMIN_EMAIL = "admin@example.com";
  private static final String PASSWORD = "Clave-Segura-123!";

  private final TokenService tokenService =
      new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);
  private final PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
  private final List<String> codes = new ArrayList<>();
  private final List<String> profileTokens = new ArrayList<>();
  private final List<String> supportNotices = new ArrayList<>();
  private AuthService service;
  private TokenService.Claims doctor;
  private TokenService.Claims admin;

  private AuthService newService(boolean withEmail) {
    AuthService created =
        new AuthService(new PasswordHasher(), tokenService, store, !withEmail, 604800, new MockEnvironment(), false, null);
    if (withEmail) {
      created.setEmailDelivery(
          new EmailDeliveryService() {
            @Override
            public void sendVerificationCode(String toEmail, String purpose, String code, Duration validFor) {
              codes.add(code);
            }

            @Override
            public void sendProfileChangeToken(String toEmail, String token, Duration validFor) {
              profileTokens.add(token);
            }

            @Override
            public void notifyProfileChangeRequested(String doctorName, String doctorEmail, String note) {
              supportNotices.add(doctorEmail);
            }
          });
    }
    return created;
  }

  @BeforeEach
  void registerDoctor() {
    service = newService(true);
    PendingAuthResponse pending =
        service.register(new RegisterRequest("Dra. Perfil", EMAIL, PASSWORD, "MN-1", "Radiologia", "UADE"));
    TokenResponse session = service.verify(pending.challengeId(), codes.get(0));
    doctor = new TokenService.Claims(session.user().id(), EMAIL, "Dra. Perfil", List.of("DOCTOR", "REVIEWER"));
    admin = new TokenService.Claims("admin-1", ADMIN_EMAIL, "Admin", List.of("ADMIN"));
    service.activateProfessional(admin, EMAIL, true);
  }

  private static int httpStatus(Runnable action) {
    return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
  }

  /** Lleva la cuenta hasta la edición habilitada. */
  private void unlock() {
    service.requestProfileChange(doctor, "Error de tipeo en la matrícula");
    service.authorizeProfileChange(admin, EMAIL);
    service.unlockProfileChange(doctor, profileTokens.get(0));
  }

  @Test
  void aNewAccountHasNoPendingChange() {
    assertEquals("NONE", service.currentProfileChange(doctor).status());
  }

  @Test
  void requestingNotifiesSupportAndAppearsForTheAdministrator() {
    ProfileChangeResponse response = service.requestProfileChange(doctor, "Error de tipeo");
    assertEquals("REQUESTED", response.status());
    assertEquals(List.of(EMAIL), supportNotices);
    assertEquals(1, service.listProfileChanges(admin).size());
    assertEquals(409, httpStatus(() -> service.requestProfileChange(doctor, "otra vez")));
  }

  @Test
  void editingIsLockedUntilAnAdministratorAuthorizesAndTheCodeIsEntered() {
    service.requestProfileChange(doctor, "x");
    assertEquals(409, httpStatus(() -> service.submitProfileChange(doctor, "Dra. Nueva", "MN-2", "UADE")));
    assertEquals(409, httpStatus(() -> service.unlockProfileChange(doctor, "ABCDEFGH")));
    service.authorizeProfileChange(admin, EMAIL);
    assertEquals(1, profileTokens.size());
    assertEquals(409, httpStatus(() -> service.submitProfileChange(doctor, "Dra. Nueva", "MN-2", "UADE")));
    assertEquals(403, httpStatus(() -> service.unlockProfileChange(doctor, "AAAAAAAA")));
    assertEquals("UNLOCKED", service.unlockProfileChange(doctor, profileTokens.get(0).toLowerCase()).status());
  }

  @Test
  void onlyAnAdministratorCanAuthorizeOrDecide() {
    service.requestProfileChange(doctor, "x");
    assertEquals(403, httpStatus(() -> service.authorizeProfileChange(doctor, EMAIL)));
    assertEquals(403, httpStatus(() -> service.decideProfileChange(doctor, EMAIL, true)));
    assertEquals(403, httpStatus(() -> service.listProfileChanges(doctor)));
  }

  @Test
  void theCodeIsThrottledAfterFiveWrongAttempts() {
    service.requestProfileChange(doctor, "x");
    service.authorizeProfileChange(admin, EMAIL);
    for (int i = 0; i < 5; i++) assertEquals(403, httpStatus(() -> service.unlockProfileChange(doctor, "ZZZZZZZZ")));
    assertEquals(429, httpStatus(() -> service.unlockProfileChange(doctor, profileTokens.get(0))));
  }

  @Test
  void savingChangesUpdatesTheDataPutsTheAccountInReviewAndKeepsTheEmail() {
    unlock();
    TokenResponse fresh = service.submitProfileChange(doctor, "Dra. Perfil Corregida", "MN-99", "Hospital Nuevo");
    assertEquals("Dra. Perfil Corregida", fresh.user().fullName());
    assertEquals("MN-99", fresh.user().licenseNumber());
    assertEquals("Hospital Nuevo", fresh.user().institution());
    assertEquals(EMAIL, fresh.user().email());
    assertTrue(fresh.user().roles().contains("READ_ONLY"));
    verify(store).updateProfileFields(EMAIL, "Dra. Perfil Corregida", "MN-99", "Hospital Nuevo");
    assertEquals("PENDING_REVIEW", service.currentProfileChange(doctor).status());
    assertEquals("MN-1", service.currentProfileChange(doctor).previousLicenseNumber());
  }

  @Test
  void aSubmissionWithoutRealChangesOrWithInvalidDataIsRejected() {
    unlock();
    assertEquals(400, httpStatus(() -> service.submitProfileChange(doctor, "Dra. Perfil", "MN-1", "UADE")));
    assertEquals(400, httpStatus(() -> service.submitProfileChange(doctor, "  ", "MN-1", "UADE")));
    assertEquals(400, httpStatus(() -> service.submitProfileChange(doctor, "x".repeat(121), "MN-1", "UADE")));
    assertEquals("UNLOCKED", service.currentProfileChange(doctor).status());
  }

  @Test
  void approvingKeepsTheNewDataAndRestoresFullAccess() {
    unlock();
    service.submitProfileChange(doctor, "Dra. Perfil Corregida", "MN-99", "UADE");
    service.decideProfileChange(admin, EMAIL, true);
    assertEquals("NONE", service.currentProfileChange(doctor).status());
    Object session = service.login(EMAIL, PASSWORD);
    assertTrue(session instanceof TokenResponse);
    TokenResponse tokens = (TokenResponse) session;
    assertFalse(tokens.user().roles().contains("READ_ONLY"));
    assertEquals("MN-99", tokens.user().licenseNumber());
  }

  @Test
  void rejectingRevertsTheDataToWhatWasApproved() {
    unlock();
    service.submitProfileChange(doctor, "Otro Nombre", "MN-77", "Otra Institucion");
    service.decideProfileChange(admin, EMAIL, false);
    verify(store).updateProfileFields(EMAIL, "Dra. Perfil", "MN-1", "UADE");
    TokenResponse tokens = (TokenResponse) service.login(EMAIL, PASSWORD);
    assertEquals("Dra. Perfil", tokens.user().fullName());
    assertEquals("MN-1", tokens.user().licenseNumber());
    assertEquals("UADE", tokens.user().institution());
    assertFalse(tokens.user().roles().contains("READ_ONLY"));
  }

  @Test
  void anAdministratorCanRejectARequestThatHasNoChangesYetButCannotApproveIt() {
    service.requestProfileChange(doctor, "x");
    assertEquals(409, httpStatus(() -> service.decideProfileChange(admin, EMAIL, true)));
    service.decideProfileChange(admin, EMAIL, false);
    assertEquals("NONE", service.currentProfileChange(doctor).status());
  }

  @Test
  void theProfessionalCanCancelBeforeSavingButNotAfter() {
    service.requestProfileChange(doctor, "x");
    assertEquals("NONE", service.cancelProfileChange(doctor).status());
    unlock2();
    service.submitProfileChange(doctor, "Dra. Perfil Corregida", "MN-1", "UADE");
    assertEquals(409, httpStatus(() -> service.cancelProfileChange(doctor)));
  }

  private void unlock2() {
    profileTokens.clear();
    unlock();
  }

  @Test
  void withoutEmailDeliveryTheAdministratorAuthorizationUnlocksEditingDirectly() {
    AuthService local = newService(false);
    PendingAuthResponse pending =
        local.register(new RegisterRequest("Dr. Local", "local@example.com", PASSWORD, "MN-5", "Clinica", "UADE"));
    TokenResponse session = local.verify(pending.challengeId(), pending.devVerificationCode());
    local.activateProfessional(admin, "local@example.com", true);
    TokenService.Claims claims = new TokenService.Claims(session.user().id(), "local@example.com", "Dr. Local", List.of("DOCTOR"));
    local.requestProfileChange(claims, "x");
    assertEquals("UNLOCKED", local.authorizeProfileChange(admin, "local@example.com").status());
    TokenResponse fresh = local.submitProfileChange(claims, "Dr. Local Corregido", "MN-5", "Clinica");
    assertTrue(fresh.user().roles().contains("READ_ONLY"));
  }

  @Test
  void aFailedMailLeavesTheRequestWaitingForAuthorization() {
    AuthService failing = newService(true);
    failing.setEmailDelivery(
        new EmailDeliveryService() {
          @Override
          public void sendVerificationCode(String toEmail, String purpose, String code, Duration validFor) {
            codes.add(code);
          }

          @Override
          public void sendProfileChangeToken(String toEmail, String token, Duration validFor) {
            throw new EmailDeliveryException("down", null);
          }
        });
    PendingAuthResponse pending =
        failing.register(new RegisterRequest("Dr. Falla", "falla@example.com", PASSWORD, "MN-6", "Clinica", "UADE"));
    failing.verify(pending.challengeId(), codes.get(codes.size() - 1));
    failing.activateProfessional(admin, "falla@example.com", true);
    TokenService.Claims claims = new TokenService.Claims("id-f", "falla@example.com", "Dr. Falla", List.of("DOCTOR"));
    failing.requestProfileChange(claims, "x");
    assertEquals(503, httpStatus(() -> failing.authorizeProfileChange(admin, "falla@example.com")));
    assertEquals("REQUESTED", failing.currentProfileChange(claims).status());
  }

  @Test
  void aFailedVerificationMailDoesNotLeaveAHalfRegisteredAccount() {
    AuthService failing = newService(true);
    failing.setEmailDelivery(
        (to, purpose, code, validFor) -> {
          throw new EmailDeliveryService.EmailDeliveryException("down", null);
        });
    RegisterRequest request = new RegisterRequest("Dr. Typo", "typo@example.com", PASSWORD, "MN-7", "Clinica", "UADE");
    assertEquals(503, httpStatus(() -> failing.register(request)));
    verify(store).deleteUnverifiedAccount("typo@example.com");
    assertEquals(503, httpStatus(() -> failing.register(request))); // se puede reintentar: no responde "ya existe" (409)
  }

  // ---- Modo solo lectura en el filtro ----------------------------------------------------------

  @RestController
  static class Probe {
    @GetMapping("/api/probe")
    String read() {
      return "ok";
    }

    @PostMapping("/api/probe")
    String write() {
      return "written";
    }

    @PostMapping("/api/auth/probe")
    String authWrite() {
      return "auth-ok";
    }
  }

  private MockMvc filterMvc(ProfileChangeRegistry registry) {
    AuthAccountStateService state = new AuthAccountStateService(store, new MockEnvironment());
    state.setProfileChanges(registry);
    AuthFilter filter = new AuthFilter(tokenService, state, true, false, new MockEnvironment());
    return MockMvcBuilders.standaloneSetup(new Probe()).addFilter(filter).build();
  }

  @Test
  void anAccountWithChangesUnderReviewCanReadButNotWrite() throws Exception {
    ProfileChangeRegistry registry = new ProfileChangeRegistry(store);
    MockMvc mvc = filterMvc(registry);
    String token =
        tokenService.issueAccessToken(
            new DoctorAccount("u1", "Dra", EMAIL, "h", "", "", "", List.of("DOCTOR", "REVIEWER"), java.time.Instant.now(), true, true, false, true));
    mvc.perform(post("/api/probe").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

    java.time.Instant now = java.time.Instant.now();
    registry.save(new ProfileChange(EMAIL, ProfileChange.Status.PENDING_REVIEW, "", null, null, "A", "B", "C", now, now));

    mvc.perform(get("/api/probe").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    mvc.perform(post("/api/probe").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PROFILE_REVIEW_PENDING"));
    mvc.perform(post("/api/auth/probe").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

    registry.remove(EMAIL); // el administrador aprobó: efecto inmediato, sin renovar el token
    mvc.perform(post("/api/probe").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
  }
}
