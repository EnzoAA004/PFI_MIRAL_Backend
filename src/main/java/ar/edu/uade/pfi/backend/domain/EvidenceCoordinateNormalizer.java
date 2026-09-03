package ar.edu.uade.pfi.backend.domain;

/**
 * EXT-04B2: the single point in this Backend that converts an AI-supplied per-slice pixel
 * coordinate (the multiplanar v2 {@code CoordinateSpaceV2} basis — real pixel width/height of
 * the source slice) into the canonical normalised 0..256 basis {@link EvidenceGeometry} is
 * always expressed in.
 *
 * <p>EXT-04B1 section 8 is explicit that this conversion must happen in exactly one, tested
 * place rather than being repeated ad hoc in a controller, a UI, or the report — this class is
 * that place. No DICOM-physical coordinates are involved: no part of this stack stores or
 * validates {@code ImagePositionPatient}/{@code PixelSpacing} today, so introducing them here
 * would be inventing an unverified capability.
 */
public final class EvidenceCoordinateNormalizer {
    public static final double CANONICAL_SIZE = EvidenceGeometry.CANONICAL_MAX;
    private static final double EPSILON = 1e-6;

    private EvidenceCoordinateNormalizer() {
    }

    /**
     * @param pixelValue             a coordinate in the AI's own per-slice pixel frame
     * @param sourceDimensionPixels  the width or height (matching the axis of {@code pixelValue})
     *                               of that same frame, in real pixels
     * @return the equivalent coordinate in the canonical 0..256 basis
     */
    public static double normalize(double pixelValue, double sourceDimensionPixels) {
        if (sourceDimensionPixels <= 0 || Double.isNaN(sourceDimensionPixels) || Double.isInfinite(sourceDimensionPixels)) {
            throw new IllegalArgumentException("sourceDimensionPixels debe ser positivo: " + sourceDimensionPixels);
        }
        if (Double.isNaN(pixelValue) || Double.isInfinite(pixelValue)) {
            throw new IllegalArgumentException("pixelValue inválido: " + pixelValue);
        }
        double normalized = pixelValue / sourceDimensionPixels * CANONICAL_SIZE;
        // A pixel exactly on the frame's far edge (pixelValue == sourceDimensionPixels) is valid
        // and must land on 256.0, not be rejected by floating-point overshoot; anything
        // materially outside the source frame (negative, or past the far edge) is a real bug
        // upstream and is rejected rather than silently clamped away.
        if (normalized < -EPSILON || normalized > CANONICAL_SIZE + EPSILON) {
            throw new IllegalArgumentException(
                "coordenada fuera del frame de origen: " + pixelValue + " / " + sourceDimensionPixels
            );
        }
        return Math.max(0.0, Math.min(CANONICAL_SIZE, normalized));
    }
}
