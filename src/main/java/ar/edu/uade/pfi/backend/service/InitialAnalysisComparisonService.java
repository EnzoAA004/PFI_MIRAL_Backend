package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.InputSequence;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.InitialAnalysisMeasurementReader.Reading;
import ar.edu.uade.pfi.backend.service.StudyPatientIdentityPolicy.Link;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Comparación entre estudios sobre las mediciones REALES del análisis inicial (la corrida multiplanar que
 * muestra el visor). Es independiente del circuito de producto/demostración: no lee sus tablas, no se apoya
 * en él como alternativa y no ejecuta ningún modelo.
 *
 * <p><b>Qué corrida se consulta.</b> En cada estudio, una sola regla: <i>la corrida más reciente del análisis
 * inicial</i> (la misma que abre el visor: {@code findLatestRunByStudyId}). Para el estudio abierto se puede
 * indicar además {@code currentRunId} —la corrida que el visor muestra— y se usa esa. Si una corrida pedida no
 * existe o no pertenece al estudio, o si la corrida elegida no tiene mediciones utilizables, se dice y se
 * corta: nunca se sustituye en silencio por otra.
 *
 * <p><b>Paciente.</b> {@link StudyPatientIdentityPolicy}: {@code patientId} si ambos lo tienen (y si difiere, no
 * hay comparación aunque coincida el {@code subjectRef}); {@code subjectRef} inequívoco solo como
 * compatibilidad histórica; nunca por nombre.
 */
@Service
public class InitialAnalysisComparisonService {
    public static final String RUN_SELECTION_RULE =
        "Se usa la corrida más reciente del análisis inicial de cada estudio (la que abre el visor). "
            + "Si esa corrida no tiene mediciones utilizables no se la reemplaza por otra anterior.";

    static final List<String> LIMITATIONS = List.of(
        "Compara solo mediciones del análisis inicial (sagital) cuya equivalencia se pudo demostrar: mismo nivel, estructura, métrica, plano, unidad, modelo y secuencia.",
        "El nivel lo asigna el análisis inicial contando los espacios discales desde el inferior (supone un encuadre que llega a L5-S1); aquí se respeta ese nivel y se excluyen las mediciones sin nivel.",
        "Cada valor sale de un único corte inferido por corrida; los cortes de los dos estudios pueden no ser el mismo plano anatómico.",
        "La diferencia describe el valor numérico; no es una conclusión clínica ni indica mejoría o empeoramiento.",
        "Las mediciones axiales, las derivadas sin definición común y las manuales del revisor no se comparan."
    );

    private final StudyRepository repository;
    private final InitialAnalysisMeasurementReader reader;

    public InitialAnalysisComparisonService(StudyRepository repository, InitialAnalysisMeasurementReader reader) {
        this.repository = repository;
        this.reader = reader;
    }

    public InitialAnalysisComparisonDto compare(String currentCaseId, String referenceCaseId, String currentRunId, String referenceRunId) {
        if (currentCaseId.equals(referenceCaseId)) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "COMPARISON_SAME_STUDY", "No se puede comparar un estudio consigo mismo.");
        }
        Study current = repository.findStudyByCaseId(currentCaseId).orElseThrow(() -> new StudyNotFoundException(currentCaseId));
        Study reference = repository.findStudyByCaseId(referenceCaseId).orElseThrow(() -> new StudyNotFoundException(referenceCaseId));
        Link link = StudyPatientIdentityPolicy.relate(current, reference);
        // Mismo criterio que el resto de la familia (IDOR): un estudio de otro paciente no se distingue de uno inexistente.
        if (!link.allowed()) throw new StudyNotFoundException(referenceCaseId);

        StudyRun currentRun = requireRun(current, currentRunId, "abierto").orElseThrow(() -> noAnalysis("CURRENT", current));
        StudyRun referenceRun = requireRun(reference, referenceRunId, "de referencia").orElseThrow(() -> noAnalysis("REFERENCE", reference));
        Reading currentReading = reader.read(current, currentRun);
        Reading referenceReading = reader.read(reference, referenceRun);
        int currentUsable = InitialMeasurementMatcher.usableCount(currentReading.measurements());
        int referenceUsable = InitialMeasurementMatcher.usableCount(referenceReading.measurements());
        if (currentUsable == 0) throw noUsable("CURRENT", current, currentRun);
        if (referenceUsable == 0) throw noUsable("REFERENCE", reference, referenceRun);

        InitialMeasurementMatcher.Result result = InitialMeasurementMatcher.match(
            currentReading.measurements(), currentReading.context(), referenceReading.measurements(), referenceReading.context());
        return new InitialAnalysisComparisonDto(
            InitialAnalysisComparisonDto.SCHEMA_VERSION,
            InitialAnalysisComparisonDto.SOURCE,
            InitialAnalysisComparisonDto.DIFFERENCE_DEFINITION,
            side(current, currentReading, currentUsable, link),
            side(reference, referenceReading, referenceUsable, link),
            new InitialAnalysisComparisonDto.Summary(
                result.rows().size(), result.excluded().size(), currentReading.measurements().size(), referenceReading.measurements().size()),
            result.rows(),
            result.excluded(),
            limitations(currentReading.context(), referenceReading.context())
        );
    }

    /** Las fijas, más una nota cuando la secuencia coincide: qué es esa coincidencia y qué no garantiza. */
    static List<String> limitations(InitialAnalysisMeasurementReader.RunContext current, InitialAnalysisMeasurementReader.RunContext reference) {
        if (!current.sequence().isComparableWeighting() || !current.sequence().value().equals(reference.sequence().value())) {
            return LIMITATIONS;
        }
        List<String> all = new ArrayList<>(LIMITATIONS);
        all.add("La secuencia sagital es " + current.sequence().value().toUpperCase(java.util.Locale.ROOT) + " en ambos estudios ("
            + provenance(reference.sequence()) + " en la referencia, " + provenance(current.sequence()) + " en el estudio abierto). "
            + (current.sequence().isDeclared() || reference.sequence().isDeclared()
                ? "Una declaración no es una detección automática ni una validación del modelo, y coincidir "
                : "Coincidir ")
            + "en ponderación no garantiza el mismo protocolo, equipo, resolución ni corte.");
        return List.copyOf(all);
    }

    private static String provenance(InputSequence sequence) {
        return sequence.isDeclared() ? "declarada por quien cargó el archivo" : "detectada del estudio DICOM";
    }

    public InitialAnalysisComparisonDto.Candidates candidates(String caseId, String currentRunId, Predicate<String> canRead) {
        Study current = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        Optional<StudyRun> currentRun = requireRun(current, currentRunId, "abierto");
        InitialAnalysisComparisonDto.StudySide currentSide = currentRun
            .map((run) -> {
                Reading reading = reader.read(current, run);
                return side(current, reading, InitialMeasurementMatcher.usableCount(reading.measurements()), Link.NONE);
            })
            .orElseGet(() -> emptySide(current));

        Map<String, Study> related = new LinkedHashMap<>();
        if (current.patientId() != null && !current.patientId().isBlank()) {
            for (Study other : repository.findStudiesByPatientId(current.patientId())) related.put(other.id(), other);
        }
        if (current.subjectRef() != null && !current.subjectRef().isBlank()) {
            for (Study other : repository.findStudiesBySubjectRef(current.subjectRef())) related.putIfAbsent(other.id(), other);
        }
        List<InitialAnalysisComparisonDto.Candidate> candidates = new ArrayList<>();
        related.values().stream()
            .filter((other) -> !other.id().equals(current.id()))
            .filter((other) -> canRead.test(other.caseId()))
            .sorted(Comparator.comparing(Study::studyDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Study::createdAt, Comparator.reverseOrder()))
            .forEach((other) -> {
                Link link = StudyPatientIdentityPolicy.relate(current, other);
                if (!link.allowed()) return;
                candidates.add(candidate(other, link));
            });
        return new InitialAnalysisComparisonDto.Candidates(
            InitialAnalysisComparisonDto.SCHEMA_VERSION, currentSide, List.copyOf(candidates), RUN_SELECTION_RULE);
    }

    private InitialAnalysisComparisonDto.Candidate candidate(Study other, Link link) {
        Optional<StudyRun> latest = repository.findLatestRunByStudyId(other.id());
        if (latest.isEmpty()) {
            return new InitialAnalysisComparisonDto.Candidate(
                other.caseId(), other.studyDate(), null, null, 0, false, "no_analysis",
                "El estudio no tiene un análisis inicial.", link.code());
        }
        StudyRun run = latest.get();
        int usable = InitialMeasurementMatcher.usableCount(reader.read(other, run).measurements());
        return new InitialAnalysisComparisonDto.Candidate(
            other.caseId(), other.studyDate(), run.multiplanarRunId(), run.createdAt(), usable, usable > 0,
            usable > 0 ? null : "no_usable_measurements",
            usable > 0 ? null : "Su análisis inicial más reciente no tiene mediciones utilizables para comparar.",
            link.code());
    }

    /** {@code Optional.empty()} solo cuando no se pidió una corrida y el estudio no tiene ninguna. */
    private Optional<StudyRun> requireRun(Study study, String requestedRunId, String role) {
        if (requestedRunId == null || requestedRunId.isBlank()) return repository.findLatestRunByStudyId(study.id());
        Optional<StudyRun> run = repository.findRunByMultiplanarRunId(requestedRunId.trim())
            .filter((candidate) -> candidate.studyId().equals(study.id()));
        if (run.isEmpty()) {
            throw new RunReviewException(HttpStatus.NOT_FOUND, "COMPARISON_RUN_NOT_FOUND",
                "No se pudo recuperar el análisis " + requestedRunId + " del estudio " + role + " " + study.caseId()
                    + ". No se reemplaza por otro análisis.");
        }
        return run;
    }

    private RunReviewException noAnalysis(String side, Study study) {
        return new RunReviewException(HttpStatus.UNPROCESSABLE_ENTITY, "COMPARISON_NO_ANALYSIS_" + side,
            "El estudio " + study.caseId() + " no tiene un análisis inicial.");
    }

    private RunReviewException noUsable(String side, Study study, StudyRun run) {
        return new RunReviewException(HttpStatus.UNPROCESSABLE_ENTITY, "COMPARISON_NO_USABLE_MEASUREMENTS_" + side,
            "El análisis " + run.multiplanarRunId() + " del estudio " + study.caseId()
                + " no tiene mediciones utilizables para comparar. No se reemplaza por otro análisis.");
    }

    private InitialAnalysisComparisonDto.StudySide side(Study study, Reading reading, int usable, Link link) {
        StudyRun run = reading.run();
        return new InitialAnalysisComparisonDto.StudySide(
            study.caseId(), study.studyDate(), run.multiplanarRunId(), run.createdAt(), run.status(),
            ReviewStatusMapper.toApiStatus(run.reviewStatus()), reading.context().modelKey(),
            reading.context().sagittalSequence(), reading.context().sequence().source(),
            reading.context().sequence().description().isEmpty() ? null : reading.context().sequence().description(),
            usable, link.code());
    }

    private InitialAnalysisComparisonDto.StudySide emptySide(Study study) {
        LocalDate date = study.studyDate();
        return new InitialAnalysisComparisonDto.StudySide(study.caseId(), date, null, null, null, null, null, null, null, null, 0, Link.NONE.code());
    }
}
