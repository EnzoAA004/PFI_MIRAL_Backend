package ar.edu.uade.pfi.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

class WebClientContextTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiServiceProperties.class, LlmProperties.class})
    static class PropertiesConfig {}

    @Test
    void bindsBothServicesAndBuildsBothClients() {
        new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, WebClientConfig.class)
            .withPropertyValues("pfi.llm.model=audit-model")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(LlmProperties.class).resolvedModel()).isEqualTo("audit-model");
                assertThat(context.getBeansOfType(WebClient.class)).containsOnlyKeys("aiWebClient", "llmWebClient");
            });
    }
}
