package ar.edu.uade.pfi.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackCandidate;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.ProfessionalFeedbackCaptureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** EXT-07D/EXT-05: capture and curation only — never training. */
class ProfessionalFeedbackControllerTest {
    private ProfessionalFeedbackCaptureService captureService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        captureService = mock(ProfessionalFeedbackCaptureService.class);
        AuditService auditService = new AuditService(new InMemoryStudyRepository());
        RoleAuthorizationService authorizationService = new RoleAuthorizationService(auditService);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ProfessionalFeedbackController(captureService, authorizationService, new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
    }

    @Test
    void captureRequiresProfessionalRole() throws Exception {
        mockMvc.perform(post("/api/studies/CASE-FB/feedback/capture"))
            .andExpect(status().isForbidden());
    }

    @Test
    void captureReturnsCapturedCandidatesAndNeverLeaksReviewedBy() throws Exception {
        when(captureService.captureForStudy("CASE-FB")).thenReturn(List.of(candidate("cand-1", "dr.reviewer@pfi.local", "captured")));

        mockMvc.perform(post("/api/studies/CASE-FB/feedback/capture").with(reviewer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("cand-1"))
            .andExpect(jsonPath("$[0].aiOriginalValue").value(12.1))
            .andExpect(jsonPath("$[0].professionalValue").value(12.3))
            .andExpect(jsonPath("$[0].status").value("captured"))
            .andExpect(jsonPath("$[0].reviewedBy").doesNotExist());
    }

    @Test
    void listReturnsCandidatesForTheStudy() throws Exception {
        when(captureService.findByCase(eq("CASE-FB"), any())).thenReturn(List.of(candidate("cand-1", "dr.reviewer@pfi.local", "captured")));

        mockMvc.perform(get("/api/studies/CASE-FB/feedback-candidates").with(reviewer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].source").value("measurement"));
    }

    @Test
    void detailReturnsOneCandidateWithoutLeakingCuratorId() throws Exception {
        when(captureService.findById("cand-1")).thenReturn(java.util.Optional.of(candidate("cand-1", "dr.reviewer@pfi.local", "captured")));

        mockMvc.perform(get("/api/feedback-candidates/cand-1").with(reviewer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("cand-1"))
            .andExpect(jsonPath("$.curatorId").doesNotExist());
    }

    @Test
    void detailReturns404WhenMissing() throws Exception {
        when(captureService.findById("missing")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/feedback-candidates/missing").with(reviewer()))
            .andExpect(status().isNotFound());
    }

    @Test
    void curateRejectsReadyForTraining() throws Exception {
        when(captureService.curate(eq("cand-1"), eq("ready_for_training"), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("Estado de feedback inválido: ready_for_training"));

        mockMvc.perform(patch("/api/feedback-candidates/cand-1").with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ready_for_training\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void curateAcceptsEligibleForCurationAndDerivesCuratorFromJwtNeverFromBody() throws Exception {
        when(captureService.curate(eq("cand-1"), eq("eligible_for_curation"), eq("doctor@pfi.local"), any(), any()))
            .thenReturn(candidate("cand-1", "dr.reviewer@pfi.local", "eligible_for_curation"));

        mockMvc.perform(patch("/api/feedback-candidates/cand-1").with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"eligible_for_curation\",\"curatorId\":\"spoofed@evil.local\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("eligible_for_curation"));

        verify(captureService).curate("cand-1", "eligible_for_curation", "doctor@pfi.local", null, null);
    }

    @Test
    void curateExcludedCarriesTheExclusionReason() throws Exception {
        when(captureService.curate(eq("cand-1"), eq("excluded"), any(), eq("no representativo"), any()))
            .thenReturn(candidateExcluded("cand-1", "no representativo"));

        mockMvc.perform(patch("/api/feedback-candidates/cand-1").with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"excluded\",\"reason\":\"no representativo\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exclusionReason").value("no representativo"));
    }

    private static ProfessionalFeedbackCandidate candidate(String id, String reviewedBy, String status) {
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        return new ProfessionalFeedbackCandidate(
            id, "study-1", "CASE-FB", "run-1", "measurement", "correction-1",
            "L4-L5", "sagittal", "disc", "majorAxisMm", null,
            12.1, null, 12.3, null,
            null, null, null,
            reviewedBy, null, status,
            Instant.parse("2026-09-04T11:00:00Z"), now,
            null, null, null
        );
    }

    private static ProfessionalFeedbackCandidate candidateExcluded(String id, String exclusionReason) {
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        return new ProfessionalFeedbackCandidate(
            id, "study-1", "CASE-FB", "run-1", "measurement", "correction-1",
            "L4-L5", "sagittal", "disc", "majorAxisMm", null,
            12.1, null, 12.3, null,
            null, null, null,
            "dr.reviewer@pfi.local", null, "excluded",
            Instant.parse("2026-09-04T11:00:00Z"), now,
            "doctor@pfi.local", now, exclusionReason
        );
    }

    private static RequestPostProcessor reviewer() {
        return request -> {
            request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("reviewer-id", "doctor@pfi.local", "", List.of("REVIEWER")));
            return request;
        };
    }
}
