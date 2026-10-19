package ar.edu.uade.pfi.backend.service;

import java.util.List;
import java.util.Map;

/**
 * Lo que el asistente recibe: un texto de datos estructurados y legibles (no imágenes), armado en el servidor a partir
 * de la corrida exacta del visor, más el índice de elementos citables.
 *
 * <p>{@code text} es lo único que viaja al proveedor del modelo. No contiene nombre, apellido, fecha de nacimiento,
 * código de paciente, código de estudio ni identificadores de corrida: A/B, M1, F1, D1, H1 son etiquetas locales de este
 * contexto. Los identificadores técnicos (caso, corridas) quedan del lado del servidor, en {@code scope}.
 *
 * <p>{@code elements} mapea cada referencia citable ({@code A.M3}, {@code B.F12}, {@code D4}, {@code H7}…) a una
 * descripción corta legible. El servidor verifica con él que lo que la respuesta cita existe.
 */
public record AssistantContext(
    Scope scope,
    String text,
    String fingerprint,
    Map<String, String> elements,
    List<String> sources,
    List<String> missing,
    /** Los mismos datos del texto, en forma estructurada: de acá salen las respuestas del modo acotado. */
    List<AssistantFact> facts
) {
    public static final String KIND_STUDY = "study";
    public static final String KIND_PAIR = "pair";

    /** Estudio/corrida o par que respalda el contexto. Los ids son técnicos y no se envían al modelo. */
    public record Scope(
        String kind,
        String key,
        String studyId,
        String caseId,
        String runId,
        String referenceStudyId,
        String referenceCaseId,
        String referenceRunId,
        boolean findingsComparable
    ) {
    }
}
