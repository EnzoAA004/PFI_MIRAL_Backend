package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.MeasurementSeriesDto;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.SubjectSummaryDto;
import ar.edu.uade.pfi.backend.service.PatientHistoryService;
import ar.edu.uade.pfi.backend.service.PatientMeasurementSeriesService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.service.SubjectListService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/subjects")
public class SubjectHistoryController {
    private final PatientHistoryService patientHistoryService;
    private final PatientMeasurementSeriesService measurementSeriesService;
    private final SubjectListService subjectListService;
    private final ProfessionalAccessAuditService accessAuditService;
    private final RoleAuthorizationService authorizationService;

    public SubjectHistoryController(
        PatientHistoryService patientHistoryService,
        PatientMeasurementSeriesService measurementSeriesService,
        SubjectListService subjectListService,
        ProfessionalAccessAuditService accessAuditService
    ) {
        this.patientHistoryService = patientHistoryService;
        this.measurementSeriesService = measurementSeriesService;
        this.subjectListService = subjectListService;
        this.accessAuditService = accessAuditService;
        this.authorizationService = null;
    }

    @Autowired
    public SubjectHistoryController(
        PatientHistoryService patientHistoryService,
        PatientMeasurementSeriesService measurementSeriesService,
        SubjectListService subjectListService,
        ProfessionalAccessAuditService accessAuditService,
        RoleAuthorizationService authorizationService
    ) {
        this.patientHistoryService = patientHistoryService;
        this.measurementSeriesService = measurementSeriesService;
        this.subjectListService = subjectListService;
        this.accessAuditService = accessAuditService;
        this.authorizationService = authorizationService;
    }

    /**
     * Stage 85-B: the "patient list" projection -- grouped by {@code Study.subjectRef}, never a
     * persisted entity. Replaces the never-implemented {@code GET /api/patients} the old,
     * now-retired {@code PatientDetailView}/{@code PatientsView} called (Stage 84 finding).
     */
    @GetMapping
    public List<SubjectSummaryDto> list(HttpServletRequest request) {
        accessAuditService.record(request, "access_subject_list", "Listado de pacientes de-identificado consultado");
        return authorizationService == null ? subjectListService.list() : subjectListService.list(authorizationService.accessibleStudyCaseIds(request));
    }

    @GetMapping("/{subjectRef}/history")
    public Map<String, Object> history(@PathVariable String subjectRef, HttpServletRequest request) {
        List<String> accessible = accessibleCases(request, subjectRef);
        accessAuditService.record(request, "access_subject_history", "Historial longitudinal de-identificado consultado subjectRef=" + subjectRef);
        return patientHistoryService.history(subjectRef, accessible);
    }

    /** Stage 84-M: which measurement keys have at least 2 comparable Studies -- for the Frontend's series selector. */
    @GetMapping("/{subjectRef}/measurement-series/keys")
    public List<MeasurementSeriesDto.MeasurementSeriesKeyDto> availableSeriesKeys(@PathVariable String subjectRef, HttpServletRequest request) {
        List<String> accessible = accessibleCases(request, subjectRef);
        accessAuditService.record(request, "access_subject_measurement_series_keys", "Claves de evolucion longitudinal consultadas subjectRef=" + subjectRef);
        return measurementSeriesService.availableSeriesKeys(subjectRef, accessible);
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
        List<String> accessible = accessibleCases(request, subjectRef);
        accessAuditService.record(request, "access_subject_measurement_series", "Evolucion longitudinal de una medicion consultada subjectRef=" + subjectRef);
        return measurementSeriesService.series(subjectRef, level, plane, structure, metric, accessible);
    }

    private List<String> accessibleCases(HttpServletRequest request, String subjectRef) {
        if (authorizationService == null) return null;
        authorizationService.requireSubjectAccess(request, subjectRef);
        return authorizationService.accessibleStudyCaseIds(request);
    }
}
