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
    Integer completionTokens,
    boolean truncated,
    /** {@code structured}: respuesta armada por el servidor con plantillas sobre datos registrados; {@code free}: texto libre del modelo (modo anterior). */
    String mode,
    /** Intención soportada que se respondió (modo acotado); {@code null} en el modo libre. */
    String intent,
    /** Cuántos elementos se muestran y cuántos hay en el ámbito pedido (modo acotado). */
    Integer shownCount,
    Integer totalCount,
    /** La respuesta está respaldada por datos registrados (false si la consulta quedó fuera del alcance). */
    boolean answerSupported
) {
    public record Reference(String ref, String label) {
    }

    /** Qué estudio/corrida o par respaldó la respuesta (ids técnicos, ya conocidos por el visor). */
    public record Scope(String kind, String runId, String referenceCaseId, String referenceRunId, boolean findingsComparable) {
    }
}
