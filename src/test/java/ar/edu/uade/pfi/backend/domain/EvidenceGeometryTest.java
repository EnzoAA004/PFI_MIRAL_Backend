package ar.edu.uade.pfi.backend.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceGeometryTest {

    @Test
    void bboxRoundTripsThroughMapAndValidatesRange() {
        EvidenceGeometry.Bbox bbox = new EvidenceGeometry.Bbox(10.0, 20.0, 30.0, 40.0);
        Map<String, Object> map = bbox.toMap();

        assertEquals(EvidenceGeometry.TYPE_BBOX, EvidenceGeometry.typeOf(bbox));
        assertEquals(bbox, EvidenceGeometry.fromMap(EvidenceGeometry.TYPE_BBOX, map));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.Bbox(-1.0, 0.0, 10.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.Bbox(0.0, 0.0, 0.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.Bbox(250.0, 0.0, 10.0, 10.0));
    }

    @Test
    void ellipseRoundTripsThroughMapAndValidatesRange() {
        EvidenceGeometry.Ellipse ellipse = new EvidenceGeometry.Ellipse(128.0, 128.0, 20.0, 15.0);
        Map<String, Object> map = ellipse.toMap();

        assertEquals(EvidenceGeometry.TYPE_ELLIPSE, EvidenceGeometry.typeOf(ellipse));
        assertEquals(ellipse, EvidenceGeometry.fromMap(EvidenceGeometry.TYPE_ELLIPSE, map));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.Ellipse(128.0, 128.0, 0.0, 15.0));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.Ellipse(-5.0, 128.0, 20.0, 15.0));
    }

    @Test
    void polygonRoundTripsThroughMapAndRequiresAtLeastThreePoints() {
        EvidenceGeometry.Polygon polygon = new EvidenceGeometry.Polygon(List.of(
            new EvidenceGeometry.Polygon.Point(0.0, 0.0),
            new EvidenceGeometry.Polygon.Point(10.0, 0.0),
            new EvidenceGeometry.Polygon.Point(5.0, 10.0)
        ));
        Map<String, Object> map = polygon.toMap();

        assertEquals(EvidenceGeometry.TYPE_POLYGON, EvidenceGeometry.typeOf(polygon));
        assertEquals(polygon, EvidenceGeometry.fromMap(EvidenceGeometry.TYPE_POLYGON, map));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.Polygon(List.of(
            new EvidenceGeometry.Polygon.Point(0.0, 0.0),
            new EvidenceGeometry.Polygon.Point(10.0, 0.0)
        )));
    }

    @Test
    void maskReferenceRoundTripsThroughMapAndRejectsPathTraversal() {
        EvidenceGeometry.MaskReference reference = new EvidenceGeometry.MaskReference("run-1", "sagittal", "mask-preview.png");
        Map<String, Object> map = reference.toMap();

        assertEquals(EvidenceGeometry.TYPE_MASK_REFERENCE, EvidenceGeometry.typeOf(reference));
        assertEquals(reference, EvidenceGeometry.fromMap(EvidenceGeometry.TYPE_MASK_REFERENCE, map));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.MaskReference("run-1", "sagittal", "../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.MaskReference("run-1", "sagittal", "sub/dir/mask.png"));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.MaskReference("run-1", "coronal", "mask.png"));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGeometry.MaskReference("", "sagittal", "mask.png"));
    }

    @Test
    void fromMapRejectsUnknownGeometryType() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> EvidenceGeometry.fromMap("CIRCLE_OF_DOOM", Map.of("x", 1.0)));
        assertTrue(ex.getMessage().contains("CIRCLE_OF_DOOM"));
    }

    @Test
    void fromMapRejectsMissingNumericField() {
        assertThrows(IllegalArgumentException.class,
            () -> EvidenceGeometry.fromMap(EvidenceGeometry.TYPE_BBOX, Map.of("x", 1.0, "y", 1.0, "width", 1.0)));
    }
}
