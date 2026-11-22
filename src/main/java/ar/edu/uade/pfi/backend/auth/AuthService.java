package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfileChangeResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.AdminProfileChangeItem;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfessionalActivationResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.SettingsRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.UserResponse;
import ar.edu.uade.pfi.backend.auth.exception.AdminAccountProtectedException;
import ar.edu.uade.pfi.backend.auth.exception.LastAdminProtectionException;
import ar.edu.uade.pfi.backend.service.AuditAction;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  /**
   * El email único y conocido de la cuenta demo. El bloqueo debe basarse en esta identidad, no en los roles,
   * porque una cuenta demo sembrada previamente puede estar persistida con ADMIN en Postgres incluso
   * después de apagar el modo demo.
   */
  public static final String DEMO_ACCOUNT_EMAIL = "doctor.demo@pfi.local";

  private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
  private final Map<String, DoctorAccount> accountsByEmail = new ConcurrentHashMap<>();
  private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
  private final Map<String, Integer> resetAttempts = new ConcurrentHashMap<>();
  private final Map<String, RegistrationRejection> rejections = new ConcurrentHashMap<>();
  static final int MAX_RESET_CODE_ATTEMPTS = 5;
  private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
  private final PasswordHasher passwordHasher;
  private final TokenService tokenService;
  private final PostgresAuthStoreService postgresAuthStore;
  private final boolean exposeCodes;
  private final long refreshTokenSeconds;
  private final SecureRandom random = new SecureRandom();
  private final Environment environment;
  private final boolean demoEnabled;
  private final AuditService auditService;
  private final Map<String, java.util.ArrayDeque<Instant>> codeSends = new ConcurrentHashMap<>();
  private EmailDeliveryService emailDelivery;
  private ProfileChangeRegistry profileChanges;
  private final Map<String, java.util.ArrayDeque<Instant>> profileTokenFailures = new ConcurrentHashMap<>();
  private final Map<String, java.util.ArrayDeque<Instant>> loginFailures = new ConcurrentHashMap<>();
  static final int MAX_LOGIN_FAILURES = 5;
  private final Map<String, java.util.ArrayDeque<Instant>> passwordFailures = new ConcurrentHashMap<>();

  public AuthService(
      PasswordHasher passwordHasher,
      TokenService tokenService,
      PostgresAuthStoreService postgresAuthStore,
      @Value("${pfi.auth.expose-dev-codes:false}") boolean exposeCodes,
      @Value("${pfi.auth.refresh-token-seconds:604800}") long refreshTokenSeconds) {
    this(
        passwordHasher,
        tokenService,
        postgresAuthStore,
        exposeCodes,
        refreshTokenSeconds,
        null,
        false,
        null);
  }

  @Autowired
  public AuthService(
      PasswordHasher passwordHasher,
      TokenService tokenService,
      PostgresAuthStoreService postgresAuthStore,
      @Value("${pfi.auth.expose-dev-codes:false}") boolean exposeCodes,
      @Value("${pfi.auth.refresh-token-seconds:604800}") long refreshTokenSeconds,
      Environment environment,
      @Value("${pfi.auth.demo-enabled:false}") boolean demoEnabled,
      AuditService auditService) {
    this.passwordHasher = passwordHasher;
    this.tokenService = tokenService;
    this.postgresAuthStore = postgresAuthStore;
    this.exposeCodes = exposeCodes;
    this.refreshTokenSeconds = refreshTokenSeconds;
    this.environment = environment;
    this.demoEnabled = demoEnabled;
    this.auditService = auditService;
    this.profileChanges = new ProfileChangeRegistry(postgresAuthStore);
  }

  /** El registro compartido con AuthAccountStateService (en la aplicación lo inyecta Spring). */
  @Autowired(required = false)
  void setProfileChanges(ProfileChangeRegistry profileChanges) {
    this.profileChanges = profileChanges;
  }

  /** Opcional: presente solo cuando {@code pfi.auth.email-delivery=smtp}. */
  @Autowired(required = false)
  void setEmailDelivery(EmailDeliveryService emailDelivery) {
    this.emailDelivery = emailDelivery;
  }

  /**
   * Si el modo demo no está efectivamente habilitado al arrancar, revoca de forma proactiva cualquier refresh
   * token remanente de la cuenta demo de un despliegue/rollout anterior — de lo contrario un
   * refresh token todavía válido emitido antes de este cambio podría seguir renovando access tokens indefinidamente aunque
   * el endpoint de seed ya esté cerrado.
   */
  @PostConstruct
  void revokeDemoSessionsIfDemoDisabled() {
    if (!isDemoEffectivelyEnabled()) {
      postgresAuthStore.revokeRefreshTokensForEmail(DEMO_ACCOUNT_EMAIL);
      refreshTokens.values().removeIf(DEMO_ACCOUNT_EMAIL::equals);
    }
  }

  public PendingAuthResponse register(RegisterRequest request) {
    String email = normalizeEmail(request.email());
    if (!PasswordPolicy.validEmail(email)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "El correo no tiene un formato válido (usuario@dominio.com).");
    }
    String passwordProblem = PasswordPolicy.problem(request.password(), email);
    if (passwordProblem != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, passwordProblem);
    }
    if (findAccount(email).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Ya existe una cuenta registrada con ese email");
    }
    DoctorAccount account =
        new DoctorAccount(
            UUID.randomUUID().toString(),
            request.fullName().trim(),
            email,
            passwordHasher.hash(request.password()),
            defaultString(request.licenseNumber()),
            defaultString(request.specialty()),
            defaultString(request.institution()),
            List.of("PENDING_APPROVAL"),
            Instant.now(),
            false,
            false,
            false,
            false);
    accountsByEmail.put(email, account);
    postgresAuthStore.saveAccount(account);
    try {
      return createChallenge(
          "REGISTER",
          email,
          "Código enviado para verificar el email profesional. Luego la cuenta queda pendiente de aprobación institucional.");
    } catch (ResponseStatusException ex) {
      // Si el correo no pudo salir, la cuenta no queda a medias: se puede volver a registrar con el mismo correo.
      if (ex.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
        accountsByEmail.remove(email);
        postgresAuthStore.deleteUnverifiedAccount(email);
      }
      throw ex;
    }
  }

  public Object login(String emailValue, String password) {
    String email = normalizeEmail(emailValue);
    if (isDemoAccountBlocked(email)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
    }
    java.util.ArrayDeque<Instant> failures =
        loginFailures.computeIfAbsent(email, k -> new java.util.ArrayDeque<>());
    synchronized (failures) {
      Instant now = Instant.now();
      while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(Duration.ofMinutes(15)))) {
        failures.pollFirst();
      }
      if (failures.size() >= MAX_LOGIN_FAILURES) {
        throw new ResponseStatusException(
            HttpStatus.TOO_MANY_REQUESTS,
            "Demasiados intentos de ingreso. Esperá unos minutos antes de volver a probar.");
      }
    }
    DoctorAccount account = findAccount(email).orElse(null);
    if (account == null || !passwordHasher.verify(password, account.passwordHash())) {
      synchronized (failures) {
        failures.addLast(Instant.now());
      }
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
    }
    synchronized (failures) {
      failures.clear();
    }
    if (!account.verified()) {
      return createChallenge(
          "REGISTER", email, "La cuenta todavía requiere verificación del email profesional.");
    }
    if (!account.approved()) {
      return issueTokens(account);
    }
    if (account.twoFactorEnabled()) {
      return createChallenge(
          "LOGIN", email, "Código de doble verificación enviado para iniciar sesión.");
    }
    return issueTokens(account);
  }

  public TokenResponse verify(String challengeId, String code) {
    Challenge challenge = challenges.get(challengeId);
    if (challenge == null || challenge.expiresAt().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código expirado o inválido");
    }
    if ("RESET".equals(challenge.type())) {
      // Un codigo de restablecimiento solo sirve en /reset-password, nunca para abrir sesion.
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código expirado o inválido");
    }
    if (!challenge.code().equals(code.trim())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código inválido");
    }
    if (isDemoAccountBlocked(challenge.email())) {
      challenges.remove(challengeId);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código expirado o inválido");
    }
    DoctorAccount account = findAccount(challenge.email()).orElse(null);
    if (account == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no encontrada");
    }
    if ("REGISTER".equals(challenge.type())) {
      account.verify();
      accountsByEmail.put(account.email(), account);
      postgresAuthStore.markVerified(account.email());
    }
    challenges.remove(challengeId);
    return issueTokens(account);
  }

  public TokenResponse refresh(String refreshToken) {
    String email =
        postgresAuthStore
            .findEmailByRefreshToken(refreshToken)
            .orElseGet(() -> refreshTokens.get(refreshToken));
    if (email == null) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Refresh token inválido o revocado");
    }
    if (isDemoAccountBlocked(email)) {
      revokeRefreshToken(refreshToken);
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Refresh token inválido o revocado");
    }
    DoctorAccount account = findAccount(email).orElse(null);
    if (account == null || !account.verified() || !account.approved()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible");
    }
    revokeRefreshToken(refreshToken);
    return issueTokens(account);
  }

  public void logout(String refreshToken) {
    revokeRefreshToken(refreshToken);
  }

  /**
   * P10-B.2: informa {@code claims.roles()} — los roles *efectivos* del llamante para este request
   * puntual, ya resueltos por AuthFilter/AuthAccountStateService — no {@code
   * account.roles()} directamente. Los roles ya no se borran al desactivar (se preservan en
   * el almacenamiento para la reactivación), así que leer el valor crudo persistido acá mostraría incorrectamente en su propio `/me`
   * los roles profesionales anteriores de una cuenta desactivada en vez del estado PENDING_APPROVAL
   * al que realmente está restringida.
   */

  /**
   * Cambio de contraseña autoservicio. Requiere la contraseña actual, aplica una política mínima, revoca
   * todos los refresh tokens de la cuenta (se cierra sesión en otros dispositivos) y devuelve una sesión nueva
   * para que el llamante siga con la sesión iniciada. Máximo 5 contraseñas actuales erróneas cada 15 minutos por cuenta.
   */
  public TokenResponse changePassword(
      TokenService.Claims claims, String currentPassword, String newPassword) {
    String email = normalizeEmail(claims.email());
    if (isDemoAccountBlocked(email) || DEMO_ACCOUNT_EMAIL.equals(email)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "La contraseña de la cuenta demo no se puede cambiar.");
    }
    DoctorAccount account =
        findAccount(email)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no encontrada"));
    java.util.ArrayDeque<Instant> failures =
        passwordFailures.computeIfAbsent(email, k -> new java.util.ArrayDeque<>());
    synchronized (failures) {
      Instant now = Instant.now();
      while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(Duration.ofMinutes(15)))) {
        failures.pollFirst();
      }
      if (failures.size() >= 5) {
        throw new ResponseStatusException(
            HttpStatus.TOO_MANY_REQUESTS,
            "Demasiados intentos con la contraseña actual. Esperá unos minutos.");
      }
      if (!passwordHasher.verify(currentPassword, account.passwordHash())) {
        failures.addLast(now);
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "La contraseña actual no es correcta.");
      }
      failures.clear();
    }
    String problem = newPasswordProblem(newPassword, currentPassword, email);
    if (problem != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
    }
    String newHash = passwordHasher.hash(newPassword);
    postgresAuthStore.updatePasswordHash(email, newHash);
    account.changePasswordHash(newHash);
    accountsByEmail.put(email, account);
    postgresAuthStore.revokeRefreshTokensForEmail(email);
    refreshTokens.values().removeIf(email::equals);
    invalidateChallengesFor(email);
    auditPasswordChange(claims, account);
    return issueTokens(account);
  }

  // ---- Rechazo de un registro con motivo ----------------------------------------------------------
  //
  // El administrador no aprueba una cuenta pendiente (por ejemplo, la matrícula no coincide) y le dice al
  // profesional por qué. El motivo sale por correo si el entorno envía correos y, en todos los casos, queda
  // guardado para mostrarse en la pantalla de cuenta pendiente. Aprobar la cuenta después borra el rechazo.

  static final int MIN_REJECTION_REASON = 10;
  static final int MAX_REJECTION_REASON = 1000;

  public record RegistrationRejection(String email, String reason, String rejectedBy, Instant rejectedAt) {}

  public record RejectionResult(String email, boolean emailSent) {}

  public RejectionResult rejectRegistration(TokenService.Claims claims, String emailValue, String reasonValue) {
    requireAdmin(claims);
    String email = normalizeEmail(emailValue);
    String reason = reasonValue == null ? "" : reasonValue.trim();
    if (reason.length() < MIN_REJECTION_REASON || reason.length() > MAX_REJECTION_REASON) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Escribí el motivo del rechazo (entre " + MIN_REJECTION_REASON + " y " + MAX_REJECTION_REASON + " caracteres).");
    }
    DoctorAccount account =
        findAccount(email)
            .filter(a -> !DEMO_ACCOUNT_EMAIL.equals(a.email()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profesional no encontrado"));
    if (account.roles() != null && account.roles().contains("ADMIN")) {
      throw new AdminAccountProtectedException();
    }
    if (account.approved()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "La cuenta ya está aprobada: para quitarle el acceso usá «Revocar acceso».");
    }
    RegistrationRejection rejection = new RegistrationRejection(email, reason, claims.subject(), Instant.now());
    postgresAuthStore.saveRegistrationRejection(
        rejection.email(), rejection.reason(), rejection.rejectedBy(), rejection.rejectedAt());
    rejections.put(email, rejection);
    boolean emailSent = false;
    if (emailDelivery != null) {
      try {
        emailDelivery.sendRegistrationRejected(email, account.fullName(), reason);
        emailSent = true;
      } catch (RuntimeException ex) {
        log.warn("Rejection e-mail could not be sent: {}", ex.getClass().getSimpleName());
      }
    }
    if (auditService != null) {
      auditService.record(
          claims.subject(),
          "PROFESSIONAL_REGISTRATION_REJECTED",
          account.id(),
          MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
          Map.of("entityType", "professional_account", "emailSent", emailSent));
    }
    return new RejectionResult(email, emailSent);
  }

  /** El rechazo vigente de la cuenta de la sesión, o vacío. Lo usa la pantalla de cuenta pendiente. */
  public Optional<RegistrationRejection> registrationRejectionFor(TokenService.Claims claims) {
    return findRejection(normalizeEmail(claims.email()));
  }

  /** Solo ADMIN: los rechazos vigentes, para mostrarlos en la lista de profesionales. */
  public List<RegistrationRejection> listRegistrationRejections(TokenService.Claims claims) {
    requireAdmin(claims);
    java.util.Map<String, RegistrationRejection> merged = new java.util.LinkedHashMap<>();
    try {
      postgresAuthStore.listRegistrationRejections().forEach(r -> merged.put(r.email(), r));
    } catch (RuntimeException ignored) {
      // Se usa lo que haya en memoria.
    }
    merged.putAll(rejections);
    return List.copyOf(merged.values());
  }

  private Optional<RegistrationRejection> findRejection(String email) {
    RegistrationRejection cached = rejections.get(email);
    if (cached != null) return Optional.of(cached);
    try {
      Optional<RegistrationRejection> persisted = postgresAuthStore.findRegistrationRejection(email);
      persisted.ifPresent(r -> rejections.put(email, r));
      return persisted;
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private void clearRejection(String email) {
    rejections.remove(email);
    try {
      postgresAuthStore.deleteRegistrationRejection(email);
    } catch (RuntimeException ignored) {
      // El rechazo ya no se muestra a una cuenta aprobada aunque la fila no se pueda borrar.
    }
  }

  // ---- Contraseña olvidada ---------------------------------------------------------------------
  //
  // Se pide un codigo (por correo; en local, sin correo, queda en el registro del backend) y con ese
  // codigo se define la contraseña nueva. La respuesta no revela si la cuenta existe y el codigo
  // tiene un maximo de intentos. Al terminar se cierran todas las sesiones de la cuenta.

  static final String RESET_REQUEST_MESSAGE =
      "Si el correo corresponde a una cuenta verificada, enviamos un código para restablecer la contraseña.";

  public PendingAuthResponse requestPasswordReset(String emailValue) {
    String email = normalizeEmail(emailValue);
    DoctorAccount account = findAccount(email).orElse(null);
    boolean eligible =
        account != null
            && account.verified()
            && !isDemoAccountBlocked(email)
            && !DEMO_ACCOUNT_EMAIL.equals(email);
    if (!eligible) {
      // Misma forma de respuesta que una cuenta real, para no filtrar quien esta registrado.
      return new PendingAuthResponse(
          UUID.randomUUID().toString(),
          emailDelivery == null ? "email-demo" : "email",
          (int) CHALLENGE_TTL.toSeconds(),
          RESET_REQUEST_MESSAGE,
          null);
    }
    PendingAuthResponse pending = createChallenge("RESET", email, RESET_REQUEST_MESSAGE);
    if (auditService != null) {
      auditService.record(
          account.id(),
          "PASSWORD_RESET_REQUESTED",
          account.id(),
          MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
          Map.of("entityType", "professional_account", "outcome", "requested"));
    }
    return pending;
  }

  public void resetPassword(String challengeId, String code, String newPassword) {
    Challenge challenge = challengeId == null ? null : challenges.get(challengeId);
    if (challenge == null
        || !"RESET".equals(challenge.type())
        || challenge.expiresAt().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código expirado o inválido");
    }
    int attempts = resetAttempts.merge(challengeId, 1, Integer::sum);
    if (attempts > MAX_RESET_CODE_ATTEMPTS) {
      challenges.remove(challengeId);
      resetAttempts.remove(challengeId);
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Pedí un código nuevo.");
    }
    if (code == null || !challenge.code().equals(code.trim())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código inválido");
    }
    String email = challenge.email();
    DoctorAccount account =
        findAccount(email)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código expirado o inválido"));
    String problem = newPasswordProblem(newPassword, null, email);
    if (problem != null) {
      // El codigo sigue vigente: quien se equivoco con la politica puede corregir la clave nueva.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
    }
    String newHash = passwordHasher.hash(newPassword);
    postgresAuthStore.updatePasswordHash(email, newHash);
    account.changePasswordHash(newHash);
    accountsByEmail.put(email, account);
    postgresAuthStore.revokeRefreshTokensForEmail(email);
    refreshTokens.values().removeIf(email::equals);
    invalidateChallengesFor(email);
    resetAttempts.remove(challengeId);
    passwordFailures.remove(email);
    if (auditService != null) {
      auditService.record(
          account.id(),
          AuditAction.PASSWORD_CHANGED.name(),
          account.id(),
          MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
          Map.of("entityType", "professional_account", "outcome", "reset"));
    }
  }

  static String newPasswordProblem(String newPassword, String currentPassword, String email) {
    String problem = PasswordPolicy.problem(newPassword, email);
    if (problem != null) return problem.replace("La contraseña", "La nueva contraseña");
    if (newPassword.equals(currentPassword)) {
      return "La nueva contraseña debe ser distinta de la actual.";
    }
    return null;
  }

  private void auditPasswordChange(TokenService.Claims claims, DoctorAccount account) {
    if (auditService == null) return;
    auditService.record(
        claims.subject(),
        AuditAction.PASSWORD_CHANGED.name(),
        account.id(),
        MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
        Map.of("entityType", "professional_account", "outcome", "success"));
  }

  // ---- Cambio de datos del perfil --------------------------------------------------------------
  //
  // El profesional pide el cambio, un administrador lo autoriza, el profesional recibe un código por
  // correo y recién entonces puede editar nombre, matrícula e institución. Al guardar, la cuenta queda
  // en solo lectura hasta que un administrador revise los cambios. El correo no se modifica: es la
  // identidad de la cuenta.

  static final Duration PROFILE_TOKEN_TTL = Duration.ofMinutes(30);
  static final Duration PROFILE_UNLOCK_TTL = Duration.ofMinutes(30);
  private static final String TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private DoctorAccount requireProfileEditableAccount(TokenService.Claims claims) {
    String email = normalizeEmail(claims == null ? null : claims.email());
    if (isDemoAccountBlocked(email) || DEMO_ACCOUNT_EMAIL.equals(email)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La cuenta demo no puede cambiar su perfil.");
    }
    DoctorAccount account =
        findAccount(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no encontrada"));
    if (!account.verified() || !account.approved()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La cuenta todavía no está habilitada.");
    }
    if (account.roles() != null && account.roles().contains("ADMIN")) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Los datos de una cuenta de administración se cambian con otro administrador.");
    }
    return account;
  }

  private ProfileChangeResponse toProfileChangeResponse(ProfileChange change) {
    return new ProfileChangeResponse(
        change.status().name(),
        change.note(),
        change.status() == ProfileChange.Status.AUTHORIZED || change.status() == ProfileChange.Status.UNLOCKED
            ? change.expiresAt()
            : null,
        change.requestedAt(),
        change.previousFullName(),
        change.previousLicenseNumber(),
        change.previousInstitution());
  }

  public ProfileChangeResponse currentProfileChange(TokenService.Claims claims) {
    String email = normalizeEmail(claims == null ? null : claims.email());
    return profileChanges
        .find(email)
        .map(this::toProfileChangeResponse)
        .orElse(new ProfileChangeResponse("NONE", "", null, null, null, null, null));
  }

  public ProfileChangeResponse requestProfileChange(TokenService.Claims claims, String note) {
    DoctorAccount account = requireProfileEditableAccount(claims);
    if (profileChanges.find(account.email()).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Ya tenés una solicitud de cambio de perfil en curso.");
    }
    String cleanNote = defaultString(note);
    if (cleanNote.length() > 500) cleanNote = cleanNote.substring(0, 500);
    Instant now = Instant.now();
    ProfileChange change =
        new ProfileChange(
            account.email(), ProfileChange.Status.REQUESTED, cleanNote, null, null, null, null, null, now, now);
    profileChanges.save(change);
    auditProfileChange(claims, AuditAction.PROFILE_CHANGE_REQUESTED, account.id());
    if (emailDelivery != null) {
      try {
        emailDelivery.notifyProfileChangeRequested(account.fullName(), account.email(), cleanNote);
      } catch (RuntimeException ignored) {
        // El aviso por correo es una comodidad: la solicitud igual queda visible en la pantalla Profesionales.
      }
    }
    return toProfileChangeResponse(change);
  }

  public ProfileChangeResponse authorizeProfileChange(TokenService.Claims claims, String emailValue) {
    requireAdmin(claims);
    String email = normalizeEmail(emailValue);
    ProfileChange change =
        profileChanges
            .find(email)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay una solicitud de cambio para esa cuenta."));
    if (change.status() != ProfileChange.Status.REQUESTED
        && change.status() != ProfileChange.Status.AUTHORIZED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud no está esperando autorización.");
    }
    DoctorAccount account =
        findAccount(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profesional no encontrado"));
    Instant now = Instant.now();
    if (emailDelivery == null) {
      // Sin envío de correo (instalación local), la autorización del administrador habilita la edición directamente.
      ProfileChange unlocked = change.withStatus(ProfileChange.Status.UNLOCKED, null, now.plus(PROFILE_UNLOCK_TTL));
      profileChanges.save(unlocked);
      auditProfileChange(claims, AuditAction.PROFILE_CHANGE_AUTHORIZED, account.id());
      return toProfileChangeResponse(unlocked);
    }
    String token = newProfileToken();
    ProfileChange authorized =
        change.withStatus(ProfileChange.Status.AUTHORIZED, hashToken(token), now.plus(PROFILE_TOKEN_TTL));
    profileChanges.save(authorized);
    try {
      emailDelivery.sendProfileChangeToken(email, token, PROFILE_TOKEN_TTL);
    } catch (RuntimeException ex) {
      profileChanges.save(change);
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "No pudimos enviar el código al profesional. Verificá su correo e intentá nuevamente.");
    }
    auditProfileChange(claims, AuditAction.PROFILE_CHANGE_AUTHORIZED, account.id());
    return toProfileChangeResponse(authorized);
  }

  public ProfileChangeResponse unlockProfileChange(TokenService.Claims claims, String token) {
    DoctorAccount account = requireProfileEditableAccount(claims);
    ProfileChange change =
        profileChanges
            .find(account.email())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay una solicitud de cambio de perfil."));
    Instant now = Instant.now();
    if (change.status() == ProfileChange.Status.UNLOCKED && change.expiresAt() != null && change.expiresAt().isAfter(now)) {
      return toProfileChangeResponse(change);
    }
    if (change.status() != ProfileChange.Status.AUTHORIZED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Todavía no autorizaron tu solicitud.");
    }
    if (change.expiresAt() == null || change.expiresAt().isBefore(now)) {
      profileChanges.save(change.withStatus(ProfileChange.Status.REQUESTED, null, null));
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "El código venció. Pedí a un administrador que autorice de nuevo la solicitud.");
    }
    java.util.ArrayDeque<Instant> failures =
        profileTokenFailures.computeIfAbsent(account.email(), k -> new java.util.ArrayDeque<>());
    synchronized (failures) {
      while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(Duration.ofMinutes(15)))) {
        failures.pollFirst();
      }
      if (failures.size() >= 5) {
        throw new ResponseStatusException(
            HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos con el código. Esperá unos minutos.");
      }
      String candidate = token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
      if (change.tokenHash() == null
          || !java.security.MessageDigest.isEqual(
              hashToken(candidate).getBytes(java.nio.charset.StandardCharsets.UTF_8),
              change.tokenHash().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
        failures.addLast(now);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El código no es correcto.");
      }
      failures.clear();
    }
    ProfileChange unlocked = change.withStatus(ProfileChange.Status.UNLOCKED, null, now.plus(PROFILE_UNLOCK_TTL));
    profileChanges.save(unlocked);
    return toProfileChangeResponse(unlocked);
  }

  public TokenResponse submitProfileChange(
      TokenService.Claims claims, String fullName, String licenseNumber, String institution) {
    DoctorAccount account = requireProfileEditableAccount(claims);
    ProfileChange change =
        profileChanges
            .find(account.email())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay una solicitud de cambio de perfil."));
    Instant now = Instant.now();
    if (change.status() != ProfileChange.Status.UNLOCKED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "La edición del perfil todavía no está habilitada.");
    }
    if (change.expiresAt() == null || change.expiresAt().isBefore(now)) {
      profileChanges.save(change.withStatus(ProfileChange.Status.REQUESTED, null, null));
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "La habilitación venció. Pedí una nueva autorización.");
    }
    String newName = defaultString(fullName);
    String newLicense = defaultString(licenseNumber);
    String newInstitution = defaultString(institution);
    if (newName.isBlank() || newName.length() > 120) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio (hasta 120 caracteres).");
    }
    if (newLicense.length() > 40) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La matrícula admite hasta 40 caracteres.");
    }
    if (newInstitution.length() > 120) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La institución admite hasta 120 caracteres.");
    }
    if (newName.equals(account.fullName())
        && newLicense.equals(account.licenseNumber())
        && newInstitution.equals(account.institution())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay cambios para enviar.");
    }
    ProfileChange pending =
        new ProfileChange(
            account.email(),
            ProfileChange.Status.PENDING_REVIEW,
            change.note(),
            null,
            null,
            account.fullName(),
            account.licenseNumber(),
            account.institution(),
            change.requestedAt(),
            now);
    profileChanges.save(pending);
    try {
      postgresAuthStore.updateProfileFields(account.email(), newName, newLicense, newInstitution);
    } catch (RuntimeException ex) {
      profileChanges.save(change);
      throw ex;
    }
    account.updateProfile(newName, newLicense, newInstitution);
    accountsByEmail.put(account.email(), account);
    auditProfileChange(claims, AuditAction.PROFILE_CHANGE_SUBMITTED, account.id());
    return issueTokens(account);
  }

  public ProfileChangeResponse cancelProfileChange(TokenService.Claims claims) {
    DoctorAccount account = requireProfileEditableAccount(claims);
    ProfileChange change = profileChanges.find(account.email()).orElse(null);
    if (change == null) return new ProfileChangeResponse("NONE", "", null, null, null, null, null);
    if (change.status() == ProfileChange.Status.PENDING_REVIEW) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Los cambios ya están en revisión: los resuelve un administrador.");
    }
    profileChanges.remove(account.email());
    return new ProfileChangeResponse("NONE", "", null, null, null, null, null);
  }

  public List<AdminProfileChangeItem> listProfileChanges(TokenService.Claims claims) {
    requireAdmin(claims);
    List<AdminProfileChangeItem> items = new java.util.ArrayList<>();
    for (ProfileChange change : profileChanges.list()) {
      DoctorAccount account = findAccount(normalizeEmail(change.email())).orElse(null);
      if (account == null) continue;
      items.add(
          new AdminProfileChangeItem(
              account.email(),
              account.fullName(),
              account.licenseNumber(),
              account.institution(),
              change.status().name(),
              change.note(),
              change.requestedAt(),
              change.previousFullName(),
              change.previousLicenseNumber(),
              change.previousInstitution()));
    }
    items.sort(java.util.Comparator.comparing(AdminProfileChangeItem::requestedAt));
    return items;
  }

  /** Aprueba o rechaza. Rechazar cambios ya guardados los revierte a los datos anteriores. */
  public void decideProfileChange(TokenService.Claims claims, String emailValue, boolean approve) {
    requireAdmin(claims);
    String email = normalizeEmail(emailValue);
    ProfileChange change =
        profileChanges
            .find(email)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay una solicitud de cambio para esa cuenta."));
    DoctorAccount account =
        findAccount(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profesional no encontrado"));
    if (change.status() != ProfileChange.Status.PENDING_REVIEW) {
      if (approve) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Todavía no hay cambios para aprobar. Autorizá la solicitud o rechazala.");
      }
      profileChanges.remove(email);
      auditProfileChange(claims, AuditAction.PROFILE_CHANGE_REJECTED, account.id());
      return;
    }
    if (approve) {
      profileChanges.remove(email);
      auditProfileChange(claims, AuditAction.PROFILE_CHANGE_APPROVED, account.id());
      return;
    }
    String name = change.previousFullName() == null ? account.fullName() : change.previousFullName();
    String license = change.previousLicenseNumber() == null ? account.licenseNumber() : change.previousLicenseNumber();
    String institution = change.previousInstitution() == null ? account.institution() : change.previousInstitution();
    postgresAuthStore.updateProfileFields(email, name, license, institution);
    account.updateProfile(name, license, institution);
    accountsByEmail.put(email, account);
    profileChanges.remove(email);
    auditProfileChange(claims, AuditAction.PROFILE_CHANGE_REJECTED, account.id());
  }

  private String newProfileToken() {
    StringBuilder builder = new StringBuilder(8);
    for (int i = 0; i < 8; i++) builder.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
    return builder.toString();
  }

  private static String hashToken(String token) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void auditProfileChange(TokenService.Claims claims, AuditAction action, String targetAccountId) {
    if (auditService == null) return;
    auditService.record(
        claims.subject(),
        action.name(),
        targetAccountId,
        MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
        Map.of("entityType", "professional_account", "outcome", "success"));
  }

  public UserResponse currentUser(TokenService.Claims claims) {
    DoctorAccount account = findAccount(normalizeEmail(claims.email())).orElse(null);
    if (account == null) {
      return new UserResponse(
          claims.subject(),
          claims.name(),
          claims.email(),
          "",
          "",
          "",
          claims.roles(),
          true,
          true,
          false,
          true);
    }
    return toUser(account, claims.roles());
  }

  /**
   * Mismo fundamento de roles efectivos que {@link #currentUser} — /settings es accesible también con
   * PENDING_APPROVAL.
   */
  public UserResponse updateSettings(TokenService.Claims claims, SettingsRequest request) {
    DoctorAccount account =
        findAccount(normalizeEmail(claims.email()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no encontrada"));
    if (request.twoFactorEnabled() != null) account.setTwoFactorEnabled(request.twoFactorEnabled());
    if (request.onboardingCompleted() != null)
      account.setOnboardingCompleted(request.onboardingCompleted());
    accountsByEmail.put(account.email(), account);
    postgresAuthStore.updateSettings(
        account.email(), request.twoFactorEnabled(), request.onboardingCompleted());
    return toUser(account, claims.roles());
  }

  public List<UserResponse> listProfessionals(TokenService.Claims claims) {
    requireAdmin(claims);
    List<DoctorAccount> persisted = postgresAuthStore.listAccounts();
    if (!persisted.isEmpty()) {
      persisted.forEach(account -> accountsByEmail.put(account.email(), account));
      return persisted.stream().map(this::toUser).toList();
    }
    return accountsByEmail.values().stream().map(this::toUser).toList();
  }

  /**
   * Endpoint legacy mantenido solo por compatibilidad con el frontend (ver AuthController) — ahora delega en
   * la misma operación de dominio que la activación institucional, por lo que ya no puede eludir
   * la protección del último ADMIN, el bloqueo de demo, la persistencia fail-closed, la revocación de sesiones ni la invalidación
   * de desafíos como antes. Deprecado: se eliminará cuando el frontend migre a
   * /activation (P10-C).
   */
  @Deprecated
  public UserResponse approveProfessional(
      TokenService.Claims claims, String emailValue, boolean approved) {
    setProfessionalActivation(claims, emailValue, approved);
    DoctorAccount account =
        findAccount(normalizeEmail(emailValue))
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Profesional no encontrado"));
    return toUser(account);
  }

  /**
   * "Activación institucional manual" — un ADMIN avala una cuenta profesional por fuera de cualquier
   * flujo de verificación por email. Deliberadamente NO es lo mismo que la verificación por email: no
   * afirma que se haya enviado ni confirmado un email, y nunca emite un token. Activar nunca otorga
   * ADMIN (el DTO del request solo lleva email+activated, así que no hay nada para colar
   * desde el llamante) y, desde P10-B.2, tampoco reemplaza nunca un conjunto de roles profesionales existente —
   * ver {@link #resolveActivationRoles}.
   */
  public ProfessionalActivationResponse activateProfessional(
      TokenService.Claims claims, String emailValue, boolean activated) {
    return setProfessionalActivation(claims, emailValue, activated);
  }

  /**
   * La única operación de dominio a la que delegan tanto /approval (legacy) como /activation — ver
   * las notas P10-A.2.1 a nivel de clase. Existe exactamente una implementación de "cambiar el
   * estado activo de un profesional" en este servicio.
   *
   * <p>Este es un flujo SOLO para PROFESIONALES: una cuenta que ya lleva ADMIN nunca puede tocarse
   * acá, en ninguna dirección (activated=true o activated=false) — sus roles, flags approved/verified,
   * contraseña y todo lo demás quedan intactos. La gestión de administradores corresponde a un
   * flujo separado y dedicado.
   */
  private ProfessionalActivationResponse setProfessionalActivation(
      TokenService.Claims claims, String emailValue, boolean activated) {
    requireAdmin(claims);
    String email = normalizeEmail(emailValue);
    if (DEMO_ACCOUNT_EMAIL.equals(email)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta operacion.");
    }
    DoctorAccount account =
        findAccount(email)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Profesional no encontrado"));
    if (account.roles() != null && account.roles().contains("ADMIN")) {
      throw new AdminAccountProtectedException();
    }
    return activated ? activate(claims, account) : deactivate(claims, account);
  }

  private ProfessionalActivationResponse activate(
      TokenService.Claims claims, DoctorAccount account) {
    List<String> targetRoles = resolveActivationRoles(account.roles());
    postgresAuthStore.updateProfessionalActivation(account.email(), true, true, targetRoles);
    account.verify();
    account.approve(true);
    account.setRoles(targetRoles);
    accountsByEmail.put(account.email(), account);
    clearRejection(account.email());
    invalidateChallengesFor(account.email());
    auditActivation(claims, account, AuditAction.PROFESSIONAL_ACTIVATED, true, targetRoles);
    return new ProfessionalActivationResponse(
        account.email(), "activated", true, true, targetRoles);
  }

  /**
   * P10-B.2: la activación cambia solo el *estado* de la cuenta — nunca debe reemplazar un conjunto de roles ya significativo
   * por el par por defecto DOCTOR+REVIEWER. El valor por defecto se usa solo la primera vez que una
   * cuenta se aprueba (sus roles son todavía exactamente el placeholder {@code PENDING_APPROVAL} del
   * registro, o están vacíos/corruptos) — cualquier conjunto real de roles ya presente en la cuenta (un solo rol, un
   * subconjunto restringido, un rol extra) sobrevive a la activación/reactivación sin cambios, byte por byte.
   */
  private List<String> resolveActivationRoles(List<String> currentRoles) {
    List<String> real =
        currentRoles == null
            ? List.of()
            : currentRoles.stream().filter(role -> !"PENDING_APPROVAL".equals(role)).toList();
    return real.isEmpty() ? List.of("DOCTOR", "REVIEWER") : real;
  }

  /**
   * La desactivación + revocación de sesiones ocurren dentro de una única transacción de Postgres
   * (PostgresAuthStoreService.deactivateProfessionalAndRevokeSessions) — el
   * cache en memoria y el mapa de refresh tokens solo se modifican después de que esa llamada retorna con éxito, así que una falla
   * nunca deja la cuenta desactivada localmente mientras Postgres la sigue teniendo activa (o
   * viceversa).
   *
   * <p>P10-B.2: la desactivación cambia solo el *estado* de la cuenta — los roles se releen y se persisten
   * sin cambios (nunca se reemplazan por PENDING_APPROVAL). El acceso sigue correctamente
   * restringido mientras está desactivada: AuthAccountStateService deriva los roles *efectivos* del request
   * de verified/approved, no de esta columna de roles (ver sus docs de clase), y
   * AuthService.issueTokens hace lo mismo al emitir cualquier token nuevo para esta cuenta mientras
   * siga sin aprobar.
   */
  private ProfessionalActivationResponse deactivate(
      TokenService.Claims claims, DoctorAccount account) {
    if (isLastNonDemoAdmin(account)) {
      throw new LastAdminProtectionException();
    }
    List<String> preservedRoles =
        account.roles() == null ? List.of() : List.copyOf(account.roles());
    postgresAuthStore.deactivateProfessionalAndRevokeSessions(
        account.email(), account.verified(), preservedRoles);
    account.approve(false);
    accountsByEmail.put(account.email(), account);
    refreshTokens.values().removeIf(account.email()::equals);
    invalidateChallengesFor(account.email());
    auditActivation(claims, account, AuditAction.PROFESSIONAL_DEACTIVATED, false, preservedRoles);
    return new ProfessionalActivationResponse(
        account.email(), "deactivated", account.verified(), false, preservedRoles);
  }

  /**
   * Best-effort, nunca lanza excepción — se usa solo para el flag del resumen de diagnóstico de ADMIN, no para autorizar.
   */
  public boolean hasConfiguredAdminSafe() {
    try {
      return postgresAuthStore.enabled() && postgresAuthStore.hasNonDemoAdmin(DEMO_ACCOUNT_EMAIL);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private boolean isLastNonDemoAdmin(DoctorAccount account) {
    if (DEMO_ACCOUNT_EMAIL.equals(account.email()) || !account.roles().contains("ADMIN")) {
      return false;
    }
    return countOtherNonDemoAdmins(account.email()) == 0;
  }

  /**
   * Con Postgres habilitado, esta es una decisión de seguridad y debe ser fail-closed: la imposibilidad de
   * contar administradores bloquea la operación (vía LastAdminProtectionException) en lugar de
   * asumir que existen otros admins. Sin Postgres (modo memoria/dev/test), recurre al
   * cache en memoria — aceptable ahí porque SecurityStartupValidator exige Postgres en
   * producción de todos modos.
   */
  private long countOtherNonDemoAdmins(String excludedEmail) {
    if (postgresAuthStore.enabled()) {
      try {
        return postgresAuthStore.countActiveNonDemoAdminsExcluding(excludedEmail);
      } catch (RuntimeException ex) {
        throw new LastAdminProtectionException();
      }
    }
    return accountsByEmail.values().stream()
        .filter(a -> !DEMO_ACCOUNT_EMAIL.equals(a.email()))
        .filter(a -> !a.email().equals(excludedEmail))
        .filter(a -> a.roles().contains("ADMIN"))
        .count();
  }

  private void invalidateChallengesFor(String email) {
    challenges.values().removeIf(challenge -> email.equals(challenge.email()));
  }

  /**
   * P10-B.1 §9: actorId/entityId son UUIDs técnicos, nunca un email — {@code claims.subject()} es
   * el id de cuenta del ADMIN que actúa (tras la revalidación, ya que {@code claims} acá siempre proviene de
   * un llamante ya autenticado/autorizado), {@code account.id()} es el id de la cuenta objetivo.
   * traceId proviene del MDC del request actual (seteado por TraceIdFilter), no es un string vacío.
   * AuditService.record() es en sí fail-safe (nunca lanza excepción), por lo que acá no hace falta try/catch.
   */
  private void auditActivation(
      TokenService.Claims claims,
      DoctorAccount account,
      AuditAction action,
      boolean activated,
      List<String> roles) {
    if (auditService == null) return;
    String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
    auditService.record(
        claims.subject(),
        action.name(),
        account.id(),
        traceId,
        Map.of(
            "entityType",
            "professional_account",
            "outcome",
            "success",
            "activated",
            activated,
            "roles",
            roles));
  }

  /**
   * Emite un token ADMIN/DOCTOR/REVIEWER totalmente aprobado sin ningún chequeo de credenciales. Se rechaza
   * (404, para no anunciar la existencia del endpoint) siempre que el modo demo no esté efectivamente
   * habilitado — es decir, siempre en producción, y en local salvo que se haya seteado explícitamente
   * PFI_AUTH_DEMO_ENABLED=true. Este chequeo se aplica acá en la capa de servicio sin importar cómo
   * llegó el request a este método (AuthFilter solo controla si se *requiere* un token para llegar).
   */
  public TokenResponse seedDemoDoctor() {
    if (!isDemoEffectivelyEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No encontrado");
    }
    String email = DEMO_ACCOUNT_EMAIL;
    DoctorAccount existing = findAccount(email).orElse(null);
    if (existing != null) {
      existing.verify();
      existing.approve(true);
      existing.setRoles(List.of("ADMIN", "DOCTOR", "REVIEWER"));
      existing.setTwoFactorEnabled(false);
      accountsByEmail.put(email, existing);
      postgresAuthStore.saveAccount(existing);
      return issueTokens(existing);
    }
    DoctorAccount account =
        new DoctorAccount(
            UUID.randomUUID().toString(),
            "Dra. Demo Reviewer",
            email,
            passwordHasher.hash("Demo1234!"),
            "MN-DEMO-2026",
            "Radiología / Columna lumbar",
            "PFI Academic Lab",
            List.of("ADMIN", "DOCTOR", "REVIEWER"),
            Instant.now(),
            true,
            true,
            false,
            true);
    accountsByEmail.put(email, account);
    postgresAuthStore.saveAccount(account);
    return issueTokens(account);
  }

  public Map<String, Object> diagnostics() {
    return Map.of(
        "enabled", true,
        "mode", postgresAuthStore.enabled() ? "jwt-postgres" : "jwt-memory",
        "professionalAccountsPersisted", postgresAuthStore.enabled(),
        "professionalApprovalRequired", true,
        "twoFactorConfigurable", true,
        "refreshTokensRevocable", true,
        "status", "enabled");
  }

  private Optional<DoctorAccount> findAccount(String email) {
    DoctorAccount cached = accountsByEmail.get(email);
    if (cached != null) return Optional.of(cached);
    Optional<DoctorAccount> persisted = postgresAuthStore.findByEmail(email);
    persisted.ifPresent(account -> accountsByEmail.put(email, account));
    return persisted;
  }

  private boolean isProductionProfile() {
    if (environment == null) return false;
    for (String profile : environment.getActiveProfiles()) {
      String normalized = profile.toLowerCase(Locale.ROOT);
      if (normalized.equals("production") || normalized.equals("prod")) return true;
    }
    return false;
  }

  private boolean isDemoEffectivelyEnabled() {
    return demoEnabled && !isProductionProfile();
  }

  /**
   * Bloquea la identidad de la cuenta demo conocida siempre que el modo demo no esté efectivamente habilitado —
   * sin importar qué roles pueda llevar ya en un store persistido.
   */
  private boolean isDemoAccountBlocked(String emailValue) {
    return DEMO_ACCOUNT_EMAIL.equals(normalizeEmail(emailValue)) && !isDemoEffectivelyEnabled();
  }

  private void requireAdmin(TokenService.Claims claims) {
    if (claims == null || claims.roles() == null || !claims.roles().contains("ADMIN")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requiere rol ADMIN");
    }
  }

  private PendingAuthResponse createChallenge(String type, String email, String message) {
    if (emailDelivery != null) {
      throttleCodeRequests(email);
    }
    String challengeId = UUID.randomUUID().toString();
    String code = String.format("%06d", random.nextInt(1_000_000));
    challenges.put(
        challengeId,
        new Challenge(challengeId, email, type, code, Instant.now().plus(CHALLENGE_TTL)));
    if (emailDelivery == null) {
      if ("RESET".equals(type)) {
        // Instalacion local sin correo: el codigo no sale por la API; queda en el registro del backend.
        log.warn("[RESTABLECER-CLAVE] codigo para {}: {} (vence en {} min)", email, code, CHALLENGE_TTL.toMinutes());
      }
      return new PendingAuthResponse(
          challengeId,
          "email-demo",
          (int) CHALLENGE_TTL.toSeconds(),
          message,
          exposeCodes ? code : null);
    }
    challenges.entrySet().removeIf(e -> e.getValue().email().equals(email) && !e.getKey().equals(challengeId));
    try {
      emailDelivery.sendVerificationCode(email, type, code, CHALLENGE_TTL);
    } catch (RuntimeException e) {
      challenges.remove(challengeId);
      // El intento fallido no debe gastar el cupo de reintentos: quien corrige el correo tiene que poder volver a probar.
      java.util.ArrayDeque<Instant> sends = codeSends.get(email);
      if (sends != null) {
        synchronized (sends) {
          sends.pollLast();
        }
      }
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Falló el envío del código. Verificá tus datos, preferentemente el correo electrónico, e intentá nuevamente.");
    }
    return new PendingAuthResponse(
        challengeId,
        "email",
        (int) CHALLENGE_TTL.toSeconds(),
        "REGISTER".equals(type)
            ? "Enviamos un código de verificación a tu correo. Luego la cuenta queda pendiente de aprobación institucional."
            : "RESET".equals(type)
                ? RESET_REQUEST_MESSAGE
                : "Enviamos un código de verificación a tu correo.",
        exposeCodes ? code : null);
  }

  /** Máximo 1 código cada 30 s y 5 por hora por dirección, para que el endpoint no pueda usarse para spam. */
  private void throttleCodeRequests(String email) {
    java.util.ArrayDeque<Instant> sends =
        codeSends.computeIfAbsent(email, k -> new java.util.ArrayDeque<>());
    synchronized (sends) {
      Instant now = Instant.now();
      while (!sends.isEmpty() && sends.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
        sends.pollFirst();
      }
      if ((!sends.isEmpty() && sends.peekLast().isAfter(now.minusSeconds(30)))
          || sends.size() >= 5) {
        throw new ResponseStatusException(
            HttpStatus.TOO_MANY_REQUESTS,
            "Ya enviamos un código a ese correo. Esperá unos instantes antes de pedir otro.");
      }
      sends.addLast(now);
    }
  }

  /**
   * P10-B.2: siempre genera el access token — y el UserResponse embebido junto a él — a partir de los roles
   * *efectivos* (verified && approved ? account.roles() : PENDING_APPROVAL), nunca de los
   * roles crudos almacenados de la cuenta. Esto importa porque los roles ya no se borran al
   * desactivar (se preservan para la reactivación) — sin esto, un login o refresh de una cuenta
   * todavía sin aprobar/desactivada generaría un token (y mostraría un body de respuesta) con
   * sus roles profesionales reales. Los roles crudos de la cuenta en el almacenamiento no se tocan; esto
   * solo afecta lo que embebe esta respuesta puntual.
   */
  private TokenResponse issueTokens(DoctorAccount account) {
    List<String> effectiveRoles = effectiveRoles(account);
    String accessToken = tokenService.issueAccessToken(account, effectiveRoles);
    String refreshToken =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(UUID.randomUUID().toString().getBytes());
    Instant expiresAt = Instant.now().plusSeconds(refreshTokenSeconds);
    refreshTokens.put(refreshToken, account.email());
    postgresAuthStore.saveRefreshToken(refreshToken, account.email(), expiresAt);
    return new TokenResponse(
        accessToken,
        refreshToken,
        "Bearer",
        tokenService.accessTokenSeconds(),
        toUser(account, effectiveRoles));
  }

  private void revokeRefreshToken(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) return;
    refreshTokens.remove(refreshToken);
    postgresAuthStore.revokeRefreshToken(refreshToken);
  }

  /**
   * Vistas para ADMIN / resultado de autoactivación: muestran los roles crudos persistidos de la cuenta (útil —
   * p. ej. un ADMIN puede ver los roles preservados de una cuenta desactivada).
   */
  private UserResponse toUser(DoctorAccount account) {
    return toUser(account, account.roles());
  }

  /**
   * {@code /me} y {@code /settings} usan esto con los roles *efectivos* del llamante — ver
   * los sitios de llamada.
   */
  private UserResponse toUser(DoctorAccount account, List<String> roles) {
    return new UserResponse(
        account.id(),
        account.fullName(),
        account.email(),
        account.licenseNumber(),
        account.specialty(),
        account.institution(),
        roles,
        account.verified(),
        account.approved(),
        account.twoFactorEnabled(),
        account.onboardingCompleted());
  }

  /**
   * verified && approved ? account.roles() : PENDING_APPROVAL — ver issueTokens/currentUser para entender por qué
   * esto nunca debe ser la columna de roles cruda directamente.
   */
  private List<String> effectiveRoles(DoctorAccount account) {
    if (!(account.verified() && account.approved())) return List.of("PENDING_APPROVAL");
    if (profileChanges != null
        && account.roles() != null
        && !account.roles().contains("ADMIN")
        && profileChanges.isReviewPending(account.email())) {
      List<String> roles = new java.util.ArrayList<>(account.roles());
      roles.add("READ_ONLY");
      return roles;
    }
    return account.roles();
  }

  private String normalizeEmail(String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  private String defaultString(String value) {
    return value == null ? "" : value.trim();
  }

  private record Challenge(String id, String email, String type, String code, Instant expiresAt) {}
}
