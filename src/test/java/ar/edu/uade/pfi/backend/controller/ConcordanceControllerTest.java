package ar.edu.uade.pfi.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.ConcordanceRuleSetV1;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConcordanceControllerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuditService auditService = new AuditService(new InMemoryStudyRepository());
        RoleAuthorizationService authorizationService = new RoleAuthorizationService(auditService);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ConcordanceController(new ConcordanceRuleSetV1(), authorizationService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
    }

    @Test
    void requiresAuthenticatedProfessional() throws Exception {
        mockMvc.perform(post("/api/studies/CASE-1/analytics/concordance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"evidence\":[]}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void evaluatesConcordantEvidenceEndToEnd() throws Exception {
        String body = """
            {"evidence":[
              {"id":"e1","source":"disc_level_batch","level":"L4-L5","side":"left","phenomenon":"subarticular_stenosis","finding":"moderate","experimental":true},
              {"id":"e2","source":"subarticular_roi","level":"L4-L5","side":"left","phenomenon":"subarticular_stenosis","finding":"moderate","experimental":true}
            ]}
            """;
        mockMvc.perform(post("/api/studies/CASE-1/analytics/concordance").with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].state").value("CONCORDANT"))
            .andExpect(jsonPath("$[0].ruleVersion").value("v1"));
    }

    @Test
    void emptyEvidenceListReturnsEmptyResultsNotAnError() throws Exception {
        mockMvc.perform(post("/api/studies/CASE-1/analytics/concordance").with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"evidence\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    private static RequestPostProcessor reviewer() {
        return request -> {
            request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("reviewer-id", "doctor@pfi.local", "", List.of("REVIEWER")));
            return request;
        };
    }
}
