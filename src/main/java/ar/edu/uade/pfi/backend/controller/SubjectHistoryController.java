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
     * Etapa 85-B: la proyección de "lista de pacientes" -- agrupada por {@code Study.subjectRef}, nunca una
     * entidad persistida. Reemplaza al nunca implementado {@code GET /api/patients} que llamaban las viejas
     * {@code PatientDetailView}/{@code PatientsView}, ya retiradas (hallazgo de la Etapa 84).
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

    /** Etapa 84-M: qué claves de medición tienen al menos 2 Studies comparables -- para el selector de series del Frontend. */
    @GetMapping("/{subjectRef}/measurement-series/keys")
    public List<MeasurementSeriesDto.MeasurementSeriesKeyDto> availableSeriesKeys(@PathVariable String subjectRef, HttpServletRequest request) {
        List<String> accessible = accessibleCases(request, subjectRef);
        accessAuditService.record(request, "access_subject_measurement_series_keys", "Claves de evolucion longitudinal consultadas subjectRef=" + subjectRef);
        return measurementSeriesService.availableSeriesKeys(subjectRef, accessible);
    }

    /** Etapa 84-I: el valor efectivo de una medición a lo largo de N Studies, en orden cronológico ascendente. */
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
