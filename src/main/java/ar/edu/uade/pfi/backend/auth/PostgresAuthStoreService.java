package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.repository.PfiHikariDataSourceFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PostgresAuthStoreService {
  private static final Logger log = LoggerFactory.getLogger(PostgresAuthStoreService.class);
  private final DataSource dataSource;
  private final boolean enabled;

  /**
   * El DataSource con pool de toda la app solo existe como bean en modo postgres
   * ({@code PfiDataSourceConfig}); {@link ObjectProvider} es la forma en que un bean que siempre se
   * crea (este) tolera que ese bean a veces no exista.
   */
  @Autowired
  public PostgresAuthStoreService(
      @Value("${pfi.persistence.mode:memory}") String persistenceMode,
      ObjectProvider<DataSource> dataSourceProvider) {
    this(persistenceMode, dataSourceProvider.getIfAvailable());
  }

  public PostgresAuthStoreService(String persistenceMode, DataSource dataSource) {
    long started = System.nanoTime();
    log.info("startup.authStore.begin");
    this.dataSource = dataSource;
    this.enabled = "postgres".equalsIgnoreCase(persistenceMode) && this.dataSource != null;
    if (enabled) {
      long migrationStarted = System.nanoTime();
      log.info("startup.authStore.migrate.begin");
      migrate();
      log.info("startup.authStore.migrate.ok durationMs={}", elapsedMillis(migrationStarted));
    }
    log.info("startup.authStore.ready durationMs={}", elapsedMillis(started));
  }

  private long elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }

  /** Conveniencia para tests: arma su propio pool chico a partir de una URL JDBC/de conexión cruda. */
  public PostgresAuthStoreService(String persistenceMode, String databaseUrl) {
    this(persistenceMode, buildDataSourceOrNull(databaseUrl));
  }

  private static DataSource buildDataSourceOrNull(String databaseUrl) {
    String jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
    return jdbcUrl.isBlank() ? null : PfiHikariDataSourceFactory.create(jdbcUrl);
  }

  public boolean enabled() {
    return enabled;
  }

  public Optional<DoctorAccount> findByEmail(String email) {
    if (!enabled) return Optional.empty();
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, approved, two_factor_enabled, onboarding_completed
            FROM doctor_accounts
            WHERE email = ?
            """)) {
      statement.setString(1, email);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(readAccount(rs));
      }
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  public List<DoctorAccount> listAccounts() {
    if (!enabled) return List.of();
    List<DoctorAccount> accounts = new ArrayList<>();
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, approved, two_factor_enabled, onboarding_completed
            FROM doctor_accounts
            ORDER BY created_at DESC
            """)) {
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) accounts.add(readAccount(rs));
      }
    } catch (Exception ignored) {
      return List.of();
    }
    return accounts;
  }

  public DoctorAccount saveAccount(DoctorAccount account) {
    if (!enabled) return account;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO doctor_accounts(id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, approved, two_factor_enabled, onboarding_completed, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (email) DO UPDATE SET
              full_name = EXCLUDED.full_name,
              password_hash = EXCLUDED.password_hash,
              license_number = EXCLUDED.license_number,
              specialty = EXCLUDED.specialty,
              institution = EXCLUDED.institution,
              roles = EXCLUDED.roles,
              verified = EXCLUDED.verified,
              approved = EXCLUDED.approved,
              two_factor_enabled = EXCLUDED.two_factor_enabled,
              onboarding_completed = EXCLUDED.onboarding_completed,
              updated_at = now()
            """)) {
      statement.setString(1, account.id());
      statement.setString(2, account.fullName());
      statement.setString(3, account.email());
      statement.setString(4, account.passwordHash());
      statement.setString(5, account.licenseNumber());
      statement.setString(6, account.specialty());
      statement.setString(7, account.institution());
      statement.setString(8, String.join(",", account.roles()));
      statement.setTimestamp(9, Timestamp.from(account.createdAt()));
      statement.setBoolean(10, account.verified());
      statement.setBoolean(11, account.approved());
      statement.setBoolean(12, account.twoFactorEnabled());
      statement.setBoolean(13, account.onboardingCompleted());
      statement.executeUpdate();
    } catch (Exception ignored) {
      // Auth sigue disponible mediante el fallback en memoria.
    }
    return account;
  }

  public void markVerified(String email) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            UPDATE doctor_accounts SET verified = TRUE, updated_at = now() WHERE email = ?
            """)) {
      statement.setString(1, email);
      statement.executeUpdate();
    } catch (Exception ignored) {
      // La cuenta en memoria sigue siendo verificada por AuthService.
    }
  }

  public void updateApproval(String email, boolean approved, List<String> roles) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            UPDATE doctor_accounts
            SET approved = ?, roles = ?, updated_at = now()
            WHERE email = ?
            """)) {
      statement.setBoolean(1, approved);
      statement.setString(2, String.join(",", roles));
      statement.setString(3, email);
      statement.executeUpdate();
    } catch (Exception ignored) {
      // La cuenta en memoria sigue siendo la autoridad para este request.
    }
  }

  public void updateSettings(String email, Boolean twoFactorEnabled, Boolean onboardingCompleted) {
    if (!enabled) return;
    try (Connection connection = connection()) {
      if (twoFactorEnabled != null) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE doctor_accounts SET two_factor_enabled = ?, updated_at = now() WHERE email = ?")) {
          statement.setBoolean(1, twoFactorEnabled);
          statement.setString(2, email);
          statement.executeUpdate();
        }
      }
      if (onboardingCompleted != null) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE doctor_accounts SET onboarding_completed = ?, updated_at = now() WHERE email = ?")) {
          statement.setBoolean(1, onboardingCompleted);
          statement.setString(2, email);
          statement.executeUpdate();
        }
      }
    } catch (Exception ignored) {
      // La actualización en memoria ya ocurrió en AuthService.
    }
  }

  public void saveRefreshToken(String refreshToken, String email, Instant expiresAt) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO auth_refresh_tokens(token_hash, email, issued_at, expires_at, revoked_at)
            VALUES (?, ?, now(), ?, NULL)
            ON CONFLICT (token_hash) DO UPDATE SET
              email = EXCLUDED.email,
              expires_at = EXCLUDED.expires_at,
              revoked_at = NULL
            """)) {
      statement.setString(1, tokenHash(refreshToken));
      statement.setString(2, email);
      statement.setTimestamp(3, Timestamp.from(expiresAt));
      statement.executeUpdate();
    } catch (Exception ignored) {
      // El fallback de refresh token en memoria sigue activo.
    }
  }

  public Optional<String> findEmailByRefreshToken(String refreshToken) {
    if (!enabled) return Optional.empty();
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT email FROM auth_refresh_tokens
            WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now()
            """)) {
      statement.setString(1, tokenHash(refreshToken));
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.ofNullable(rs.getString("email"));
      }
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  public void revokeRefreshToken(String refreshToken) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            UPDATE auth_refresh_tokens SET revoked_at = now() WHERE token_hash = ?
            """)) {
      statement.setString(1, tokenHash(refreshToken));
      statement.executeUpdate();
    } catch (Exception ignored) {
      // El logout es best-effort para el fallback de persistencia.
    }
  }

  /**
   * Chequeo estricto y fail-closed usado exclusivamente por la lógica de bootstrap de admin/bloqueo por activación: a diferencia de
   * cualquier otro método de esta clase, nunca traga una excepción — si Postgres no está habilitado o
   * la consulta falla, quien llama (AdminBootstrapService) debe tratarlo como "no se puede determinar con seguridad el
   * estado de ADMIN" y negarse a arrancar, en lugar de recurrir silenciosamente a una respuesta en memoria.
   */
  public boolean hasNonDemoAdmin(String excludedEmail) {
    return findNonDemoAdmin(excludedEmail).isPresent();
  }

  /**
   * Ver {@link #hasNonDemoAdmin(String)} — mismo contrato fail-closed. La cláusula `roles LIKE '%ADMIN%'`
   * es solo un prefiltro SQL amigable al índice (también coincidiría con un hipotético rol futuro
   * como "SUPERADMIN"); cada fila candidata se vuelve a chequear en Java contra la lista exacta de roles
   * separada por comas más verified/approved, de modo que solo una cuenta ADMIN genuinamente activa y exacta
   * se acepta para saltear el bootstrap.
   */
  public Optional<DoctorAccount> findNonDemoAdmin(String excludedEmail) {
    if (!enabled) {
      throw new IllegalStateException(
          "Postgres auth persistence is not enabled (pfi.persistence.mode != postgres)");
    }
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, approved, two_factor_enabled, onboarding_completed
            FROM doctor_accounts
            WHERE roles LIKE '%ADMIN%' AND verified = TRUE AND approved = TRUE AND email <> ?
            ORDER BY created_at ASC
            """)) {
      statement.setString(1, excludedEmail == null ? "" : excludedEmail);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          DoctorAccount account = readAccount(rs);
          if (account.verified() && account.approved() && account.roles().contains("ADMIN")) {
            return Optional.of(account);
          }
        }
        return Optional.empty();
      }
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Could not query for an existing production ADMIN account", ex);
    }
  }

  /**
   * Creación idempotente y a prueba de carreras del ADMIN de bootstrap. Estrategia de concurrencia: el INSERT se apoya
   * en el constraint UNIQUE(email) existente con ON CONFLICT DO NOTHING — si dos instancias arrancan
   * concurrentemente y ambas intentan crear el mismo email de bootstrap, exactamente un INSERT tiene éxito
   * y el otro queda como no-op en lugar de un error o una fila duplicada. En cualquier caso, quien llama
   * (AdminBootstrapService) siempre relee la fila después vía findByEmail(...) para obtener la
   * única identidad persistida canónica — así ambas instancias convergen en la misma cuenta sin
   * necesitar un lock distribuido.
   */
  public DoctorAccount createBootstrapAdmin(DoctorAccount account) {
    if (!enabled) {
      throw new IllegalStateException(
          "Postgres auth persistence is not enabled (pfi.persistence.mode != postgres)");
    }
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO doctor_accounts(id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, approved, two_factor_enabled, onboarding_completed, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (email) DO NOTHING
            """)) {
      statement.setString(1, account.id());
      statement.setString(2, account.fullName());
      statement.setString(3, account.email());
      statement.setString(4, account.passwordHash());
      statement.setString(5, account.licenseNumber());
      statement.setString(6, account.specialty());
      statement.setString(7, account.institution());
      statement.setString(8, String.join(",", account.roles()));
      statement.setTimestamp(9, Timestamp.from(account.createdAt()));
      statement.setBoolean(10, account.verified());
      statement.setBoolean(11, account.approved());
      statement.setBoolean(12, account.twoFactorEnabled());
      statement.setBoolean(13, account.onboardingCompleted());
      statement.executeUpdate();
    } catch (Exception ex) {
      throw new IllegalStateException("Could not persist the bootstrap ADMIN account", ex);
    }
    return findByEmailStrict(account.email());
  }

  /**
   * La misma lectura que findByEmail, pero lanza excepción en lugar de devolver vacío ante cualquier falla — se usa solo para
   * la verificación del bootstrap.
   */
  private DoctorAccount findByEmailStrict(String email) {
    if (!enabled) {
      throw new IllegalStateException(
          "Postgres auth persistence is not enabled (pfi.persistence.mode != postgres)");
    }
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, approved, two_factor_enabled, onboarding_completed
            FROM doctor_accounts
            WHERE email = ?
            """)) {
      statement.setString(1, email);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException(
              "Bootstrap ADMIN account was not found after persistence");
        }
        return readAccount(rs);
      }
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Could not verify the bootstrap ADMIN account was persisted", ex);
    }
  }

  /**
   * UPDATE único y atómico para la activación/desactivación institucional — verified, approved y roles
   * se escriben siempre juntos, de modo que una cuenta persistida nunca se observe en un estado a medio actualizar.
   * Fail-closed como los métodos de bootstrap de arriba: un error acá debe aflorar como una falla real para
   * quien llama, no como un no-op silencioso.
   */
  public ProfessionalActivationResult updateProfessionalActivation(
      String email, boolean verified, boolean approved, List<String> roles) {
    if (!enabled) {
      throw new IllegalStateException(
          "Postgres auth persistence is not enabled (pfi.persistence.mode != postgres)");
    }
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            UPDATE doctor_accounts
            SET verified = ?, approved = ?, roles = ?, updated_at = now()
            WHERE email = ?
            """)) {
      statement.setBoolean(1, verified);
      statement.setBoolean(2, approved);
      statement.setString(3, String.join(",", roles));
      statement.setString(4, email);
      int updated = statement.executeUpdate();
      if (updated == 0) {
        throw new IllegalStateException("No account was updated for the given email");
      }
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Could not persist professional activation state", ex);
    }
    return new ProfessionalActivationResult(email, verified, approved, roles);
  }

  public record ProfessionalActivationResult(
      String email, boolean verified, boolean approved, List<String> roles) {}

  /**
   * Lectura de cuenta fail-closed por request, usada por AuthAccountStateService para revalidar el estado persistido
   * de quien llama en cada request protegido. A diferencia de findByEmail (que traga errores y
   * devuelve vacío — válido para búsquedas de UI best-effort), esto lanza excepción ante cualquier falla de infraestructura; "la cuenta
   * realmente no existe" es el único caso que devuelve Optional.empty().
   */
  public Optional<DoctorAccount> findByEmailForAuthorization(String email) {
    if (!enabled) {
      throw new IllegalStateException(
          "Postgres auth persistence is not enabled (pfi.persistence.mode != postgres)");
    }
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, approved, two_factor_enabled, onboarding_completed
            FROM doctor_accounts
            WHERE email = ?
            """)) {
      statement.setString(1, email);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(readAccount(rs));
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not validate the current session's account state", ex);
    }
  }

  /**
   * Conteo fail-closed por rol exacto, usado exclusivamente para el chequeo de bloqueo del último ADMIN. Deliberadamente
   * NO se apoya solo en `roles LIKE '%ADMIN%'` — ese prefiltro SQL solo se usa para que la
   * consulta sea amigable al índice; la decisión real usa la lista exacta de roles separada por comas de
   * readAccount() (List.contains("ADMIN")), de modo que un hipotético rol futuro como "SUPERADMIN" nunca
   * se contabilice por error como "ADMIN". Solo cuentan las cuentas verified+approved+no demo con el rol
   * ADMIN exacto.
   */
  public long countActiveNonDemoAdminsExcluding(String excludedEmail) {
    if (!enabled) {
      throw new IllegalStateException(
          "Postgres auth persistence is not enabled (pfi.persistence.mode != postgres)");
    }
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, approved, two_factor_enabled, onboarding_completed
            FROM doctor_accounts
            WHERE roles LIKE '%ADMIN%' AND verified = TRUE AND approved = TRUE AND email <> ?
            """)) {
      statement.setString(1, excludedEmail == null ? "" : excludedEmail);
      try (ResultSet rs = statement.executeQuery()) {
        long count = 0;
        while (rs.next()) {
          DoctorAccount account = readAccount(rs);
          if (!AuthService.DEMO_ACCOUNT_EMAIL.equals(account.email())
              && account.roles().contains("ADMIN")) count++;
        }
        return count;
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not count active production ADMIN accounts", ex);
    }
  }

  /**
   * Desactivación transaccional: la fila de la cuenta y sus refresh tokens se actualizan dentro de una
   * misma conexión/transacción (autoCommit=false, commit explícito, rollback ante cualquier error) para que quien llame
   * nunca pueda observar "desactivada pero con sesiones todavía válidas" ni lo contrario — o se aplican
   * ambas escrituras, o ninguna. El cache en memoria de AuthService solo lo actualiza quien llama después de que
   * este método retorna con éxito.
   */
  public ProfessionalActivationResult deactivateProfessionalAndRevokeSessions(
      String email, boolean verified, List<String> roles) {
    if (!enabled) {
      throw new IllegalStateException(
          "Postgres auth persistence is not enabled (pfi.persistence.mode != postgres)");
    }
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                    UPDATE doctor_accounts
                    SET verified = ?, approved = FALSE, roles = ?, updated_at = now()
                    WHERE email = ?
                    """)) {
          statement.setBoolean(1, verified);
          statement.setString(2, String.join(",", roles));
          statement.setString(3, email);
          int updated = statement.executeUpdate();
          if (updated == 0) {
            throw new IllegalStateException("No account was updated for the given email");
          }
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                    UPDATE auth_refresh_tokens SET revoked_at = now() WHERE email = ? AND revoked_at IS NULL
                    """)) {
          statement.setString(1, email);
          statement.executeUpdate();
        }
        connection.commit();
      } catch (Exception ex) {
        connection.rollback();
        throw ex;
      }
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Could not deactivate the account and revoke its sessions", ex);
    }
    return new ProfessionalActivationResult(email, verified, false, roles);
  }

  /**
   * Revoca en bloque todos los refresh tokens todavía activos de un email — se usa para cerrar las
   * sesiones remanentes de la cuenta demo de un rollout anterior sin tocar los tokens de ninguna otra cuenta (sin
   * DELETE, sin wildcard, solo con WHERE de un único email).
   */
  /** A diferencia de las escrituras de configuración best-effort, un cambio de contraseña nunca debe perderse en silencio. */
  public void updatePasswordHash(String email, String passwordHash) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE doctor_accounts SET password_hash = ?, updated_at = now() WHERE email = ?")) {
      statement.setString(1, passwordHash);
      statement.setString(2, email);
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("Password hash row not updated");
      }
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Could not persist the new password", ex);
    }
  }

  public void revokeRefreshTokensForEmail(String email) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            UPDATE auth_refresh_tokens SET revoked_at = now() WHERE email = ? AND revoked_at IS NULL
            """)) {
      statement.setString(1, email);
      statement.executeUpdate();
    } catch (Exception ignored) {
      // Best-effort; el fallback en memoria de AuthService también limpia su propio mapa.
    }
  }

  // ---- Solicitudes de cambio de perfil -------------------------------------------------------------

  public Optional<ProfileChange> findProfileChange(String email) {
    if (!enabled) return Optional.empty();
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT email, status, note, token_hash, expires_at, prev_full_name, prev_license_number, prev_institution, requested_at, updated_at FROM doctor_profile_changes WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(readProfileChange(rs)) : Optional.empty();
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not read the profile change request", ex);
    }
  }

  public List<ProfileChange> listProfileChanges() {
    if (!enabled) return List.of();
    List<ProfileChange> items = new ArrayList<>();
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT email, status, note, token_hash, expires_at, prev_full_name, prev_license_number, prev_institution, requested_at, updated_at FROM doctor_profile_changes ORDER BY requested_at");
        ResultSet rs = statement.executeQuery()) {
      while (rs.next()) items.add(readProfileChange(rs));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not list the profile change requests", ex);
    }
    return items;
  }

  public void saveProfileChange(ProfileChange change) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO doctor_profile_changes(email, status, note, token_hash, expires_at, prev_full_name, prev_license_number, prev_institution, requested_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (email) DO UPDATE SET
              status = EXCLUDED.status,
              note = EXCLUDED.note,
              token_hash = EXCLUDED.token_hash,
              expires_at = EXCLUDED.expires_at,
              prev_full_name = EXCLUDED.prev_full_name,
              prev_license_number = EXCLUDED.prev_license_number,
              prev_institution = EXCLUDED.prev_institution,
              updated_at = now()
            """)) {
      statement.setString(1, change.email());
      statement.setString(2, change.status().name());
      statement.setString(3, change.note() == null ? "" : change.note());
      statement.setString(4, change.tokenHash());
      statement.setTimestamp(5, change.expiresAt() == null ? null : Timestamp.from(change.expiresAt()));
      statement.setString(6, change.previousFullName());
      statement.setString(7, change.previousLicenseNumber());
      statement.setString(8, change.previousInstitution());
      statement.setTimestamp(9, Timestamp.from(change.requestedAt()));
      statement.executeUpdate();
    } catch (Exception ex) {
      throw new IllegalStateException("Could not persist the profile change request", ex);
    }
  }

  public void deleteProfileChange(String email) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM doctor_profile_changes WHERE email = ?")) {
      statement.setString(1, email);
      statement.executeUpdate();
    } catch (Exception ex) {
      throw new IllegalStateException("Could not remove the profile change request", ex);
    }
  }

  public void updateProfileFields(String email, String fullName, String licenseNumber, String institution) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE doctor_accounts SET full_name = ?, license_number = ?, institution = ?, updated_at = now() WHERE email = ?")) {
      statement.setString(1, fullName);
      statement.setString(2, licenseNumber);
      statement.setString(3, institution);
      statement.setString(4, email);
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("Profile row not updated");
      }
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Could not persist the profile data", ex);
    }
  }

  /** Quita una cuenta que nunca llegó a verificarse (por ejemplo, si no se pudo enviar el código). */
  public void saveRegistrationRejection(String email, String reason, String rejectedBy, Instant rejectedAt) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO doctor_registration_rejections(email, reason, rejected_by, rejected_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (email) DO UPDATE SET
              reason = EXCLUDED.reason, rejected_by = EXCLUDED.rejected_by, rejected_at = EXCLUDED.rejected_at
            """)) {
      statement.setString(1, email);
      statement.setString(2, reason);
      statement.setString(3, rejectedBy == null ? "" : rejectedBy);
      statement.setTimestamp(4, Timestamp.from(rejectedAt));
      statement.executeUpdate();
    } catch (Exception ex) {
      throw new IllegalStateException("Could not persist the registration rejection", ex);
    }
  }

  public Optional<AuthService.RegistrationRejection> findRegistrationRejection(String email) {
    if (!enabled) return Optional.empty();
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT email, reason, rejected_by, rejected_at FROM doctor_registration_rejections WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(readRejection(rs)) : Optional.empty();
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not read the registration rejection", ex);
    }
  }

  public List<AuthService.RegistrationRejection> listRegistrationRejections() {
    if (!enabled) return List.of();
    List<AuthService.RegistrationRejection> items = new ArrayList<>();
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT email, reason, rejected_by, rejected_at FROM doctor_registration_rejections ORDER BY rejected_at DESC");
        ResultSet rs = statement.executeQuery()) {
      while (rs.next()) items.add(readRejection(rs));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not list the registration rejections", ex);
    }
    return items;
  }

  public void deleteRegistrationRejection(String email) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM doctor_registration_rejections WHERE email = ?")) {
      statement.setString(1, email);
      statement.executeUpdate();
    } catch (Exception ex) {
      throw new IllegalStateException("Could not remove the registration rejection", ex);
    }
  }

  private static AuthService.RegistrationRejection readRejection(ResultSet rs) throws Exception {
    Timestamp at = rs.getTimestamp("rejected_at");
    return new AuthService.RegistrationRejection(
        rs.getString("email"), rs.getString("reason"), rs.getString("rejected_by"), at == null ? Instant.now() : at.toInstant());
  }

  public void deleteUnverifiedAccount(String email) {
    if (!enabled) return;
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM doctor_accounts WHERE email = ? AND verified = FALSE")) {
      statement.setString(1, email);
      statement.executeUpdate();
    } catch (Exception ignored) {
      // Si no se pudo borrar, el registro queda sin verificar y se reintenta iniciando sesión.
    }
  }

  private ProfileChange readProfileChange(ResultSet rs) throws Exception {
    Timestamp expires = rs.getTimestamp("expires_at");
    return new ProfileChange(
        rs.getString("email"),
        ProfileChange.Status.valueOf(rs.getString("status")),
        rs.getString("note"),
        rs.getString("token_hash"),
        expires == null ? null : expires.toInstant(),
        rs.getString("prev_full_name"),
        rs.getString("prev_license_number"),
        rs.getString("prev_institution"),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private DoctorAccount readAccount(ResultSet rs) throws Exception {
    List<String> roles =
        Arrays.stream(rs.getString("roles").split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    boolean approved = rs.getBoolean("approved");
    return new DoctorAccount(
        rs.getString("id"),
        rs.getString("full_name"),
        rs.getString("email"),
        rs.getString("password_hash"),
        rs.getString("license_number"),
        rs.getString("specialty"),
        rs.getString("institution"),
        roles.isEmpty()
            ? (approved ? List.of("DOCTOR", "REVIEWER") : List.of("PENDING_APPROVAL"))
            : roles,
        rs.getTimestamp("created_at").toInstant(),
        rs.getBoolean("verified"),
        approved,
        rs.getBoolean("two_factor_enabled"),
        rs.getBoolean("onboarding_completed"));
  }

  private void migrate() {
    try (Connection connection = connection()) {
      connection
          .createStatement()
          .execute(
              """
                CREATE TABLE IF NOT EXISTS doctor_accounts (
                    id TEXT PRIMARY KEY,
                    full_name TEXT NOT NULL,
                    email TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    license_number TEXT NOT NULL DEFAULT '',
                    specialty TEXT NOT NULL DEFAULT '',
                    institution TEXT NOT NULL DEFAULT '',
                    roles TEXT NOT NULL DEFAULT 'PENDING_APPROVAL',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    verified BOOLEAN NOT NULL DEFAULT FALSE,
                    approved BOOLEAN NOT NULL DEFAULT FALSE,
                    two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
      connection
          .createStatement()
          .execute(
              "ALTER TABLE doctor_accounts ADD COLUMN IF NOT EXISTS approved BOOLEAN NOT NULL DEFAULT FALSE");
      connection
          .createStatement()
          .execute(
              "ALTER TABLE doctor_accounts ADD COLUMN IF NOT EXISTS two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE");
      connection
          .createStatement()
          .execute(
              "ALTER TABLE doctor_accounts ADD COLUMN IF NOT EXISTS onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE");
      connection
          .createStatement()
          .execute(
              "UPDATE doctor_accounts SET approved = TRUE WHERE verified = TRUE AND approved = FALSE AND roles NOT LIKE '%PENDING_APPROVAL%'");
      connection
          .createStatement()
          .execute(
              "UPDATE doctor_accounts SET roles = 'DOCTOR,REVIEWER' WHERE approved = TRUE AND roles = 'PENDING_APPROVAL'");
      connection
          .createStatement()
          .execute(
              """
                CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
                    token_hash TEXT PRIMARY KEY,
                    email TEXT NOT NULL REFERENCES doctor_accounts(email) ON DELETE CASCADE,
                    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    expires_at TIMESTAMPTZ NOT NULL,
                    revoked_at TIMESTAMPTZ NULL
                )
                """);
      connection
          .createStatement()
          .execute(
              """
                CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_email ON auth_refresh_tokens(email)
                """);
      connection
          .createStatement()
          .execute(
              """
                CREATE TABLE IF NOT EXISTS doctor_profile_changes (
                    email TEXT PRIMARY KEY REFERENCES doctor_accounts(email) ON DELETE CASCADE,
                    status TEXT NOT NULL,
                    note TEXT NOT NULL DEFAULT '',
                    token_hash TEXT NULL,
                    expires_at TIMESTAMPTZ NULL,
                    prev_full_name TEXT NULL,
                    prev_license_number TEXT NULL,
                    prev_institution TEXT NULL,
                    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
      connection
          .createStatement()
          .execute(
              """
                CREATE TABLE IF NOT EXISTS doctor_registration_rejections (
                    email TEXT PRIMARY KEY REFERENCES doctor_accounts(email) ON DELETE CASCADE,
                    reason TEXT NOT NULL,
                    rejected_by TEXT NOT NULL DEFAULT '',
                    rejected_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
    } catch (Exception ignored) {
      // El servicio sigue disponible con el fallback en memoria.
    }
  }

  private String tokenHash(String refreshToken) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
    StringBuilder builder = new StringBuilder();
    for (byte b : digest) builder.append(String.format("%02x", b));
    return builder.toString();
  }

  private Connection connection() throws Exception {
    return dataSource.getConnection();
  }

  private static String toJdbcUrl(String databaseUrl) {
    if (databaseUrl.isBlank()) return "";
    if (databaseUrl.startsWith("jdbc:postgresql://")) return databaseUrl;
    if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://"))
      return databaseUrl;
    try {
      URI uri = URI.create(databaseUrl);
      String userInfo = uri.getUserInfo();
      String user = "";
      String password = "";
      if (userInfo != null) {
        String[] parts = userInfo.split(":", 2);
        user = parts.length > 0 ? parts[0] : "";
        password = parts.length > 1 ? parts[1] : "";
      }
      String query = "sslmode=require";
      if (!user.isBlank()) query += "&user=" + URLEncoder.encode(user, StandardCharsets.UTF_8);
      if (!password.isBlank())
        query += "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
      int port = uri.getPort() > 0 ? uri.getPort() : 5432;
      return "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?" + query;
    } catch (Exception ex) {
      return databaseUrl;
    }
  }
}
