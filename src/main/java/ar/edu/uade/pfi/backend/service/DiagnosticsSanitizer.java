package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.util.SafeLogSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sanitizador recursivo específico para las respuestas de diagnóstico del módulo de IA embebidas en {@code GET
 * /api/system/diagnostics} (P10-B.1 §5). A diferencia de {@link AuditService#sanitize}, esto es una blocklist,
 * no una allowlist estricta — las respuestas de health/readiness/models del upstream tienen muchos campos inocuos
 * (schemaVersion, key/versión de modelo, hash de artefacto, estado de calidad, ...) que deben sobrevivir
 * sin cambios para que la verificación v1/v2 siga funcionando; solo se
 * eliminan/redactan las claves y valores sensibles conocidos.
 */
public final class DiagnosticsSanitizer {
  /**
   * Coincidencias exactas de nombre de clave (sin distinguir mayúsculas), no por subcadena — "port"/"host"/ "file"/"path"
   * como *subcadenas* darían falsos positivos con claves inocuas como "reportCount" o
   * "profile" (detectado durante el desarrollo, ver DiagnosticsSanitizerTest).
   */
  private static final Set<String> SENSITIVE_KEYS =
      Set.of(
          "url",
          "baseurl",
          "path",
          "file",
          "filename",
          "localpath",
          "storagepath",
          "token",
          "secret",
          "credential",
          "authorization",
          "password",
          "host",
          "port");

  private static final int MAX_DEPTH = 6;
  private static final int MAX_LIST_ELEMENTS = 50;

  private DiagnosticsSanitizer() {}

  public static Map<String, Object> sanitize(Map<String, Object> raw) {
    return sanitize(raw, 0);
  }

  private static Map<String, Object> sanitize(Map<String, Object> raw, int depth) {
    if (raw == null || raw.isEmpty() || depth >= MAX_DEPTH) return Map.of();
    Map<String, Object> safe = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : raw.entrySet()) {
      String key = entry.getKey() == null ? "" : entry.getKey();
      if (isSensitiveKey(key)) continue;
      safe.put(key, sanitizeValue(entry.getValue(), depth));
    }
    return safe;
  }

  private static Object sanitizeValue(Object value, int depth) {
    if (value == null) return null;
    if (value instanceof Number || value instanceof Boolean) return value;
    if (depth >= MAX_DEPTH) return SafeLogSanitizer.REDACTED;
    if (value instanceof Map<?, ?> nested) {
      Map<String, Object> typed = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : nested.entrySet()) {
        typed.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      return sanitize(typed, depth + 1);
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>();
      int count = 0;
      for (Object item : list) {
        if (count++ >= MAX_LIST_ELEMENTS) break;
        result.add(sanitizeValue(item, depth + 1));
      }
      return result;
    }
    String text = String.valueOf(value);
    return SafeLogSanitizer.isSensitive(text) ? SafeLogSanitizer.REDACTED : text;
  }

  private static boolean isSensitiveKey(String key) {
    return SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT));
  }
}
