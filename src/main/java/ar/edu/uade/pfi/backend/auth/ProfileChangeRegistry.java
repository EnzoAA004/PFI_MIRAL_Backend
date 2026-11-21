package ar.edu.uade.pfi.backend.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fuente única del estado de cambios de perfil. La comparten {@link AuthService} (que lo modifica) y
 * {@link AuthAccountStateService} (que lo consulta en cada request para aplicar el modo solo lectura).
 * Escribe primero en la base y recién después en memoria: si la base falla, nada queda a medias.
 */
@Component
public class ProfileChangeRegistry {
  private final Map<String, ProfileChange> byEmail = new ConcurrentHashMap<>();
  private final PostgresAuthStoreService store;

  public ProfileChangeRegistry(PostgresAuthStoreService store) {
    this.store = store;
  }

  private static String key(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  public Optional<ProfileChange> find(String email) {
    String k = key(email);
    ProfileChange cached = byEmail.get(k);
    if (cached != null) return Optional.of(cached);
    try {
      Optional<ProfileChange> persisted = store.findProfileChange(k);
      persisted.ifPresent(change -> byEmail.put(k, change));
      return persisted;
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  public void save(ProfileChange change) {
    store.saveProfileChange(change);
    byEmail.put(key(change.email()), change);
  }

  public void remove(String email) {
    store.deleteProfileChange(key(email));
    byEmail.remove(key(email));
  }

  public List<ProfileChange> list() {
    Map<String, ProfileChange> merged = new ConcurrentHashMap<>();
    try {
      for (ProfileChange change : store.listProfileChanges()) merged.put(key(change.email()), change);
    } catch (RuntimeException ignored) {
      // Se usa lo que haya en memoria.
    }
    merged.putAll(byEmail);
    byEmail.putAll(merged);
    return new ArrayList<>(merged.values());
  }

  public boolean isReviewPending(String email) {
    return find(email).map(change -> change.status() == ProfileChange.Status.PENDING_REVIEW).orElse(false);
  }
}
