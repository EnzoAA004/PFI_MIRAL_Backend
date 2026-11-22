package ar.edu.uade.pfi.backend.domain;

/**
 * EXT-04B2: el único punto de este Backend que convierte una coordenada de píxel por slice suministrada por la IA
 * (la base multiplanar v2 {@code CoordinateSpaceV2} — ancho/alto real en píxeles del
 * slice de origen) a la base normalizada canónica 0..256 en la que {@link EvidenceGeometry} se expresa
 * siempre.
 *
 * <p>EXT-04B1 sección 8 es explícita en que esta conversión debe ocurrir en exactamente un lugar, testeado,
 * y no repetirse ad hoc en un controller, una UI o el reporte — esta clase es
 * ese lugar. No intervienen coordenadas físicas DICOM: ninguna parte de este stack guarda ni
 * valida {@code ImagePositionPatient}/{@code PixelSpacing} hoy, así que introducirlas acá
 * sería inventar una capacidad no verificada.
 */
public final class EvidenceCoordinateNormalizer {
    public static final double CANONICAL_SIZE = EvidenceGeometry.CANONICAL_MAX;
    private static final double EPSILON = 1e-6;

    private EvidenceCoordinateNormalizer() {
    }

    /**
     * @param pixelValue             una coordenada en el marco de píxeles por slice propio de la IA
     * @param sourceDimensionPixels  el ancho o el alto (según el eje de {@code pixelValue})
     *                               de ese mismo marco, en píxeles reales
     * @return la coordenada equivalente en la base canónica 0..256
     */
    public static double normalize(double pixelValue, double sourceDimensionPixels) {
        if (sourceDimensionPixels <= 0 || Double.isNaN(sourceDimensionPixels) || Double.isInfinite(sourceDimensionPixels)) {
            throw new IllegalArgumentException("sourceDimensionPixels debe ser positivo: " + sourceDimensionPixels);
        }
        if (Double.isNaN(pixelValue) || Double.isInfinite(pixelValue)) {
            throw new IllegalArgumentException("pixelValue inválido: " + pixelValue);
        }
        double normalized = pixelValue / sourceDimensionPixels * CANONICAL_SIZE;
        // Un píxel justo en el borde lejano del marco (pixelValue == sourceDimensionPixels) es válido
        // y debe caer en 256.0, no ser rechazado por un desborde de punto flotante; cualquier cosa
        // materialmente fuera del marco de origen (negativa, o más allá del borde lejano) es un bug real
        // upstream y se rechaza en lugar de recortarse silenciosamente.
        if (normalized < -EPSILON || normalized > CANONICAL_SIZE + EPSILON) {
            throw new IllegalArgumentException(
                "coordenada fuera del frame de origen: " + pixelValue + " / " + sourceDimensionPixels
            );
        }
        return Math.max(0.0, Math.min(CANONICAL_SIZE, normalized));
    }
}
