package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import ar.edu.uade.pfi.backend.service.StudyReportService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/studies/{caseId}")
public class StudyReportController {
    private final StudyReportService reportService;
    private final RoleAuthorizationService authorizationService;

    public StudyReportController(StudyReportService reportService, RoleAuthorizationService authorizationService) {
        this.reportService = reportService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/report")
    public StudyReportDto report(@PathVariable String caseId, @RequestParam(required = false) String baselineCaseId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyRead(request, caseId);
        if (baselineCaseId != null && !baselineCaseId.isBlank()) authorizationService.requireStudyRead(request, baselineCaseId);
        return reportService.report(caseId, baselineCaseId);
    }

    @GetMapping("/report.json")
    public ResponseEntity<StudyReportDto> reportJson(@PathVariable String caseId, @RequestParam(required = false) String baselineCaseId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyRead(request, caseId);
        if (baselineCaseId != null && !baselineCaseId.isBlank()) authorizationService.requireStudyRead(request, baselineCaseId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, attachment("study-report-" + safeFilename(caseId) + ".json"))
            .contentType(MediaType.APPLICATION_JSON)
            .body(reportService.report(caseId, baselineCaseId));
    }

    @GetMapping("/report.pdf")
    public ResponseEntity<byte[]> reportPdf(@PathVariable String caseId, @RequestParam(required = false) String baselineCaseId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyRead(request, caseId);
        if (baselineCaseId != null && !baselineCaseId.isBlank()) authorizationService.requireStudyRead(request, baselineCaseId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, attachment("study-report-" + safeFilename(caseId) + ".pdf"))
            .contentType(MediaType.APPLICATION_PDF)
            .body(reportService.reportPdf(caseId, baselineCaseId));
    }

    private String attachment(String filename) {
        return ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString();
    }

    private String safeFilename(String value) {
        return value == null ? "study" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
