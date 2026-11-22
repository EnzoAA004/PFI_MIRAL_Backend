package ar.edu.uade.pfi.backend.service;

/**
 * Se lanza cuando un Study no tiene un input utilizable (analizable) para alguno de los planos que necesita
 * {@code POST /v1/product/analyze}. Nunca se infiere de un path de respaldo ni se fabrica —
 * si se lanza esto, el Study realmente no tiene nada que una corrida de IA pueda usar.
 */
public class AiStudyInputUnresolvedException extends RuntimeException {
    public AiStudyInputUnresolvedException(String caseId) {
        super("No analyzable sagittal or axial input found for study: " + caseId);
    }
}
