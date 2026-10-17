package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contexto del asistente: corrida exacta, qué entra y qué NO entra al texto que recibe el modelo, y qué dice cuando falta
 * información. Son pruebas de contrato del código del servidor; no usan ningún modelo de lenguaje.
 */
class AssistantContextBuilderTest {
    private final AssistantTestFixtures fx = new AssistantTestFixtures();
    private final AssistantContextBuilder builder = fx.builder();

    private Study secretStudy() {
        return fx.studyWithContext("CASO-SECRETO-77", "PAT-SECRETO-9", LocalDate.of(2026, 10, 7), 54, Study.AGE_CALCULATED,
            "Lumbalgia crónica", "Dolor irradiado a pierna derecha", "6 meses", "Cirugía previa de rodilla");
    }

    private StudyRun fullRun(Study study, String runId) {
        return fx.run(study, runId, AssistantTestFixtures.T0, List.of(
            AssistantTestFixtures.disc("L4-L5", "height", 5.79), AssistantTestFixtures.disc("L4-L5", "area", 126.81),
            AssistantTestFixtures.disc("L5-S1", "height", 6.4)),
            List.of(AssistantTestFixtures.bulging("L5-S1", true, 0.73), AssistantTestFixtures.bulging("L4-L5", false, 0.9)));
    }

    @Test
    void studyContextCarriesMeasurementsCorrectionsFindingsAndClinicalContext() {
        Study study = secretStudy();
        StudyRun run = fullRun(study, "run-1");
        fx.correct(run, "sagittal-disc-l4-l5-height", 6.1, "mm");

        AssistantContext context = builder.buildStudy(study, "run-1");

        String text = context.text();
        assertTrue(text.contains("Edad al estudio: 54 años (calculada a partir de la fecha de nacimiento"), text);
        assertTrue(text.contains("«Lumbalgia crónica»") && text.contains("«6 meses»") && text.contains("«Cirugía previa de rodilla»"));
        assertTrue(text.contains("altura del disco 6.1 mm (corregido por un profesional; original de la IA 5.79 mm)"), text);
        assertTrue(text.contains("área del disco 126.81 mm2"), text);
        assertTrue(text.contains("L5-S1 · Abombamiento discal: presente (73.0 %)"), text);
        assertFalse(text.contains("distribución:"), "en una clasificación binaria la otra clase es el complemento: no se repite");
        assertTrue(text.contains("revisión profesional pendiente"));
        assertTrue(text.contains("No se entregan ni se analizaron imágenes") || text.contains("No se entregan ni se analizaron imágenes."));
        assertTrue(context.sources().contains("professional_correction") && context.sources().contains("clinical_context"));
        assertEquals(List.of(), context.missing());
        // Referencias citables reales: M1..M3 (mediciones), F1..F2 (hallazgos), C y R.
        assertTrue(context.elements().keySet().containsAll(List.of("M1", "M2", "M3", "F1", "F2", "C", "R")), context.elements().toString());
        assertEquals("run:run-1", context.scope().key());
    }

    @Test
    void textSentToTheModelNeverContainsIdentifiersOfThePatientOrTheStudy() {
        Study study = secretStudy();
        StudyRun run = fullRun(study, "run-identificable-123");
        AssistantContext context = builder.buildStudy(study, "run-identificable-123");
        String text = context.text();
        for (String forbidden : List.of("CASO-SECRETO-77", "PAT-SECRETO-9", "SUBJ-PAT-SECRETO-9", "run-identificable-123", run.id(), study.id(), run.traceId(),
            AssistantTestFixtures.SHA, "sag-run-identificable-123")) {
            assertFalse(text.contains(forbidden), "el texto para el modelo contiene «" + forbidden + "»");
        }
        // Los identificadores técnicos siguen disponibles del lado del servidor.
        assertEquals("CASO-SECRETO-77", context.scope().caseId());
        assertEquals("run-identificable-123", context.scope().runId());
    }

    @Test
    void absentAgeAndClinicalContextAreReportedAsMissingNotInvented() {
        Study study = fx.study("CASO-1", "PAT-1", "S", LocalDate.of(2026, 10, 7));
        fullRun(study, "run-1");
        AssistantContext context = builder.buildStudy(study, "run-1");
        String text = context.text();
        assertTrue(text.contains("Edad al estudio: NO INFORMADA"), text);
        assertTrue(text.contains("Motivo de consulta: NO INFORMADO") && text.contains("Síntomas: NO INFORMADO")
            && text.contains("Duración de los síntomas: NO INFORMADO") && text.contains("Antecedentes relevantes: NO INFORMADO"));
        assertTrue(context.missing().containsAll(List.of("Edad al estudio", "Motivo de consulta", "Síntomas", "Duración de los síntomas", "Antecedentes relevantes")), context.missing().toString());
        assertTrue(text.contains("DATOS AUSENTES O NO INFORMADOS (no se deben inventar ni suponer)"));
        assertFalse(context.sources().contains("clinical_context"));
    }

    @Test
    void ageProvenanceIsKeptApart() {
        Study reported = fx.studyWithContext("C-REP", "P-2", LocalDate.of(2026, 10, 7), 61, Study.AGE_REPORTED, null, null, null, null);
        fullRun(reported, "run-rep");
        assertTrue(builder.buildStudy(reported, "run-rep").text().contains("Edad al estudio: 61 años (informada, no calculada)"));
    }

    @Test
    void aStudyWithoutP107FindingsSaysSoInsteadOfInventingThem() {
        Study study = fx.study("CASO-1", "PAT-1", "S", LocalDate.of(2026, 10, 7));
        fx.run(study, "run-1", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 5.79)), null);
        AssistantContext context = builder.buildStudy(study, "run-1");
        assertTrue(context.text().contains("no equivale a «ausente»"), context.text());
        assertTrue(context.missing().contains("Hallazgos P10.7"));
        assertFalse(context.elements().keySet().stream().anyMatch(key -> key.startsWith("F")));
    }

    @Test
    void theRequestedRunIsUsedExactlyAndNeverReplacedByTheLatestOne() {
        Study study = fx.study("CASO-1", "PAT-1", "S", LocalDate.of(2026, 10, 7));
        fx.run(study, "run-vieja", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 5.0)), null);
        fx.run(study, "run-nueva", AssistantTestFixtures.T0.plusSeconds(500), List.of(AssistantTestFixtures.disc("L4-L5", "height", 9.0)), null);
        String old = builder.buildStudy(study, "run-vieja").text();
        assertTrue(old.contains("altura del disco 5 mm") && !old.contains("altura del disco 9 mm"), old);
        String latest = builder.buildStudy(study, "run-nueva").text();
        assertTrue(latest.contains("altura del disco 9 mm") && !latest.contains("altura del disco 5 mm"));
    }

    @Test
    void missingOrForeignRunIsRejectedNeverChosenSilently() {
        Study study = fx.study("CASO-1", "PAT-1", "S", LocalDate.of(2026, 10, 7));
        Study other = fx.study("CASO-2", "PAT-2", "S2", LocalDate.of(2026, 10, 7));
        fullRun(study, "run-1");
        fullRun(other, "run-ajena");
        assertEquals("CHAT_RUN_REQUIRED", assertThrows(RunReviewException.class, () -> builder.buildStudy(study, null)).code());
        assertEquals("CHAT_RUN_REQUIRED", assertThrows(RunReviewException.class, () -> builder.buildStudy(study, "  ")).code());
        RunReviewException foreign = assertThrows(RunReviewException.class, () -> builder.buildStudy(study, "run-ajena"));
        assertEquals("CHAT_RUN_NOT_FOUND", foreign.code());
        assertEquals(404, foreign.status().value());
        assertEquals("CHAT_RUN_NOT_FOUND", assertThrows(RunReviewException.class, () -> builder.buildStudy(study, "no-existe")).code());
    }

    @Test
    void fingerprintTracksTheDataNotTheCallAndChangesWithCorrectionsOrClinicalContext() {
        Study study = fx.study("CASO-1", "PAT-1", "S", LocalDate.of(2026, 10, 7));
        StudyRun run = fullRun(study, "run-1");
        String first = builder.buildStudy(study, "run-1").fingerprint();
        assertEquals(first, builder.buildStudy(study, "run-1").fingerprint(), "mismos datos, misma huella");
        fx.correct(run, "sagittal-disc-l4-l5-height", 6.1, "mm");
        String afterCorrection = builder.buildStudy(study, "run-1").fingerprint();
        assertNotEquals(first, afterCorrection, "una corrección cambia la huella");
        Study withContext = new Study(study.id(), study.caseId(), study.status(), study.patientId(), study.subjectRef(), study.studyDate(), study.modality(),
            study.description(), study.reviewPriority(), 40, Study.AGE_REPORTED, "Motivo nuevo", null, null, null, Study.CONTEXT_VERSION, study.createdAt(), study.updatedAt());
        fx.repository.saveStudy(withContext);
        assertNotEquals(afterCorrection, builder.buildStudy(withContext, "run-1").fingerprint(), "el contexto clínico cambia la huella");
        assertTrue(first.startsWith("ctx-"));
    }

    /* ------------------------------------------------------------------------------ comparación A/B */

    private Study[] pair() {
        Study reference = fx.studyWithContext("REF-1", "P-1", LocalDate.of(2026, 4, 1), 50, Study.AGE_CALCULATED, "Control", null, null, null);
        Study current = fx.studyWithContext("CUR-1", "P-1", LocalDate.of(2026, 10, 7), 50, Study.AGE_CALCULATED, "Seguimiento", "Dolor", "1 mes", null);
        return new Study[] {current, reference};
    }

    @Test
    void pairContextUsesTheExactRunsAndTheServersOwnComparators() {
        Study[] s = pair();
        fx.run(s[1], "run-a", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 5.79), AssistantTestFixtures.disc("L4-L5", "area", 100.0)),
            List.of(AssistantTestFixtures.bulging("L4-L5", false, 0.8), AssistantTestFixtures.bulging("L5-S1", true, 0.6)));
        fx.run(s[0], "run-b", AssistantTestFixtures.T0.plusSeconds(100), List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.3), AssistantTestFixtures.disc("L4-L5", "area", 100.0)),
            List.of(AssistantTestFixtures.bulging("L4-L5", true, 0.7), AssistantTestFixtures.bulging("L5-S1", true, 0.9)));

        AssistantContext context = builder.buildPair(s[0], "run-b", s[1], "run-a");

        String text = context.text();
        assertTrue(text.contains("A = Referencia, B = Actual"), text);
        assertTrue(text.contains("B − A = 189 días"), text);
        // La diferencia la calcula el comparador existente: 7.3 − 5.79 = 1.51 (con 2 decimales y signo).
        assertTrue(text.contains("altura del disco") && text.contains("A 5.79 mm") && text.contains("B 7.3 mm") && text.contains("B − A +1.51 mm"), text);
        assertTrue(text.contains("L4-L5 · Abombamiento discal: A ausente (80.0 %) → B presente (70.0 %) · Ausente → Presente"), text);
        assertTrue(text.contains("L5-S1 · Abombamiento discal: A presente (60.0 %) → B presente (90.0 %) · sin cambio de clasificación"), text);
        assertTrue(text.contains("«Control»") && text.contains("«Seguimiento»"), "contexto clínico de cada estudio por separado");
        assertTrue(context.elements().keySet().containsAll(List.of("A.C", "B.C", "A.R", "B.R", "D1", "H1")), context.elements().toString());
        assertTrue(context.scope().findingsComparable());
        assertEquals("pair:run-b|run-a", context.scope().key());
        // No viajan identificadores.
        for (String forbidden : List.of("REF-1", "CUR-1", "P-1", "run-a", "run-b")) assertFalse(text.contains(forbidden), forbidden);
    }

    @Test
    void pairWithoutFindingsInBothSidesDoesNotPromiseAFindingsComparison() {
        Study[] s = pair();
        fx.run(s[1], "run-a", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 5.79)), null);
        fx.run(s[0], "run-b", AssistantTestFixtures.T0.plusSeconds(100), List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.3)), null);
        AssistantContext context = builder.buildPair(s[0], "run-b", s[1], "run-a");
        assertFalse(context.scope().findingsComparable());
        assertTrue(context.text().contains("NO HAY COMPARACIÓN DE HALLAZGOS P10.7 PARA ESTE PAR"), context.text());
        assertTrue(context.text().contains("Sin datos no hay «Ausente»") || context.text().contains("sin datos en los dos lados no hay comparación"));
        assertFalse(context.elements().keySet().stream().anyMatch(key -> key.startsWith("H")));
        assertTrue(context.elements().containsKey("D1"), "las diferencias numéricas siguen disponibles");
    }

    @Test
    void pairWithFindingsOnOnlyOneSideIsNotComparable() {
        Study[] s = pair();
        fx.run(s[1], "run-a", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 5.79)), List.of(AssistantTestFixtures.bulging("L4-L5", false, 0.8)));
        fx.run(s[0], "run-b", AssistantTestFixtures.T0.plusSeconds(100), List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.3)), null);
        AssistantContext context = builder.buildPair(s[0], "run-b", s[1], "run-a");
        assertFalse(context.scope().findingsComparable());
        assertTrue(context.text().contains("B: Esta corrida no tiene hallazgos discales persistidos"), context.text());
    }

    @Test
    void pairWithoutUsableMeasurementsKeepsTheFindingsAndSaysWhatIsMissing() {
        Study[] s = pair();
        fx.run(s[1], "run-a", AssistantTestFixtures.T0, List.of(), List.of(AssistantTestFixtures.bulging("L4-L5", false, 0.8)));
        fx.run(s[0], "run-b", AssistantTestFixtures.T0.plusSeconds(100), List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.3)), List.of(AssistantTestFixtures.bulging("L4-L5", true, 0.7)));
        AssistantContext context = builder.buildPair(s[0], "run-b", s[1], "run-a");
        assertTrue(context.text().contains("No hay diferencias numéricas"), context.text());
        assertTrue(context.scope().findingsComparable());
        assertTrue(context.missing().stream().anyMatch(item -> item.startsWith("Diferencias numéricas")));
    }

    @Test
    void pairValidatesPatientRelationAndBothRuns() {
        Study[] s = pair();
        Study stranger = fx.study("OTRO-1", "P-999", "S-999", LocalDate.of(2026, 10, 7));
        fx.run(s[1], "run-a", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 5.79)), null);
        fx.run(s[0], "run-b", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.3)), null);
        fx.run(stranger, "run-x", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.3)), null);

        // Otro paciente: no se distingue de un estudio inexistente.
        assertThrows(StudyNotFoundException.class, () -> builder.buildPair(s[0], "run-b", stranger, "run-x"));
        // Misma identidad.
        assertEquals("COMPARISON_SAME_STUDY", assertThrows(RunReviewException.class, () -> builder.buildPair(s[0], "run-b", s[0], "run-b")).code());
        // La corrida de A no es de A (es de B) y la de B no existe: no se reemplazan.
        assertEquals("CHAT_RUN_NOT_FOUND", assertThrows(RunReviewException.class, () -> builder.buildPair(s[0], "run-b", s[1], "run-b")).code());
        assertEquals("CHAT_RUN_NOT_FOUND", assertThrows(RunReviewException.class, () -> builder.buildPair(s[0], "no-existe", s[1], "run-a")).code());
        assertEquals("CHAT_RUN_REQUIRED", assertThrows(RunReviewException.class, () -> builder.buildPair(s[0], "run-b", s[1], null)).code());
    }

    @Test
    void categoricalFindingsKeepTheirFullDistribution() {
        Study study = fx.study("CASO-1", "PAT-1", "S", LocalDate.of(2026, 10, 7));
        fx.run(study, "run-1", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 5.79)),
            List.of(AssistantTestFixtures.finding("L4-L5", "pfirrmann_grade", "II",
                java.util.Map.of("I", 0.1, "II", 0.6, "III", 0.2, "IV", 0.05, "V", 0.05))));
        String text = builder.buildStudy(study, "run-1").text();
        assertTrue(text.contains("Grado de Pfirrmann: grado II (60.0 %) · distribución: I 10.0 %, II 60.0 %, III 20.0 %, IV 5.0 %, V 5.0 %"), text);
        assertTrue(text.contains("Estado: experimentales (1)"), text);
    }

    @Test
    void aLargeRunStaysCompactEnoughForASmallLocalModel() {
        Study study = fx.study("CASO-1", "PAT-1", "S", LocalDate.of(2026, 10, 7));
        List<java.util.Map<String, Object>> measurements = new java.util.ArrayList<>();
        List<java.util.Map<String, Object>> findings = new java.util.ArrayList<>();
        for (String level : List.of("T11-T12", "T12-L1", "L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1")) {
            for (String metric : List.of("height", "width", "area")) measurements.add(AssistantTestFixtures.disc(level, metric, 10.5));
            measurements.add(AssistantTestFixtures.measurement("vertebra-" + level + "-height", "vertebra height", level, "mm", 20.1));
            measurements.add(AssistantTestFixtures.measurement("vertebra-" + level + "-width", "vertebra width", level, "mm", 40.2));
            measurements.add(AssistantTestFixtures.measurement("vertebra-" + level + "-area", "vertebra area", level, "mm2", 800.3));
            measurements.add(AssistantTestFixtures.measurement("canal-" + level, "canal ap", level, "mm", 14.1));
            measurements.add(AssistantTestFixtures.measurement("angle-" + level, "segmental angle", level, "deg", 4.2));
        }
        for (String level : List.of("L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1")) {
            for (String type : DiscFindingsComparator.TYPES) {
                if (type.equals("pfirrmann_grade")) findings.add(AssistantTestFixtures.finding(level, type, "II", java.util.Map.of("I", 0.1, "II", 0.6, "III", 0.2, "IV", 0.05, "V", 0.05)));
                else if (type.equals("modic_change")) findings.add(AssistantTestFixtures.finding(level, type, "none", java.util.Map.of("none", 0.7, "I", 0.1, "II", 0.1, "III", 0.1)));
                else findings.add(AssistantTestFixtures.finding(level, type, "absent", java.util.Map.of("absent", 0.8, "present", 0.2)));
            }
        }
        fx.run(study, "run-grande", AssistantTestFixtures.T0, measurements, findings);
        AssistantContext context = builder.buildStudy(study, "run-grande");
        // ~3,5 caracteres por token en español: por debajo de ~11 000 caracteres quedan holgados 8192 tokens de contexto.
        assertTrue(context.text().length() < 11000, "contexto de " + context.text().length() + " caracteres");
        assertTrue(context.elements().containsKey("M56") && context.elements().containsKey("F40"));
    }
}
