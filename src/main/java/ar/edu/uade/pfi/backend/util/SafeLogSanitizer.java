package ar.edu.uade.pfi.backend.util;

import java.util.regex.Pattern;

/**
 * Utilidad de redacción sin estado compartida por el código de logging, auditoría y respuestas de error. Se exponen
 * deliberadamente dos granularidades distintas:
 *
 * <ul>
 *   <li>{@link #redactValue(String)} — semántica de valor completo: si el valor como un todo parece
 *       sensible (un bearer token, un JWT, un string con aspecto de contraseña, un path, un email, ...), el
 *       valor entero pasa a ser {@code "[redacted]"}. Usar para un único campo discreto — el valor de un
 *       header, el valor de metadata de auditoría, un claim.
 *   <li>{@link #sanitizeMessage(String)} — redacción a nivel de subcadena dentro de un mensaje de
 *       texto libre más largo (un mensaje de excepción, una línea de log): las subcadenas sensibles conocidas se
 *       reemplazan individualmente por {@code "[redacted]"}, el resto del mensaje se preserva. Usar para líneas de
 *       log donde perder el mensaje entero por un único token coincidente destruiría el valor
 *       diagnóstico.
 * </ul>
 *
 * Ambos siempre acotan la longitud del resultado. Ninguno sustituye a no loguear datos sensibles en primer
 * lugar — esto es defensa en profundidad, no el control principal.
 */
public final class SafeLogSanitizer {
  public static final String REDACTED = "[redacted]";
  private static final int MAX_LENGTH = 200;

  private static final Pattern BEARER_TOKEN =
      Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern JWT =
      Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*\\b");

  /**
   * Cualquier URL jdbc:, con credenciales o sin ellas — el driver/host/puerto/nombre de base de datos son en sí información
   * de infra.
   */
  private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[a-z0-9]+://[^\\s\"']*");

  private static final Pattern URL_WITH_USERINFO =
      Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"'/@]+:[^\\s\"'/@]+@[^\\s\"']*");

  /**
   * Cualquier URL http(s), pública o privada, lo importante es que una URL nunca debe llegar textual a un
   * log/body/auditoría.
   */
  private static final Pattern HTTP_URL = Pattern.compile("(?i)\\bhttps?://[^\\s\"']+");

  private static final Pattern LOCAL_OR_INTERNAL_HOST =
      Pattern.compile(
          "(?i)\\b(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|host\\.docker\\.internal|[a-z0-9.-]*\\.trycloudflare\\.com|[a-z0-9.-]+\\.internal)\\b(?::\\d+)?");

  /** Rangos IPv4 privados de RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16. */
  private static final Pattern PRIVATE_IPV4 =
      Pattern.compile(
          "\\b(?:10(?:\\.\\d{1,3}){3}|172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}|192\\.168(?:\\.\\d{1,3}){2})(?::\\d+)?\\b");

  private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\s\"']*");
  private static final Pattern TMP_OR_APP_PATH =
      Pattern.compile("(?<![\\w./])(?:/tmp|/app)(?:/[^\\s\"']*)?");

  /**
   * Directorios comunes del sistema Linux, cuando parecen un path interno absoluto (al menos un
   * segmento más).
   */
  private static final Pattern LINUX_INTERNAL_PATH =
      Pattern.compile("(?<![\\w./])(?:/var|/opt|/home|/etc|/usr|/srv|/data|/mnt|/root)/[^\\s\"']+");

  private static final Pattern QUERY_STRING =
      Pattern.compile("\\?[A-Za-z0-9_.\\-]+=[^\\s\"']*(?:&[A-Za-z0-9_.\\-]+=[^\\s\"']*)*");
  private static final Pattern EMAIL =
      Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
  private static final Pattern KEY_VALUE_SECRET =
      Pattern.compile(
          "(?i)\\b(password|passwd|secret|token|credential|authorization|apikey|api_key)\\b\\s*[:=]\\s*[^\\s,;\"'&]+");
  private static final Pattern MEDICAL_FILENAME =
      Pattern.compile("(?i)\\b[\\w.\\-]+\\.(?:dcm|dicom|nii|nii\\.gz|png|jpe?g|tiff?)\\b");

  private static final Pattern[] WHOLE_VALUE_SENSITIVE_PATTERNS = {
    BEARER_TOKEN,
    JWT,
    JDBC_URL,
    URL_WITH_USERINFO,
    HTTP_URL,
    LOCAL_OR_INTERNAL_HOST,
    PRIVATE_IPV4,
    WINDOWS_PATH,
    TMP_OR_APP_PATH,
    LINUX_INTERNAL_PATH,
    KEY_VALUE_SECRET,
    EMAIL,
    MEDICAL_FILENAME
  };

  private SafeLogSanitizer() {}

  /** True si el valor crudo, tomado como un todo, coincide con algún patrón sensible conocido. */
  public static boolean isSensitive(String value) {
    if (value == null || value.isBlank()) return false;
    for (Pattern pattern : WHOLE_VALUE_SENSITIVE_PATTERNS) {
      if (pattern.matcher(value).find()) return true;
    }
    return looksLikeBinary(value);
  }

  /** Redacción de valor completo: sensible → "[redacted]"; de lo contrario el original (con tope de longitud). */
  public static String redactValue(String value) {
    if (value == null) return null;
    if (isSensitive(value)) return REDACTED;
    return cap(value);
  }

  /** Redacción a nivel de subcadena para mensajes de texto libre/líneas de log más largas. */
  public static String sanitizeMessage(String message) {
    if (message == null || message.isBlank()) return message;
    String result = message;
    result = BEARER_TOKEN.matcher(result).replaceAll(REDACTED);
    result = JWT.matcher(result).replaceAll(REDACTED);
    result = JDBC_URL.matcher(result).replaceAll(REDACTED);
    result = URL_WITH_USERINFO.matcher(result).replaceAll(REDACTED);
    result = HTTP_URL.matcher(result).replaceAll(REDACTED);
    result = LOCAL_OR_INTERNAL_HOST.matcher(result).replaceAll(REDACTED);
    result = PRIVATE_IPV4.matcher(result).replaceAll(REDACTED);
    result = KEY_VALUE_SECRET.matcher(result).replaceAll(REDACTED);
    result = WINDOWS_PATH.matcher(result).replaceAll(REDACTED);
    result = TMP_OR_APP_PATH.matcher(result).replaceAll(REDACTED);
    result = LINUX_INTERNAL_PATH.matcher(result).replaceAll(REDACTED);
    result = QUERY_STRING.matcher(result).replaceAll("");
    result = EMAIL.matcher(result).replaceAll(REDACTED);
    result = MEDICAL_FILENAME.matcher(result).replaceAll(REDACTED);
    return cap(result);
  }

  /**
   * Heurística best-effort: una larga secuencia de caracteres no imprimibles/de alta entropía se trata como contenido
   * binario.
   */
  private static boolean looksLikeBinary(String value) {
    if (value.length() < 24) return false;
    int nonPrintable = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') nonPrintable++;
    }
    return nonPrintable > 0 && nonPrintable >= value.length() / 8;
  }

  private static String cap(String value) {
    return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) + "..." : value;
  }
}
