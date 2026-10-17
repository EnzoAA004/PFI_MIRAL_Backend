package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Vista del preinforme de una corrida: lo que el sistema genera hoy desde las fuentes ({@code generated}, con su
 * {@code fingerprint}) y el borrador guardado, si existe. {@code stale} = el borrador se creó con fuentes distintas a las
 * actuales; {@code changes} describe qué cambió. Nada se actualiza solo.
 */
public record PreReportViewDto(
    Map<String, Object> generated,
    String fingerprint,
    Instant generatedAt,
    boolean usable,
    DraftDto draft,
    boolean stale,
    List<Map<String, Object>> changes,
    List<String> orphanedEdits
) {
    /** {@code generated} es lo que se generó al crear o actualizar explícitamente el borrador; {@code edits}, lo del profesional. */
    public record DraftDto(
        String id,
        int version,
        String sourceFingerprint,
        Map<String, Object> generated,
        Map<String, Object> edits,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy
    ) {
    }
}
