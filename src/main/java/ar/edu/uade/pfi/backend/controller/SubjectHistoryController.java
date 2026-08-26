package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.MeasurementSeriesDto;
import ar.edu.uade.pfi.backend.service.PatientHistoryService;
import ar.edu.uade.pfi.backend.service.PatientMeasurementSeriesService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subjects")
public class SubjectHistoryController {
    private final PatientHistoryService patientHistoryService;
    private final PatientMeasurementSeriesService measurementSeriesService;
    private final ProfessionalAccessAuditService accessAuditService;

    public SubjectHistoryController(
        PatientHistoryService patientHistoryService,
        PatientMeasurementSeriesService measurementSeriesService,
        ProfessionalAccessAuditService accessAuditService
    ) {
        this.patientHistoryService = patientHistoryService;
        this.measurementSeriesService = measurementSeriesService;
        this.accessAuditService = accessAuditService;
    }

    @GetMapping("/{subjectRef}/history")
    public Map<String, Object> history(@PathVariable String subjectRef, HttpServletRequest request) {
        accessAuditService.record(request, "access_subject_history", "Historial longitudinal de-identificado consultado subjectRef=" + subjectRef);
        return patientHistoryService.history(subjectRef);
    }

    /** Stage 84-M: which measurement keys have at least 2 comparable Studies -- for the Frontend's series selector. */
    @GetMapping("/{subjectRef}/measurement-series/keys")
    public List<MeasurementSeriesDto.MeasurementSeriesKeyDto> availableSeriesKeys(@PathVariable String subjectRef, HttpServletRequest request) {
        accessAuditService.record(request, "access_subject_measurement_series_keys", "Claves de evolucion longitudinal consultadas subjectRef=" + subjectRef);
        return measurementSeriesService.availableSeriesKeys(subjectRef);
    }

    /** Stage 84-I: one measurement's effective value across N Studies, chronologically ascending. */
    @GetMapping("/{subjectRef}/measurement-series")
    public MeasurementSeriesDto measurementSeries(
        @PathVariable String subjectRef,
        @RequestParam String level,
        @RequestParam String plane,
        @RequestParam String structure,
        @RequestParam String metric,
        HttpServletRequest request
    ) {
        accessAuditService.record(request, "access_subject_measurement_series", "Evolucion longitudinal de una medicion consultada subjectRef=" + subjectRef);
        return measurementSeriesService.series(subjectRef, level, plane, structure, metric);
    }
}
