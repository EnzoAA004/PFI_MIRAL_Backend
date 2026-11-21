package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto.Location;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto.Point;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El vínculo entre cada valor comparado y su lugar en la imagen: se toma de lo que la corrida persistió
 * (corte, espacio de coordenadas, trazo) y nunca se deduce por el nombre de la medición ni por el número de
 * corte del otro estudio.
 */
class InitialAnalysisComparisonLocationTest {
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Map<String, Object> SPACE = Map.of(
        "name", "model_256x256", "units", "pixel", "width", 256, "height", 256, "sourceSliceIndex", 7);

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final InitialAnalysisComparisonService service =
        new InitialAnalysisComparisonService(repository, new InitialAnalysisMeasurementReader(repository));

    private Study study(String caseId) {
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", "P-1", "VIS-1", LocalDate.of(2026, 4, 1), "MRI", "desc", "medium", T0, T0);
        repository.saveStudy(study);
        repository.saveInput(new InputResource(UUID.randomUUID().toString(), study.id(), "sagittal", "inp-" + caseId, "dcm", 10, "t2_tse_sag", "t2", 15, false, false, true, T0));
        return study;
    }

    private static List<Map<String, Object>> pts(double... xy) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) list.add(Map.of("x", xy[i], "y", xy[i + 1]));
        return list;
    }

    private static Map<String, Object> measurement(String id, String labelKey, String unit, double value, Integer slice, List<Map<String, Object>> points) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("labelKey", labelKey);
        map.put("level", "L4-L5");
        map.put("levelScope", "level");
        map.put("unit", unit);
        map.put("value", value);
        map.put("aiValue", value);
        map.put("plane", "sagittal");
        map.put("measurementBasis", "physical_spacing");
        if (slice != null) map.put("sliceIndex", slice);
        map.put("points", points);
        return map;
    }

    private StudyRun run(Study study, Instant at, Map<String, Object> space, List<Map<String, Object>> measurements) {
        Map<String, Object> sagittal = new LinkedHashMap<>();
        sagittal.put("measurements", measurements);
        sagittal.put("input", Map.of("inputId", "inp-" + study.caseId()));
        sagittal.put("planeRunId", "plane-" + study.caseId());
        if (space != null) sagittal.put("coordinateSpace", space);
        StudyRun run = new StudyRun(
            UUID.randomUUID().toString(), study.id(), "run-" + study.caseId(), "trace", "real_baseline", "real_baseline",
            "sagittal_spider", "axial_t2_alkafri", "hash-1", "", "sag", "ax",
            Map.of(), Map.of("planes", Map.of("sagittal", sagittal, "axial", Map.of("measurements", List.of()))),
            List.of(), "completed", "pending", "", null, "", at, at);
        repository.saveRun(run);
        return run;
    }

    private void correct(StudyRun run, String measurementId, Map<String, Object> after) {
        repository.saveReview(run.multiplanarRunId(), "observed", "Dra. Test", T0.plusSeconds(60), "nota larga",
            List.of(new MeasurementCorrection(UUID.randomUUID().toString(), run.id(), measurementId, "x", Map.of("value", 0), after, "", T0.plusSeconds(60))));
    }

    private InitialAnalysisComparisonDto.Row only(InitialAnalysisComparisonDto dto) {
        assertEquals(1, dto.comparisons().size(), dto.toString());
        return dto.comparisons().get(0);
    }

    @Test
    void locatedSegmentCarriesSliceSpaceAndPersistedGeometryForEachSide() {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, T0, SPACE, List.of(measurement("h", "disc height", "mm", 5.79, 7, pts(10, 20, 11, 30))));
        run(cur, T0.plusSeconds(5), SPACE, List.of(measurement("h", "disc height", "mm", 6.0, 7, pts(50, 60, 52, 70))));

        InitialAnalysisComparisonDto.Row row = only(service.compare("cur", "ref", null, null));

        Location reference = row.reference().location();
        assertEquals(Location.LOCATED, reference.status());
        assertEquals("segment", reference.geometryKind());
        assertEquals(List.of(new Point(10, 20), new Point(11, 30)), reference.geometry());
        assertEquals(7, reference.sliceIndex());
        assertEquals("sagittal", reference.plane());
        assertEquals("plane-ref", reference.planeRunId());
        assertEquals("inp-ref", reference.inputId());
        assertEquals("model_256x256", reference.coordinateSpace());
        assertEquals(256, reference.coordinateWidth());
        assertEquals(List.of(new Point(50, 60), new Point(52, 70)), row.current().location().geometry());
        assertEquals("plane-cur", row.current().location().planeRunId());
    }

    @Test
    void eachSideKeepsItsOwnSliceEvenWhenTheNumbersDiffer() {
        Study ref = study("ref");
        Study cur = study("cur");
        Map<String, Object> otherSpace = Map.of("name", "model_256x256", "units", "pixel", "width", 256, "height", 256, "sourceSliceIndex", 11);
        run(ref, T0, SPACE, List.of(measurement("h", "disc height", "mm", 5.0, 7, pts(1, 2, 3, 4))));
        run(cur, T0.plusSeconds(5), otherSpace, List.of(measurement("h", "disc height", "mm", 6.0, 11, pts(5, 6, 7, 8))));

        InitialAnalysisComparisonDto.Row row = only(service.compare("cur", "ref", null, null));

        // mismo par comparable, cortes distintos: no se alinean ni se igualan por índice
        assertEquals(7, row.reference().location().sliceIndex());
        assertEquals(11, row.current().location().sliceIndex());
        assertEquals(Location.LOCATED, row.reference().location().status());
        assertEquals(Location.LOCATED, row.current().location().status());
    }

    @Test
    void areaHasSliceButNoInventedGeometry() {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, T0, SPACE, List.of(measurement("a", "disc area", "mm2", 100.0, 7, List.of())));
        run(cur, T0.plusSeconds(5), SPACE, List.of(measurement("a", "disc area", "mm2", 110.0, 7, List.of())));

        Location location = only(service.compare("cur", "ref", null, null)).current().location();

        assertEquals(Location.SLICE_ONLY, location.status());
        assertEquals("none", location.geometryKind());
        assertTrue(location.geometry().isEmpty());
        assertEquals(7, location.sliceIndex());
        assertNotNull(location.reason());
    }

    @Test
    void geometryWithoutDeclaredCoordinateSpaceIsNotLocated() {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, T0, null, List.of(measurement("h", "disc height", "mm", 5.0, 7, pts(1, 2, 3, 4))));
        run(cur, T0.plusSeconds(5), SPACE, List.of(measurement("h", "disc height", "mm", 6.0, 7, pts(1, 2, 3, 4))));

        InitialAnalysisComparisonDto.Row row = only(service.compare("cur", "ref", null, null));

        assertEquals(Location.SLICE_ONLY, row.reference().location().status());
        assertTrue(row.reference().location().geometry().isEmpty());
        assertEquals(Location.LOCATED, row.current().location().status(), "el otro lado no se bloquea");
    }

    @Test
    void geometryFromAnotherSliceSpaceIsNotPlacedOnTheMeasurementSlice() {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, T0, SPACE, List.of(measurement("h", "disc height", "mm", 5.0, 9, pts(1, 2, 3, 4))));
        run(cur, T0.plusSeconds(5), SPACE, List.of(measurement("h", "disc height", "mm", 6.0, 7, pts(1, 2, 3, 4))));

        Location location = only(service.compare("cur", "ref", null, null)).reference().location();

        assertEquals(Location.SLICE_ONLY, location.status());
        assertTrue(location.reason().contains("otro corte"));
    }

    @Test
    void missingSliceIsNotLocated() {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, T0, SPACE, List.of(measurement("h", "disc height", "mm", 5.0, null, pts(1, 2, 3, 4))));
        run(cur, T0.plusSeconds(5), SPACE, List.of(measurement("h", "disc height", "mm", 6.0, 7, pts(1, 2, 3, 4))));

        Location location = only(service.compare("cur", "ref", null, null)).reference().location();

        assertEquals(Location.NOT_LOCATED, location.status());
        assertTrue(location.geometry().isEmpty());
    }

    @Test
    void numericCorrectionDoesNotChangeTheGeometry() {
        Study ref = study("ref");
        Study cur = study("cur");
        StudyRun reference = run(ref, T0, SPACE, List.of(measurement("h", "disc height", "mm", 5.79, 7, pts(10, 20, 11, 30))));
        run(cur, T0.plusSeconds(5), SPACE, List.of(measurement("h", "disc height", "mm", 6.0, 7, pts(1, 2, 3, 4))));
        correct(reference, "h", Map.of("value", "7.3", "unit", "mm"));

        InitialAnalysisComparisonDto.Row row = only(service.compare("cur", "ref", null, null));

        assertTrue(row.reference().corrected());
        assertEquals(List.of(new Point(10, 20), new Point(11, 30)), row.reference().location().geometry(), "el trazo sigue siendo el de la IA");
        assertTrue(row.reference().location().correctedGeometry().isEmpty());
    }

    @Test
    void correctionThatRepeatsTheAiGeometryIsNotPresentedAsMoved() {
        Study ref = study("ref");
        Study cur = study("cur");
        StudyRun reference = run(ref, T0, SPACE, List.of(measurement("h", "disc height", "mm", 5.79, 7, pts(10, 20, 11, 30))));
        run(cur, T0.plusSeconds(5), SPACE, List.of(measurement("h", "disc height", "mm", 6.0, 7, pts(1, 2, 3, 4))));
        correct(reference, "h", Map.of("value", 7.3, "unit", "mm", "points", pts(10, 20, 11, 30)));

        assertTrue(only(service.compare("cur", "ref", null, null)).reference().location().correctedGeometry().isEmpty());
    }

    @Test
    void geometryThatTheReviewerMovedTravelsSeparatelyFromTheAiOne() {
        Study ref = study("ref");
        Study cur = study("cur");
        StudyRun reference = run(ref, T0, SPACE, List.of(measurement("h", "disc height", "mm", 5.79, 7, pts(10, 20, 11, 30))));
        run(cur, T0.plusSeconds(5), SPACE, List.of(measurement("h", "disc height", "mm", 6.0, 7, pts(1, 2, 3, 4))));
        correct(reference, "h", Map.of("value", 7.3, "unit", "mm", "points", pts(10, 20, 12, 33)));

        Location location = only(service.compare("cur", "ref", null, null)).reference().location();

        assertEquals(List.of(new Point(10, 20), new Point(11, 30)), location.geometry());
        assertEquals(List.of(new Point(10, 20), new Point(12, 33)), location.correctedGeometry());
    }

    @Test
    void geometryKindFollowsThePointCountNotTheName() {
        Study ref = study("ref");
        Study cur = study("cur");
        Map<String, Object> angle = measurement("g", "segmental angle", "deg", 10.0, 7, pts(1, 2, 3, 4, 5, 6, 7, 8));
        Map<String, Object> three = measurement("h", "disc height", "mm", 5.0, 7, pts(1, 2, 3, 4, 5, 6));
        run(ref, T0, SPACE, List.of(angle, three));
        run(cur, T0.plusSeconds(5), SPACE, List.of(
            measurement("g", "segmental angle", "deg", 12.0, 7, pts(1, 2, 3, 4, 5, 6, 7, 8)),
            measurement("h", "disc height", "mm", 6.0, 7, pts(1, 2, 3, 4, 5, 6))));

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        InitialAnalysisComparisonDto.Row angleRow = dto.comparisons().stream().filter((r) -> r.metric().contains("angle")).findFirst().orElseThrow();
        assertEquals("angle", angleRow.reference().location().geometryKind());
        InitialAnalysisComparisonDto.Row heightRow = dto.comparisons().stream().filter((r) -> r.metric().equals("height")).findFirst().orElseThrow();
        assertEquals("none", heightRow.reference().location().geometryKind(), "tres puntos no son un trazo que el visor sepa dibujar");
        assertTrue(heightRow.reference().location().geometry().isEmpty());
    }
}
