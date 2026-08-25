package ar.edu.uade.pfi.backend.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.service.AiProductUpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wire-level contract test for POST /v1/product/analyze (stage 75-A/75-F). Stubs the real AI
 * Module response shape behind an actual HTTP server — no FastAPI process, no model, no GPU.
 */
class AiServiceClientProductAnalyzeWireMockTest {
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearTrace() {
        MDC.clear();
    }

    private AiServiceClient buildClient(int timeoutSeconds) {
        WebClient webClient = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
        return buildClient(webClient, timeoutSeconds);
    }

    private AiServiceClient buildClient(WebClient webClient, int timeoutSeconds) {
        return new AiServiceClient(
            webClient,
            new AiServiceProperties(wireMock.baseUrl(), timeoutSeconds, "v1"),
            new AiMultiplanarV2RequestMapper(),
            new AiMultiplanarV2ResponseAdapter(objectMapper),
            new AiMultiplanarV1ResponseAdapter(),
            new ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator(),
            new ar.edu.uade.pfi.backend.service.MultiplanarV2RealBaselineValidator(),
            objectMapper
        );
    }

    private String completedJson() {
        return """
            {
              "schemaVersion": "pfi.ai-product-result.v1",
              "runId": "product-abc123",
              "traceId": "trace-wiremock-1",
              "caseId": "case-1",
              "release": {"releaseId": "post-e50-ai-product-freeze-v1"},
              "status": "completed",
              "planes": {"sagittal": {"status": "available", "runtimeAvailable": true, "reviewRequired": true}, "axial": {"status": "not_requested", "runtimeAvailable": false, "reviewRequired": true}},
              "discLevels": [],
              "warnings": [],
              "review": {"required": true, "status": "pending"},
              "humanReviewRequired": true,
              "autonomousDiagnosis": false,
              "notClinicalDiagnosis": true
            }
            """;
    }

    private String partialJson() {
        return """
            {
              "schemaVersion": "pfi.ai-product-result.v1",
              "runId": "product-abc123",
              "traceId": "trace-wiremock-1",
              "caseId": "case-1",
              "release": {"releaseId": "post-e50-ai-product-freeze-v1"},
              "status": "partial",
              "planes": {"sagittal": {"status": "unavailable", "runtimeAvailable": false, "reviewRequired": true}, "axial": {"status": "not_requested", "runtimeAvailable": false, "reviewRequired": true}},
              "discLevels": [{"level": null, "localization": {"status": "unavailable", "validated": true, "source": "67B2"}, "axialPairing": {"status": "not_requested", "validated": false, "experimental": true, "reviewRequired": true}, "measurements": [], "degenerativeFindings": {"status": "blocked_preprocessing_parity", "automaticInferenceEnabled": false, "reviewRequired": true}}],
              "warnings": ["67B2_RUNTIME_PARITY_NOT_PROVEN"],
              "review": {"required": true, "status": "pending"},
              "humanReviewRequired": true,
              "autonomousDiagnosis": false,
              "notClinicalDiagnosis": true
            }
            """;
    }

    private String failedJson() {
        return """
            {
              "schemaVersion": "pfi.ai-product-result.v1",
              "runId": "product-abc123",
              "traceId": "trace-wiremock-1",
              "caseId": "case-1",
              "release": {"releaseId": "post-e50-ai-product-freeze-v1"},
              "status": "failed",
              "planes": {"sagittal": {"status": "unavailable", "runtimeAvailable": false, "reviewRequired": true}, "axial": {"status": "unavailable", "runtimeAvailable": false, "reviewRequired": true}},
              "discLevels": [],
              "warnings": ["sagittal_pipeline_unavailable", "axial_pipeline_unavailable"],
              "review": {"required": true, "status": "pending"},
              "humanReviewRequired": true,
              "autonomousDiagnosis": false,
              "notClinicalDiagnosis": true
            }
            """;
    }

    private AiProductAnalyzeRequestDto request(String traceId) {
        return new AiProductAnalyzeRequestDto("case-1", "input-sagittal-1", null, Map.of("traceId", traceId));
    }

    /** Test 1: AI completed -> Spring accepts and returns it. */
    @Test
    void completedStatusIsAcceptedAndReturned() {
        wireMock.stubFor(post(urlEqualTo("/v1/product/analyze")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(completedJson())));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-wiremock-1");

        AiProductAnalyzeResponseDto response = buildClient(30).analyzeProduct(request("trace-wiremock-1"));

        assertEquals("completed", response.status());
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/product/analyze")));
    }

    /** Test 2: AI partial -> NOT an error, returned with its warnings intact. */
    @Test
    void partialStatusIsNotAnErrorAndPreservesWarnings() {
        wireMock.stubFor(post(urlEqualTo("/v1/product/analyze")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(partialJson())));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-wiremock-1");

        AiProductAnalyzeResponseDto response = buildClient(30).analyzeProduct(request("trace-wiremock-1"));

        assertEquals("partial", response.status());
        assertEquals(1, response.warnings().size());
    }

    /** Test 3: AI failed -> represented as status=failed, not thrown as an exception. */
    @Test
    void failedStatusIsRepresentedNotThrown() {
        wireMock.stubFor(post(urlEqualTo("/v1/product/analyze")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(failedJson())));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-wiremock-1");

        AiProductAnalyzeResponseDto response = buildClient(30).analyzeProduct(request("trace-wiremock-1"));

        assertEquals("failed", response.status());
    }

    /** Test 16: FastAPI 400 -> client/request error, upstream body never surfaced. */
    @Test
    void fastApi400IsTranslatedToClientRequestError() {
        wireMock.stubFor(post(urlEqualTo("/v1/product/analyze")).willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json").withBody("{\"detail\":\"at_least_one_registered_input_required\"}")));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-wiremock-1");

        AiProductUpstreamException ex = assertThrows(AiProductUpstreamException.class, () -> buildClient(30).analyzeProduct(request("trace-wiremock-1")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("AI_INVALID_REQUEST", ex.code());
    }

    /** Test 17: FastAPI 500 -> upstream error (BAD_GATEWAY), body never surfaced. */
    @Test
    void fastApi500IsTranslatedToUpstreamError() {
        wireMock.stubFor(post(urlEqualTo("/v1/product/analyze")).willReturn(aResponse().withStatus(500).withBody("internal trace nobody should see")));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-wiremock-1");

        AiProductUpstreamException ex = assertThrows(AiProductUpstreamException.class, () -> buildClient(30).analyzeProduct(request("trace-wiremock-1")));

        assertEquals(HttpStatus.BAD_GATEWAY, ex.status());
        assertEquals("AI_MODULE_ERROR", ex.code());
    }

    /**
     * Test 18: a slow/hanging AI Module is translated to a controlled upstream error, never left
     * hanging indefinitely and never surfacing a raw connector exception to the caller.
     *
     * <p>{@code AiServiceProperties.resolvedTimeoutSeconds()} enforces a 30s floor on the
     * {@code .block(timeout)} ceiling (by design — see {@code AiServiceTimeoutsTest}), so this
     * test does not wait 30s: it configures a short connector-level {@code responseTimeout}
     * directly on the WebClient, independent of that floor, to force a real transport timeout in
     * well under a second.
     */
    @Test
    void timeoutIsTranslatedToControlledUpstreamErrorNotLeftHanging() {
        wireMock.stubFor(post(urlEqualTo("/v1/product/analyze")).willReturn(aResponse().withStatus(200).withFixedDelay(1500).withBody(completedJson())));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-wiremock-1");

        reactor.netty.http.client.HttpClient shortTimeoutHttpClient = reactor.netty.http.client.HttpClient.create()
            .responseTimeout(java.time.Duration.ofMillis(300));
        WebClient webClient = WebClient.builder()
            .baseUrl(wireMock.baseUrl())
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(shortTimeoutHttpClient))
            .build();

        AiProductUpstreamException ex = assertThrows(AiProductUpstreamException.class, () -> buildClient(webClient, 30).analyzeProduct(request("trace-wiremock-1")));

        // Controlled, never left hanging: a 5xx-family status, never a raw connector exception.
        assertEquals(5, ex.status().value() / 100);
    }

    /** Contract violation propagates from a 200 response that fails the frozen release identity. */
    @Test
    void wrongReleaseInWireResponseIsContractViolationNotSuccess() {
        String wrongRelease = completedJson().replace("post-e50-ai-product-freeze-v1", "some-other-release");
        wireMock.stubFor(post(urlEqualTo("/v1/product/analyze")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(wrongRelease)));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-wiremock-1");

        assertThrows(ar.edu.uade.pfi.backend.service.AiProductContractViolationException.class, () -> buildClient(30).analyzeProduct(request("trace-wiremock-1")));
    }
}
