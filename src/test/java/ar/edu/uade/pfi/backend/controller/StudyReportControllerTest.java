package ar.edu.uade.pfi.backend.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.StudyReportService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StudyReportControllerTest {
    private StudyReportService reportService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reportService = mock(StudyReportService.class);
        AuditService auditService = new AuditService(new InMemoryStudyRepository());
        RoleAuthorizationService authorizationService = new RoleAuthorizationService(auditService);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new StudyReportController(reportService, authorizationService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
    }

    @Test
    void reportRequiresProfessionalRole() throws Exception {
        mockMvc.perform(get("/api/studies/CASE-88/report"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void returnsJsonReportForProfessional() throws Exception {
        when(reportService.report(eq("CASE-88"), eq(null))).thenReturn(minimalReport("CASE-88"));

        mockMvc.perform(get("/api/studies/CASE-88/report").with(reviewer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schemaVersion").value(StudyReportService.SCHEMA_VERSION))
            .andExpect(jsonPath("$.reportStatus").value("ready"))
            .andExpect(jsonPath("$.caseId").value("CASE-88"))
            .andExpect(jsonPath("$.safety.notClinicalDiagnosis").value(true));

        verify(reportService).report("CASE-88", null);
    }

    @Test
    void jsonExportSetsAttachmentFilenameAndPassesBaseline() throws Exception {
        when(reportService.report(eq("CASE-88"), eq("BASE-88"))).thenReturn(minimalReport("CASE-88"));

        mockMvc.perform(get("/api/studies/CASE-88/report.json?baselineCaseId=BASE-88").with(reviewer()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("study-report-CASE-88.json")))
            .andExpect(jsonPath("$.caseId").value("CASE-88"));

        verify(reportService).report("CASE-88", "BASE-88");
    }

    @Test
    void pdfExportSetsAttachmentFilenameAndSanitizesUnsafeCaseId() throws Exception {
        when(reportService.reportPdf(eq("CASE:88 unsafe"), eq(null))).thenReturn("%PDF-1.7\n".getBytes());

        mockMvc.perform(get("/api/studies/{caseId}/report.pdf", "CASE:88 unsafe").with(reviewer()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("study-report-CASE_88_unsafe.pdf")))
            .andExpect(content().bytes("%PDF-1.7\n".getBytes()));

        verify(reportService).reportPdf("CASE:88 unsafe", null);
    }

    private StudyReportDto minimalReport(String caseId) {
        Instant now = Instant.parse("2026-08-26T12:00:00Z");
        return new StudyReportDto(
            StudyReportService.SCHEMA_VERSION,
            now,
            "ready",
            caseId,
            null,
            "MRI",
            new StudyReportDto.AnalysisSummary("run-1", "completed", "release", now, now, true),
            new StudyReportDto.ReviewSummary("pending", null, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(StudyReportService.DISCLAIMER),
            null,
            new StudyReportDto.SafetySummary(true, true, false, StudyReportService.DISCLAIMER),
            new StudyReportDto.TraceabilitySummary(caseId, "run-1", "pfi.ai-product-result.v1", "release", now)
        );
    }

    private static RequestPostProcessor reviewer() {
        return request -> {
            request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("reviewer-id", "doctor@pfi.local", "", List.of("REVIEWER")));
            return request;
        };
    }
}
