package ar.edu.uade.pfi.backend.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private final ObjectMapper objectMapper;
  private final String signingKey;
  private final long accessTokenSeconds;

  public TokenService(
      ObjectMapper objectMapper,
      @Value("${pfi.auth.jwt-secret:pfi-demo-change-me-2026}") String signingKey,
      @Value("${pfi.auth.access-token-seconds:3600}") long accessTokenSeconds) {
    this.objectMapper = objectMapper;
    this.signingKey = signingKey;
    this.accessTokenSeconds = accessTokenSeconds;
  }

  public long accessTokenSeconds() {
    return accessTokenSeconds;
  }

  /**
   * Embebe los {@code roles()} crudos de la cuenta tal cual — ver el overload de abajo para el camino de producción de
   * login/refresh/verify, que debe embeber roles *efectivos*.
   */
  public String issueAccessToken(DoctorAccount account) {
    return issueAccessToken(account, account.roles());
  }

  /**
   * P10-B.2: permite a quien llama generar un token con una lista explícita de roles, distinta de lo que
   * contenga actualmente {@code account.roles()}. {@code AuthService.issueTokens} lo usa para embeber los roles
   * *efectivos* de la cuenta (PENDING_APPROVAL siempre que la cuenta no esté verified+approved)
   * en lugar de sus roles persistidos crudos — así el próximo login/refresh de una cuenta desactivada nunca genera
   * un token con sus antiguos roles profesionales, aunque esos roles ahora se preserven correctamente
   * (no se borren) en el almacenamiento para cuando la cuenta se reactive.
   */
  public String issueAccessToken(DoctorAccount account, List<String> roles) {
    Instant now = Instant.now();
    Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sub", account.id());
    payload.put("email", account.email());
    payload.put("name", account.fullName());
    payload.put("roles", roles);
    payload.put("iat", now.getEpochSecond());
    payload.put("exp", now.plusSeconds(accessTokenSeconds).getEpochSecond());
    String encodedHeader = base64Json(header);
    String encodedPayload = base64Json(payload);
    String signature = sign(encodedHeader + "." + encodedPayload);
    return encodedHeader + "." + encodedPayload + "." + signature;
  }

  /**
   * La firma siempre se recalcula con nuestra propia clave HMAC-SHA256 y se compara en tiempo constante;
   * el header "alg" del propio token nunca se confía ni se usa para ramificar, de modo que los ataques de confusión de algoritmo
   * (p. ej. un token falsificado con "alg":"none") no son posibles sin importar lo que declare el header.
   */
  public Claims verify(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
        return null;
      }
      String expectedSignature = sign(parts[0] + "." + parts[1]);
      if (!constantTimeEquals(expectedSignature, parts[2])) {
        return null;
      }
      String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {});
      Number exp = (Number) payload.get("exp");
      if (exp == null || exp.longValue() < Instant.now().getEpochSecond()) {
        return null;
      }
      String subject = payload.getOrDefault("sub", "").toString().trim();
      if (subject.isBlank()) {
        return null;
      }
      Object rolesValue = payload.get("roles");
      // Las authorities se toman únicamente de este payload firmado; un cliente nunca puede
      // inyectar roles por fuera de banda (p. ej. vía un header) porque AuthFilter/RoleAuthorizationService
      // solo leen TokenService.Claims. Un token sin "roles" o con "roles" malformado no recibe
      // ninguna authority en lugar de un rol privilegiado por defecto.
      List<String> roles =
          rolesValue instanceof List<?> list
              ? list.stream().map(Object::toString).toList()
              : List.of();
      return new Claims(
          subject,
          payload.getOrDefault("email", "").toString(),
          payload.getOrDefault("name", "").toString(),
          roles);
    } catch (Exception ex) {
      return null;
    }
  }

  private String base64Json(Map<String, Object> value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(objectMapper.writeValueAsBytes(value));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not encode token", ex);
    }
  }

  private String sign(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not sign token", ex);
    }
  }

  private boolean constantTimeEquals(String expected, String actual) {
    byte[] left = expected.getBytes(StandardCharsets.UTF_8);
    byte[] right = actual.getBytes(StandardCharsets.UTF_8);
    if (left.length != right.length) {
      return false;
    }
    int diff = 0;
    for (int i = 0; i < left.length; i++) {
      diff |= left[i] ^ right[i];
    }
    return diff == 0;
  }

  public record Claims(String subject, String email, String name, List<String> roles) {}
}
