package ar.edu.uade.pfi.backend.domain;

/**
 * EXT-04B2: guardas que evitan que una capacidad de IA aún no validada se "lave" hasta convertirse en un
 * {@link AiEvidenceRegion} que parezca tan confiable como una validada. Ambas condiciones provienen
 * directamente de la auditoría del repo de IA de EXT-04B1 — ninguna se inventó acá.
 */
public final class EvidenceIngestionGuard {
    /** {@code AiProductAnalyzeResponseDto.DegenerativeFindings#status()} cuando P10.7 está bloqueado. */
    public static final String P10_7_BLOCKED_STATUS = "blocked_preprocessing_parity";

    private EvidenceIngestionGuard() {
    }

    /**
     * P10.7 (inferencia automática de patología) está bloqueado por la paridad de preprocesamiento — según el
     * javadoc propio de {@code AiProductAnalyzeResponseDto.DegenerativeFindings}, su status "is
     * currently always {@code blocked_preprocessing_parity} — never a negative finding". Una
     * capacidad bloqueada no tiene geometría localizada validada para ofrecer; ingerir una región
     * "a partir de" ella fabricaría evidencia que P10.7 nunca produjo realmente.
     */
    public static void requireNotBlockedPathologyInference(String degenerativeFindingsStatus) {
        if (P10_7_BLOCKED_STATUS.equals(degenerativeFindingsStatus)) {
            throw new IllegalArgumentException(
                "P10.7 (inferencia automática de patología) está bloqueado por preprocessing parity; "
                    + "no produce geometría apta para evidencia visual"
            );
        }
    }

    /**
     * 67C (emparejamiento axial) es experimental — {@code mappingValidated=false} upstream, según la
     * auditoría de IA de EXT-04B1. Toda geometría proveniente de un emparejamiento axial experimental debe
     * persistirse con {@code experimental=true}; quien llama no puede presentarla como una región
     * validada.
     */
    public static void requireExperimentalFlagForAxialPairing(boolean axialPairingExperimental, boolean experimental) {
        if (axialPairingExperimental && !experimental) {
            throw new IllegalArgumentException(
                "geometría derivada de un emparejamiento axial experimental (67C) debe persistirse con experimental=true"
            );
        }
    }
}
