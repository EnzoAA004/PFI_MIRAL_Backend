package ar.edu.uade.pfi.backend.service;

/**
 * Thrown when a Study has no usable (analyzable) input for either plane needed by
 * {@code POST /v1/product/analyze}. Never inferred from a fallback path or fabricated —
 * if this is thrown, the Study genuinely has nothing an AI run could use.
 */
public class AiStudyInputUnresolvedException extends RuntimeException {
    public AiStudyInputUnresolvedException(String caseId) {
        super("No analyzable sagittal or axial input found for study: " + caseId);
    }
}
