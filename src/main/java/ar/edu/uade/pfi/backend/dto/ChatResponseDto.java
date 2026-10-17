package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code humanReviewRequired}/{@code notClinicalDiagnosis} son siempre {@code true}: los fija {@code ChatService}, nunca
 * el proveedor. No expone infraestructura (URL del modelo, hosts, rutas).
 *
 * <p>{@code references} son las referencias citadas por la respuesta que se verificaron contra el contexto;
 * {@code removedReferences} cuenta las citas que no existían y se quitaron. {@code contextFingerprint} identifica los datos
 * con los que se respondió.
 */
public record ChatResponseDto(
    String answer,
    String conversationId,
    String messageId,
    String model,
    String provider,
    String promptVersion,
    Instant generatedAt,
    List<String> warnings,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis,
    List<String> contextSources,
    List<Reference> references,
    int removedReferences,
    String contextFingerprint,
    Scope scope,
    long elapsedMs,
    Integer promptTokens,
    Integer completionTokens
) {
    public record Reference(String ref, String label) {
    }

    /** Qué estudio/corrida o par respaldó la respuesta (ids técnicos, ya conocidos por el visor). */
    public record Scope(String kind, String runId, String referenceCaseId, String referenceRunId, boolean findingsComparable) {
    }
}
