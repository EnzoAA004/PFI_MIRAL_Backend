package ar.edu.uade.pfi.backend.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.config.LlmProperties;
import ar.edu.uade.pfi.backend.service.RunReviewException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Stage 80-C: wire-level contract test for {@code SelfHostedLlmProvider} against
 * {@code POST /v1/chat/completions} — stubs the OpenAI-chat-completions-shaped response behind a
 * real HTTP server, no real LLM/GPU involved. Mirrors
 * {@code AiServiceClientProductAnalyzeWireMockTest}'s exact WireMock setup style.
 */
class SelfHostedLlmProviderTest {
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private SelfHostedLlmProvider buildProvider(int timeoutSeconds) {
        WebClient webClient = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
        return buildProvider(webClient, timeoutSeconds);
    }

    private SelfHostedLlmProvider buildProvider(WebClient webClient, int timeoutSeconds) {
        LlmProperties properties = new LlmProperties(true, "self-hosted", wireMock.baseUrl(), "Qwen2.5-7B-Instruct", timeoutSeconds, 0.1, 512);
        return new SelfHostedLlmProvider(webClient, properties);
    }

    private LlmChatRequest sampleRequest() {
        return new LlmChatRequest(List.of(new LlmChatMessage("system", "instructions"), new LlmChatMessage("user", "pregunta")), 0.1, 512);
    }

    private String successBody(String content) {
        return """
            {"choices": [{"message": {"role": "assistant", "content": "%s"}}]}
            """.formatted(content);
    }

    @Test
    void successfulResponseIsParsedIntoDomainResult() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody("Hay 1 medicion disponible."))));

        LlmChatResult result = buildProvider(30).complete(sampleRequest());

        assertEquals("Hay 1 medicion disponible.", result.content());
        assertEquals("Qwen2.5-7B-Instruct", result.model());
        assertEquals("self-hosted", result.provider());
    }

    @Test
    void requestIsMappedToOpenAiChatCompletionsShape() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody("ok"))));

        buildProvider(30).complete(sampleRequest());

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
            .withRequestBody(equalToJson("""
                {
                  "model": "Qwen2.5-7B-Instruct",
                  "messages": [
                    {"role": "system", "content": "instructions"},
                    {"role": "user", "content": "pregunta"}
                  ],
                  "temperature": 0.1,
                  "max_tokens": 512
                }
                """, true, true)));
    }

    @Test
    void temperatureAndModelAreConfigDriven() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody("ok"))));
        LlmProperties customProperties = new LlmProperties(true, "self-hosted", wireMock.baseUrl(), "custom-model", 30, 0.7, 128);
        WebClient webClient = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
        SelfHostedLlmProvider provider = new SelfHostedLlmProvider(webClient, customProperties);

        provider.complete(new LlmChatRequest(List.of(new LlmChatMessage("user", "hola")), 0.7, 128));

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
            .withRequestBody(equalToJson("""
                {"model": "custom-model", "messages": [{"role": "user", "content": "hola"}], "temperature": 0.7, "max_tokens": 128}
                """, true, true)));
    }

    @Test
    void clientErrorIsTranslatedToControlledUpstreamError() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(400).withBody("stack trace nadie deberia ver")));

        RunReviewException ex = assertThrows(RunReviewException.class, () -> buildProvider(30).complete(sampleRequest()));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.status());
        assertEquals("LLM_PROVIDER_UNAVAILABLE", ex.code());
    }

    @Test
    void serverErrorIsTranslatedToControlledUpstreamErrorNeverLeakingBody() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(500).withBody("internal trace nobody should see")));

        RunReviewException ex = assertThrows(RunReviewException.class, () -> buildProvider(30).complete(sampleRequest()));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.status());
        assertEquals("LLM_PROVIDER_UNAVAILABLE", ex.code());
        assertEquals(false, ex.getMessage().contains("internal trace"));
    }

    @Test
    void timeoutIsTranslatedToControlledErrorNotLeftHanging() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200).withFixedDelay(1500).withHeader("Content-Type", "application/json").withBody(successBody("tarde"))));
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMillis(300));
        WebClient shortTimeoutClient = WebClient.builder()
            .baseUrl(wireMock.baseUrl())
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
            .build();

        RunReviewException ex = assertThrows(RunReviewException.class, () -> buildProvider(shortTimeoutClient, 30).complete(sampleRequest()));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex.status());
        assertEquals("LLM_PROVIDER_TIMEOUT", ex.code());
    }

    @Test
    void missingChoicesIsAContractViolationNotASilentEmptyAnswer() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));

        RunReviewException ex = assertThrows(RunReviewException.class, () -> buildProvider(30).complete(sampleRequest()));
        assertEquals("LLM_CONTRACT_VIOLATION", ex.code());
    }

    @Test
    void blankContentIsAContractViolation() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody(""))));

        assertThrows(RunReviewException.class, () -> buildProvider(30).complete(sampleRequest()));
    }

    @Test
    void malformedChoicesArrayIsAContractViolation() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"choices\": \"not-a-list\"}")));

        assertThrows(RunReviewException.class, () -> buildProvider(30).complete(sampleRequest()));
    }
}
