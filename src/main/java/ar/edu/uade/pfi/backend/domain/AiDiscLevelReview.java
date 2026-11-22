package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Estado de revisión profesional para un {@link AiDiscLevelResult} — etapa 78-C, la capa granular
 * mínima defendible más allá de la revisión a nivel de corrida. Una fila por resultado de nivel de disco (upsert).
 *
 * <p>Esto NUNCA cambia los campos propios de {@link AiDiscLevelResult} (level, localizationStatus,
 * axialPairingValidated, degenerativeFindingsStatus, ...) — esos siguen siendo la salida original e
 * inmutable de la IA. Aceptar acá un emparejamiento 67C no setea ni puede setear
 * {@code axialPairingValidated=true}; esa es una decisión de revisión profesional que se apoya sobre el
 * estado de validación propio del método de IA, y nunca lo reemplaza.
 */
public record AiDiscLevelReview(
    String id,
    String discLevelResultId,
    String reviewStatus,
    String reviewComment,
    String reviewedBy,
    Instant reviewedAt,
    Instant updatedAt
) {
}
