package ar.edu.uade.pfi.backend.auth;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Crea exactamente un ADMIN real de producción, una sola vez, para que SPRING_PROFILES_ACTIVE=production +
 * PFI_AUTH_DEMO_ENABLED=false nunca deje un despliegue sin administradores (la cuenta demo,
 * la única que antes llevaba ADMIN por defecto, es rechazada correctamente en producción por
 * P10-A.1 — esto cubre ese vacío con una cuenta real, aprovisionada deliberadamente).
 *
 * <p>No es un controller: no hay ningún endpoint HTTP para esto. Solo corre vía {@code @PostConstruct},
 * y solo cuando el perfil de Spring activo es production/prod — cualquier otro perfil
 * (default/dev/test) es un no-op completo, por lo que `mvn test` nunca depende de ninguna de las
 * variables bootstrap-admin-*.
 *
 * <p>{@code @DependsOn("securityStartupValidator")} fuerza el orden de inicialización: (1)
 * el @PostConstruct de SecurityStartupValidator valida primero la configuración estructural de producción; recién
 * entonces (2) se construye este bean y corre su @PostConstruct, contra (3) un
 * PostgresAuthStoreService ya migrado (la migración ocurre en el constructor de ese bean,
 * que ya terminó cuando se inyecta acá).
 */
@Component
@DependsOn("securityStartupValidator")
public class AdminBootstrapService {
  private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final String DEMO_PASSWORD = "Demo1234!";
  private static final int MIN_PASSWORD_LENGTH = 16;

  private final PostgresAuthStoreService postgresAuthStore;
  private final PasswordHasher passwordHasher;
  private final Environment environment;
  private final boolean bootstrapEnabled;
  private final String email;
  private final String password;
  private final String fullName;
  private final String licenseNumber;
  private final String institution;
  private final String jwtSecret;

  public AdminBootstrapService(
      PostgresAuthStoreService postgresAuthStore,
      PasswordHasher passwordHasher,
      Environment environment,
      @Value("${pfi.auth.bootstrap-admin-enabled:false}") boolean bootstrapEnabled,
      @Value("${pfi.auth.bootstrap-admin-email:}") String email,
      @Value("${pfi.auth.bootstrap-admin-password:}") String password,
      @Value("${pfi.auth.bootstrap-admin-full-name:}") String fullName,
      @Value("${pfi.auth.bootstrap-admin-license-number:}") String licenseNumber,
      @Value("${pfi.auth.bootstrap-admin-institution:}") String institution,
      @Value("${pfi.auth.jwt-secret:pfi-demo-change-me-2026}") String jwtSecret) {
    this.postgresAuthStore = postgresAuthStore;
    this.passwordHasher = passwordHasher;
    this.environment = environment;
    this.bootstrapEnabled = bootstrapEnabled;
    this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    this.password = password == null ? "" : password;
    this.fullName = fullName == null ? "" : fullName.trim();
    this.licenseNumber = licenseNumber == null ? "" : licenseNumber.trim();
    this.institution = institution == null ? "" : institution.trim();
    this.jwtSecret = jwtSecret == null ? "" : jwtSecret;
  }

  @PostConstruct
  void bootstrap() {
    if (!isProductionProfile()) {
      // Fuera de producción, nunca se crea un admin automáticamente ni se bloquea el arranque por su ausencia.
      log.info("startup.adminBootstrap.skipped reason=non_production");
      return;
    }
    if (postgresAuthStore.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)) {
      log.info("admin_bootstrap_skipped_existing reason=admin_already_persisted");
      return;
    }
    if (!bootstrapEnabled) {
      throw new IllegalStateException("No existe un administrador productivo configurado.");
    }
    validate();
    DoctorAccount account =
        new DoctorAccount(
            UUID.randomUUID().toString(),
            fullName,
            email,
            passwordHasher.hash(password),
            licenseNumber,
            "",
            institution,
            List.of("ADMIN", "DOCTOR", "REVIEWER"),
            Instant.now(),
            true,
            true,
            false,
            false);
    postgresAuthStore.createBootstrapAdmin(account);
    DoctorAccount persisted =
        postgresAuthStore
            .findByEmail(email)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No se pudo confirmar la persistencia del administrador productivo."));
    if (!persisted.roles().contains("ADMIN")) {
      throw new IllegalStateException(
          "El administrador productivo persistido no contiene el rol ADMIN.");
    }
    log.info("admin_bootstrap_created");
  }

  private void validate() {
    if (email.isBlank() || !EMAIL_PATTERN.matcher(email).matches()) {
      fail("PFI_AUTH_BOOTSTRAP_ADMIN_EMAIL no es una direccion de correo valida");
    }
    if (AuthService.DEMO_ACCOUNT_EMAIL.equals(email)) {
      fail("PFI_AUTH_BOOTSTRAP_ADMIN_EMAIL no puede ser la cuenta demo");
    }
    if (password.length() < MIN_PASSWORD_LENGTH) {
      fail(
          "PFI_AUTH_BOOTSTRAP_ADMIN_PASSWORD debe tener al menos "
              + MIN_PASSWORD_LENGTH
              + " caracteres");
    }
    if (DEMO_PASSWORD.equals(password)) {
      fail("PFI_AUTH_BOOTSTRAP_ADMIN_PASSWORD no puede ser la contrasena demo");
    }
    // Nunca loguear ninguno de los dos valores que se comparan acá.
    if (!jwtSecret.isBlank() && password.equals(jwtSecret)) {
      fail("PFI_AUTH_BOOTSTRAP_ADMIN_PASSWORD no puede ser igual a PFI_AUTH_JWT_SECRET");
    }
    if (fullName.isBlank()) {
      fail("PFI_AUTH_BOOTSTRAP_ADMIN_FULL_NAME no puede estar vacio");
    }
    if (licenseNumber.isBlank()) {
      fail("PFI_AUTH_BOOTSTRAP_ADMIN_LICENSE_NUMBER no puede estar vacio");
    }
    if (institution.isBlank()) {
      fail("PFI_AUTH_BOOTSTRAP_ADMIN_INSTITUTION no puede estar vacio");
    }
  }

  private void fail(String sanitizedMessage) {
    throw new IllegalStateException(sanitizedMessage);
  }

  private boolean isProductionProfile() {
    for (String profile : environment.getActiveProfiles()) {
      String normalized = profile.toLowerCase(Locale.ROOT);
      if (normalized.equals("production") || normalized.equals("prod")) {
        return true;
      }
    }
    return false;
  }
}
