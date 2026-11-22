package ar.edu.uade.pfi.backend.auth;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Revalida el estado persistido de la cuenta de un request una vez superados los chequeos de firma/expiración del JWT, para que un
 * access token viejo pero aún vigente no siga otorgando privilegios que una cuenta desactivada o con roles modificados
 * ya no tiene. Separado de AuthFilter para que el SQL viva en PostgresAuthStoreService y no en
 * el filtro de servlet.
 *
 * <p>Fuera de producción, los claims del propio JWT se confían directamente (sin consulta a Postgres) — esto
 * preserva todos los flujos dev/test existentes, incluido el gran conjunto de tests que generan tokens para
 * cuentas que nunca se persistieron. En producción, Postgres es la única autoridad: cualquier
 * imposibilidad de confirmar el estado actual de la cuenta (persistencia deshabilitada, falla de conexión, falla de
 * consulta) falla de forma cerrada como STATE_UNAVAILABLE en lugar de recurrir a los claims del token.
 */
@Component
public class AuthAccountStateService {
  public enum Status {
    OK,
    ACCOUNT_NOT_FOUND,
    DEMO_BLOCKED,
    STATE_UNAVAILABLE
  }

  /**
   * claims() ya trae los roles efectivos para este request (PENDING_APPROVAL siempre que
   * verified/approved sea false, sin importar qué roles conserve la fila persistida) —
   * verified/approved se mantienen aparte para quienes necesitan el estado crudo y autoritativo
   * de la cuenta, no solo sus roles efectivos.
   */
  public record Resolution(
      Status status, TokenService.Claims claims, boolean verified, boolean approved) {
    static Resolution ok(TokenService.Claims claims, boolean verified, boolean approved) {
      return new Resolution(Status.OK, claims, verified, approved);
    }

    static Resolution of(Status status) {
      return new Resolution(status, null, false, false);
    }
  }

  private final PostgresAuthStoreService postgresAuthStore;
  private final Environment environment;
  private ProfileChangeRegistry profileChanges;

  public AuthAccountStateService(
      PostgresAuthStoreService postgresAuthStore, Environment environment) {
    this.postgresAuthStore = postgresAuthStore;
    this.environment = environment;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setProfileChanges(ProfileChangeRegistry profileChanges) {
    this.profileChanges = profileChanges;
  }

  /**
   * Mientras los cambios de perfil de un profesional están en revisión, su cuenta funciona en solo
   * lectura. Se deriva acá, en cada request, para que aprobar o rechazar tenga efecto inmediato sin
   * esperar a que venza el token de acceso.
   */
  private TokenService.Claims withReadOnlyFlag(TokenService.Claims claims) {
    List<String> roles = claims.roles() == null ? List.of() : claims.roles();
    List<String> base = roles.stream().filter(role -> !"READ_ONLY".equals(role)).toList();
    boolean professional = base.contains("DOCTOR") || base.contains("REVIEWER");
    boolean pending =
        profileChanges != null && professional && !base.contains("ADMIN") && profileChanges.isReviewPending(claims.email());
    if (!pending && base.size() == roles.size()) return claims;
    List<String> effective = new java.util.ArrayList<>(base);
    if (pending) effective.add("READ_ONLY");
    return new TokenService.Claims(claims.subject(), claims.email(), claims.name(), effective);
  }

  public Resolution resolve(TokenService.Claims tokenClaims) {
    if (!isProductionProfile()) {
      return Resolution.ok(withReadOnlyFlag(tokenClaims), true, true);
    }
    if (!postgresAuthStore.enabled()) {
      return Resolution.of(Status.STATE_UNAVAILABLE);
    }
    String email = normalize(tokenClaims.email());
    Optional<DoctorAccount> accountOpt;
    try {
      accountOpt = postgresAuthStore.findByEmailForAuthorization(email);
    } catch (RuntimeException ex) {
      return Resolution.of(Status.STATE_UNAVAILABLE);
    }
    if (accountOpt.isEmpty()) {
      return Resolution.of(Status.ACCOUNT_NOT_FOUND);
    }
    DoctorAccount account = accountOpt.get();
    if (!account.id().equals(tokenClaims.subject())) {
      return Resolution.of(Status.ACCOUNT_NOT_FOUND);
    }
    if (AuthService.DEMO_ACCOUNT_EMAIL.equals(account.email())) {
      return Resolution.of(Status.DEMO_BLOCKED);
    }
    boolean active = account.verified() && account.approved();
    List<String> effectiveRoles = active ? account.roles() : List.of("PENDING_APPROVAL");
    TokenService.Claims effectiveClaims =
        new TokenService.Claims(account.id(), account.email(), account.fullName(), effectiveRoles);
    return Resolution.ok(active ? withReadOnlyFlag(effectiveClaims) : effectiveClaims, account.verified(), account.approved());
  }

  private String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  private boolean isProductionProfile() {
    for (String profile : environment.getActiveProfiles()) {
      String normalized = profile.toLowerCase(Locale.ROOT);
      if (normalized.equals("production") || normalized.equals("prod")) return true;
    }
    return false;
  }
}
