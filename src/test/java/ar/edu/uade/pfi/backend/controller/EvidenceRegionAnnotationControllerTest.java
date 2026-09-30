package ar.edu.uade.pfi.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.ProfessionalAnnotationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** EXT-04B5: auth is the load-bearing security property here — identity always from JWT claims, never from the request body. */
class EvidenceRegionAnnotationControllerTest {
    private ProfessionalAnnotationService annotationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        annotationService = mock(ProfessionalAnnotationService.class);
        AuditService auditService = new AuditService(new InMemoryStudyRepository());
        RoleAuthorizationService authorizationService = new RoleAuthorizationService(auditService);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new EvidenceRegionAnnotationController(annotationService, authorizationService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
    }

    @Test
    void createRequiresAnAuthenticatedProfessional() throws Exception {
        mockMvc.perform(post("/api/studies/CASE-1/product-analyses/run-1/evidence-regions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plane\":\"sagittal\",\"level\":\"L4-L5\",\"structure\":\"disc\",\"sliceIndex\":6,\"geometryType\":\"BBOX\",\"geometry\":{\"x\":10,\"y\":10,\"width\":20,\"height\":20}}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void correctRequiresAnAuthenticatedProfessional() throws Exception {
        mockMvc.perform(post("/api/studies/CASE-1/product-analyses/run-1/evidence-regions/region-1/corrections")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"geometryType\":\"ELLIPSE\",\"geometry\":{\"centerX\":20,\"centerY\":20,\"radiusX\":8,\"radiusY\":6}}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void authoredByComesFromJwtClaimsNeverFromTheRequestBody() throws Exception {
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-1", "run-1", null, null, "sagittal", "L4-L5", "disc", 6,
            "professional", "BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), null,
            null, null, null, null, false, null, null, "doctor@pfi.local", Instant.parse("2026-09-05T12:00:00Z")
        );
        when(annotationService.createProfessionalRegion(eq("CASE-1"), eq("run-1"), any(), eq("doctor@pfi.local"))).thenReturn(region);

        // The request body itself tries to smuggle a different identity via an unrecognised
        // "authoredBy" field -- CreateProfessionalEvidenceRegionRequestDto has no such
        // component, so Jackson silently drops it, and the controller only ever passes the
        // identity it derived from the JWT claims attribute, never anything from the body.
        mockMvc.perform(post("/api/studies/CASE-1/product-analyses/run-1/evidence-regions").with(reviewer("doctor@pfi.local"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plane\":\"sagittal\",\"level\":\"L4-L5\",\"structure\":\"disc\",\"sliceIndex\":6,\"geometryType\":\"BBOX\",\"geometry\":{\"x\":10,\"y\":10,\"width\":20,\"height\":20},\"authoredBy\":\"spoofed@evil.local\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.origin").value("professional"));

        verify(annotationService).createProfessionalRegion(eq("CASE-1"), eq("run-1"), any(), eq("doctor@pfi.local"));
    }

    @Test
    void createReturnsTheCreatedRegionWithoutLeakingAuthoredBy() throws Exception {
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-2", "run-1", null, null, "sagittal", "L4-L5", "disc", 6,
            "professional", "BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), null,
            null, null, null, null, false, "Región de interés", null, "doctor@pfi.local", Instant.parse("2026-09-05T12:00:00Z")
        );
        when(annotationService.createProfessionalRegion(eq("CASE-1"), eq("run-1"), any(), any())).thenReturn(region);

        mockMvc.perform(post("/api/studies/CASE-1/product-analyses/run-1/evidence-regions").with(reviewer("doctor@pfi.local"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plane\":\"sagittal\",\"level\":\"L4-L5\",\"structure\":\"disc\",\"sliceIndex\":6,\"geometryType\":\"BBOX\",\"geometry\":{\"x\":10,\"y\":10,\"width\":20,\"height\":20}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidenceId").value("region-2"))
            .andExpect(jsonPath("$.origin").value("professional"))
            .andExpect(jsonPath("$.authoredBy").doesNotExist());
    }

    @Test
    void correctReturnsTheCorrectionWithoutLeakingReviewedBy() throws Exception {
        AiEvidenceRegionCorrection correction = new AiEvidenceRegionCorrection(
            "correction-1", "region-1", "run-1", "BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(),
            "ELLIPSE", new EvidenceGeometry.Ellipse(20, 20, 8, 6).toMap(), null, "doctor@pfi.local", Instant.parse("2026-09-05T12:05:00Z")
        );
        when(annotationService.correctRegion(eq("CASE-1"), eq("run-1"), eq("region-1"), any(), any())).thenReturn(correction);

        mockMvc.perform(post("/api/studies/CASE-1/product-analyses/run-1/evidence-regions/region-1/corrections").with(reviewer("doctor@pfi.local"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"geometryType\":\"ELLIPSE\",\"geometry\":{\"centerX\":20,\"centerY\":20,\"radiusX\":8,\"radiusY\":6}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.correctionId").value("correction-1"))
            .andExpect(jsonPath("$.correctedGeometryType").value("ELLIPSE"))
            .andExpect(jsonPath("$.reviewedBy").doesNotExist());
    }

    private static RequestPostProcessor reviewer(String email) {
        return request -> {
            request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("reviewer-id", email, "", List.of("REVIEWER")));
            return request;
        };
    }
}
