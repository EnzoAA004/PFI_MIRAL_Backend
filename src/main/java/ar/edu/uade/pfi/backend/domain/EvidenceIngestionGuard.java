package ar.edu.uade.pfi.backend.domain;

/**
 * EXT-04B2: guards that keep a not-yet-validated AI capability from being "laundered" into an
 * {@link AiEvidenceRegion} that looks as trustworthy as a validated one. Both conditions come
 * straight from the EXT-04B1 AI-repo audit — neither is invented here.
 */
public final class EvidenceIngestionGuard {
    /** {@code AiProductAnalyzeResponseDto.DegenerativeFindings#status()} when P10.7 is blocked. */
    public static final String P10_7_BLOCKED_STATUS = "blocked_preprocessing_parity";

    private EvidenceIngestionGuard() {
    }

    /**
     * P10.7 (automatic pathology inference) is blocked by preprocessing parity — per
     * {@code AiProductAnalyzeResponseDto.DegenerativeFindings}'s own javadoc, its status "is
     * currently always {@code blocked_preprocessing_parity} — never a negative finding". A
     * blocked capability has no validated localized geometry to offer; ingesting a region
     * "from" it would fabricate evidence P10.7 never actually produced.
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
     * 67C (axial pairing) is experimental — {@code mappingValidated=false} upstream, per the
     * EXT-04B1 AI audit. Any geometry sourced from an experimental axial pairing must be
     * persisted with {@code experimental=true}; a caller may not present it as a validated
     * region.
     */
    public static void requireExperimentalFlagForAxialPairing(boolean axialPairingExperimental, boolean experimental) {
        if (axialPairingExperimental && !experimental) {
            throw new IllegalArgumentException(
                "geometría derivada de un emparejamiento axial experimental (67C) debe persistirse con experimental=true"
            );
        }
    }
}
