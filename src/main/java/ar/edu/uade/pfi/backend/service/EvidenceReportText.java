package ar.edu.uade.pfi.backend.service;

import java.util.Map;

/**
 * EXT-04B4: exactamente las mismas cuatro etiquetas neutrales que el Frontend ya renderiza
 * ({@code visualEvidenceDisplay.ts#displayEvidenceSource}) — mantenidas en sincronía deliberadamente, nunca
 * "patología confirmada"/"lesión confirmada"/"diagnóstico". Duplicadas acá (no importadas —
 * es una base de código Java/TypeScript separada) en lugar de dejarlas derivar o reinventarlas con
 * otra redacción.
 */
final class EvidenceReportText {
    private static final Map<String, String> SOURCE_LABELS = Map.of(
        "ai_suggested", "Región sugerida por IA",
        "ai_validated", "Región revisada por profesional",
        "professional_corrected", "Región corregida por profesional",
        "professional_drawn", "Región creada por profesional"
    );

    static final String EXPERIMENTAL_BADGE = "Experimental";
    static final String NO_BASE_IMAGE_MESSAGE = "Imagen base no disponible.";
    static final String NO_VISUAL_EVIDENCE_MESSAGE = "No hay evidencia visual disponible para este análisis.";
    static final String BEFORE_AFTER_TITLE = "Comparación con estudio previo";
    static final String NO_BEFORE_AFTER_MESSAGE = "No existen estudios previos comparables disponibles.";

    private EvidenceReportText() {
    }

    static String displaySource(String source) {
        return SOURCE_LABELS.getOrDefault(source, source);
    }

    static String displayPlane(String plane) {
        if ("sagittal".equals(plane)) return "Sagital";
        if ("axial".equals(plane)) return "Axial";
        return "No informado";
    }

    static String displayReviewStatus(String status) {
        if (status == null || "pending".equals(status)) return "revisión: pendiente";
        if ("accepted".equals(status)) return "revisión: aceptada";
        if ("observed".equals(status)) return "revisión: observada";
        if ("rejected".equals(status)) return "revisión: rechazada";
        return "revisión: " + status;
    }
}
