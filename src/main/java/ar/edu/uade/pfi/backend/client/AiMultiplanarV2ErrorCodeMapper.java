package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.web.error.ApiErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Mapea los códigos de error estructurados de pfi.multiplanar-run.v2 (AiStructuredErrorV2Dto.code) a un par estable
 * de estado HTTP del backend + {@link ApiErrorCode}. Los códigos desconocidos nunca se exponen tal cual:
 * se colapsan en un bucket genérico de error upstream para que el contrato público de errores del backend siga estable
 * aunque el módulo de IA agregue códigos nuevos. El string del código upstream nunca se usa para construir el
 * código ni el mensaje público del backend — solo se usa esta tabla de mapeo fija (P10-B.1 §3/§7).
 */
final class AiMultiplanarV2ErrorCodeMapper {
  record Mapped(HttpStatus status, ApiErrorCode backendCode) {
    String publicMessage() {
      return backendCode.publicMessage();
    }
  }

  private static final Map<String, Mapped> KNOWN_CODES =
      Map.ofEntries(
          Map.entry(
              "INVALID_MULTIPLANAR_REQUEST",
              new Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_INVALID_REQUEST)),
          Map.entry(
              "NO_PLANE_REQUESTED",
              new Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_NO_PLANE_REQUESTED)),
          Map.entry(
              "INPUT_NOT_FOUND", new Mapped(HttpStatus.NOT_FOUND, ApiErrorCode.AI_INPUT_NOT_FOUND)),
          Map.entry(
              "MODEL_NOT_FOUND", new Mapped(HttpStatus.NOT_FOUND, ApiErrorCode.AI_MODEL_NOT_FOUND)),
          Map.entry(
              "MODEL_PLANE_MISMATCH",
              new Mapped(HttpStatus.CONFLICT, ApiErrorCode.AI_MODEL_PLANE_MISMATCH)),
          Map.entry(
              "MODEL_NOT_READY", new Mapped(HttpStatus.CONFLICT, ApiErrorCode.AI_MODEL_NOT_READY)),
          Map.entry(
              "CONTRACT_FALLBACK_DISABLED",
              new Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_CONTRACT_FALLBACK_DISABLED)),
          Map.entry(
              "REAL_INFERENCE_FAILED",
              new Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_REAL_INFERENCE_FAILED)),
          Map.entry(
              "INVALID_MULTIPLANAR_RESPONSE",
              new Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_INVALID_RESPONSE)),
          Map.entry(
              "UNSUPPORTED_INFERENCE_MODE",
              new Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_UNSUPPORTED_INFERENCE_MODE)));

  static final Mapped UNKNOWN = new Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_MODULE_ERROR);
  static final Mapped TIMEOUT =
      new Mapped(HttpStatus.GATEWAY_TIMEOUT, ApiErrorCode.AI_MODULE_TIMEOUT);

  private AiMultiplanarV2ErrorCodeMapper() {}

  static Mapped resolve(String upstreamCode) {
    if (upstreamCode == null) return UNKNOWN;
    Mapped mapped = KNOWN_CODES.get(upstreamCode.trim());
    return mapped == null ? UNKNOWN : mapped;
  }
}
