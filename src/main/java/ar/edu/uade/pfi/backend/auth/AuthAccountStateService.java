package ar.edu.uade.pfi.backend.auth;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Revalidates a request's persisted account state after JWT signature/expiry checks pass, so a
 * stale-but-unexpired access token cannot keep granting privileges a deactivated/role-changed
 * account no longer has. Split out of AuthFilter so the SQL lives in PostgresAuthStoreService, not
 * the servlet filter.
 *
 * <p>Outside production, the JWT's own claims are trusted directly (no Postgres round trip) — this
 * preserves every existing dev/test flow, including the large body of tests that mint tokens for
 * accounts that were never persisted anywhere. In production, Postgres is the sole authority: any
 * inability to confirm the account's current state (disabled persistence, connection failure, query
 * failure) fails closed as STATE_UNAVAILABLE rather than falling back to the token's claims.
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
   * claims() already carries the effective roles for this request (PENDING_APPROVAL whenever
   * verified/approved is false, regardless of whatever roles the persisted row still carries) —
   * verified/approved are kept alongside for callers that need the account's raw authoritative
   * state, not just its effective roles.
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
