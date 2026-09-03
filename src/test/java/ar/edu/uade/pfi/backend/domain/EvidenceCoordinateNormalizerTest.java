package ar.edu.uade.pfi.backend.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EvidenceCoordinateNormalizerTest {

    @Test
    void normalizesAPixelCoordinateIntoTheCanonical0To256Basis() {
        assertEquals(128.0, EvidenceCoordinateNormalizer.normalize(256.0, 512.0), 1e-9);
        assertEquals(0.0, EvidenceCoordinateNormalizer.normalize(0.0, 512.0), 1e-9);
    }

    @Test
    void normalizesTheFarEdgeExactlyTo256WithoutFloatingPointOvershootRejection() {
        assertEquals(256.0, EvidenceCoordinateNormalizer.normalize(512.0, 512.0), 1e-9);
        assertEquals(256.0, EvidenceCoordinateNormalizer.normalize(320.0, 320.0), 1e-9);
    }

    @Test
    void rejectsACoordinateMateriallyOutsideTheSourceFrame() {
        assertThrows(IllegalArgumentException.class, () -> EvidenceCoordinateNormalizer.normalize(-10.0, 512.0));
        assertThrows(IllegalArgumentException.class, () -> EvidenceCoordinateNormalizer.normalize(600.0, 512.0));
    }

    @Test
    void rejectsANonPositiveOrInvalidSourceDimension() {
        assertThrows(IllegalArgumentException.class, () -> EvidenceCoordinateNormalizer.normalize(10.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> EvidenceCoordinateNormalizer.normalize(10.0, -5.0));
        assertThrows(IllegalArgumentException.class, () -> EvidenceCoordinateNormalizer.normalize(Double.NaN, 512.0));
    }
}
