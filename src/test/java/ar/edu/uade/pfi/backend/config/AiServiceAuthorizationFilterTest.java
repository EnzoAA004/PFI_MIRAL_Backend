package ar.edu.uade.pfi.backend.config;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

class AiServiceAuthorizationFilterTest {
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void authNoneDoesNotSendAuthorizationAndPreservesTrace() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"status\":\"ok\"}")));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-auth-none");

        webClient(new NoopAiServiceAuthorizationProvider()).get()
            .uri("/health")
            .retrieve()
            .bodyToMono(String.class)
            .block();

        wireMock.verify(getRequestedFor(urlEqualTo("/health"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-auth-none")));
        wireMock.verify(exactly(0), getRequestedFor(urlEqualTo("/health"))
            .withHeader(HttpHeaders.AUTHORIZATION, matching(".*")));
    }

    @Test
    void googleAuthProviderHeaderIsAppliedCentrally() {
        wireMock.stubFor(get(urlEqualTo("/models")).willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"models\":[]}")));

        webClient(() -> Optional.of("Bearer fake-token")).get()
            .uri("/models")
            .retrieve()
            .bodyToMono(String.class)
            .block();

        wireMock.verify(getRequestedFor(urlEqualTo("/models"))
            .withHeader(HttpHeaders.AUTHORIZATION, matching("Bearer fake-token")));
    }

    @Test
    void frontendJwtAuthorizationIsNotForwardedToAiModule() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"status\":\"ok\"}")));

        webClient(() -> Optional.of("Bearer fake-token")).get()
            .uri("/health")
            .headers(headers -> headers.setBearerAuth("frontend-jwt"))
            .retrieve()
            .bodyToMono(String.class)
            .block();

        wireMock.verify(getRequestedFor(urlEqualTo("/health"))
            .withHeader(HttpHeaders.AUTHORIZATION, matching("Bearer fake-token")));
        wireMock.verify(exactly(0), getRequestedFor(urlEqualTo("/health"))
            .withHeader(HttpHeaders.AUTHORIZATION, matching("Bearer frontend-jwt")));
    }

    @Test
    void failingProviderStopsBeforeAnonymousRequestIsSent() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"status\":\"ok\"}")));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> webClient(() -> {
            throw new AiServiceAuthorizationException(new IllegalStateException("metadata.google.internal token secret"));
        }).get()
            .uri("/health")
            .retrieve()
            .bodyToMono(String.class)
            .block());

        assertEquals("AI service authorization failed.", authorizationFailure(ex).getMessage());
        wireMock.verify(exactly(0), getRequestedFor(urlEqualTo("/health")));
    }

    private WebClient webClient(AiServiceAuthorizationProvider provider) {
        AiServiceProperties properties = new AiServiceProperties(wireMock.baseUrl(), 30, "v1");
        return new WebClientConfig().aiWebClient(properties, provider);
    }

    private AiServiceAuthorizationException authorizationFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof AiServiceAuthorizationException authorizationException) {
                return authorizationException;
            }
            cursor = cursor.getCause();
        }
        throw new AssertionError("Expected AiServiceAuthorizationException in cause chain.");
    }
}
