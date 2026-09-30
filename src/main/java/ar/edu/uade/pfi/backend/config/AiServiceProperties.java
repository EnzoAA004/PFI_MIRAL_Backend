package ar.edu.uade.pfi.backend.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "pfi.ai-service")
public record AiServiceProperties(
    String baseUrl,
    Integer timeoutSeconds,
    String multiplanarContractVersion,
    Long studyUploadMaxBytes,
    String authMode,
    String audience
) {
    /**
     * Constructor canonico, marcado para el enlace de configuracion.
     *
     * <p>Sin la marca, el arranque falla: un record con dos constructores deja a
     * Spring sin saber cual usar para enlazar las propiedades, busca uno por defecto,
     * no lo encuentra y no levanta el contexto. El otro constructor existe solo por
     * comodidad de las pruebas, que no necesitan declarar el techo de subida.
     */
    @ConstructorBinding
    public AiServiceProperties(
        String baseUrl,
        Integer timeoutSeconds,
        String multiplanarContractVersion,
        Long studyUploadMaxBytes,
        String authMode,
        String audience
    ) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.multiplanarContractVersion = multiplanarContractVersion;
        this.studyUploadMaxBytes = studyUploadMaxBytes;
        this.authMode = authMode;
        this.audience = audience;
    }

    public AiServiceProperties(String baseUrl, Integer timeoutSeconds, String multiplanarContractVersion, Long studyUploadMaxBytes) {
        this(baseUrl, timeoutSeconds, multiplanarContractVersion, studyUploadMaxBytes, null, null);
    }

    public AiServiceProperties(String baseUrl, Integer timeoutSeconds, String multiplanarContractVersion) {
        this(baseUrl, timeoutSeconds, multiplanarContractVersion, null, null, null);
    }

    public String resolvedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:8000" : baseUrl;
    }

    public int resolvedTimeoutSeconds() {
        return timeoutSeconds == null ? 180 : Math.max(timeoutSeconds, 30);
    }

    public AiServiceAuthMode resolvedAuthMode() {
        return AiServiceAuthMode.fromConfigValue(authMode);
    }

    /**
     * For Cloud Run ID-token auth the audience is an origin only: scheme + authority,
     * no path/query/fragment and no trailing slash. If PFI_AI_SERVICE_AUDIENCE is blank,
     * it is derived from PFI_AI_SERVICE_URL using the same fail-closed validation.
     */
    public String resolvedAudience() {
        String source = audience == null || audience.isBlank() ? resolvedBaseUrl() : audience;
        return normalizeAudience(source);
    }

    private String normalizeAudience(String source) {
        try {
            URI uri = new URI(source.trim());
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("AI service audience must be an absolute HTTP(S) origin.");
            }
            String scheme = uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("AI service audience must use HTTP(S).");
            }
            String path = uri.getRawPath();
            if (path != null && !path.isBlank() && !path.equals("/")) {
                throw new IllegalArgumentException("AI service audience must not include a path.");
            }
            return scheme + "://" + uri.getRawAuthority();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("AI service audience is invalid.", ex);
        }
    }

    /**
     * Techo para las consultas de diagnostico: salud, readiness, inventario de modelos,
     * contrato.
     *
     * <p>No comparten techo con la inferencia. Un {@code /health} esperaba lo mismo que
     * una corrida multiplanar -tres minutos- asi que un modulo de IA colgado retenia un
     * hilo del pool por request de un endpoint cuyo unico proposito es responder rapido o
     * no responder. Preguntar si esta vivo y pedirle que segmente un volumen son dos
     * cosas distintas y merecen dos paciencias distintas.
     *
     * <p>Nunca supera el techo general: si alguien configura un timeout total menor, el
     * de diagnostico lo acompana.
     */
    public int resolvedDiagnosticTimeoutSeconds() {
        return Math.min(10, resolvedTimeoutSeconds());
    }

    public AiMultiplanarContractVersion resolvedMultiplanarContractVersion() {
        return AiMultiplanarContractVersion.fromConfigValue(multiplanarContractVersion);
    }

    public long resolvedStudyUploadMaxBytes() {
        return studyUploadMaxBytes == null || studyUploadMaxBytes <= 0 ? 200L * 1024L * 1024L : studyUploadMaxBytes;
    }

    @PostConstruct
    void validateMultiplanarContractVersion() {
        resolvedMultiplanarContractVersion();
    }
}
