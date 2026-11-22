package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Una ejecución persistida de {@code POST /v1/product/analyze} (pfi.ai-product-result.v1) contra
 * un Study. {@code status} es el valor propio {@code completed}/{@code partial}/{@code failed} del módulo de IA,
 * persistido textualmente — nunca reinterpretado (etapa 76-D).
 *
 * <p>No se modela ningún estado de ciclo de vida de ejecución separado (PENDING/RUNNING): la llamada a la IA es
 * sincrónica y una fila solo se crea una vez que la llamada ya devolvió un
 * resultado validado (etapa 76-K/76-L) — introducir un enum de ciclo de vida para un estado que nadie observa
 * sería complejidad sin un consumidor.
 *
 * <p>{@code sagittalInputId}/{@code axialInputId} son los identificadores de input opacos propios del Backend
 * (ya usados en otros lugares para los uploads de {@code /inputs}) — nunca un path del filesystem.
 * {@code rawResponseSanitized} es solo para trazabilidad opcional (etapa 76-H); las filas estructuradas
 * ({@link AiDiscLevelResult}, {@link AiMeasurementResult}) son siempre la fuente primaria de verdad.
 */
public record AiProductAnalysisRun(
    String id,
    String studyId,
    String caseId,
    String schemaVersion,
    String releaseId,
    String status,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis,
    boolean autonomousDiagnosis,
    String traceId,
    String sagittalInputId,
    String axialInputId,
    List<String> warnings,
    Map<String, Object> rawResponseSanitized,
    Instant createdAt,
    Instant completedAt
) {
}
