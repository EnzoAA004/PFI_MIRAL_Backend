package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.DerivedEvidenceOrigin;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * EXT-04B2: resuelve, para cada {@link AiEvidenceRegion} de una corrida, cuál geometría es la
 * "efectiva" para mostrar — inspirado en {@link EffectiveMeasurementResolver} pero NO una
 * copia mecánica, porque la efectividad de una región también depende de si fue rechazada
 * (una medición escalar no tiene ese filtro) y de una política controlada por quien llama para las sugerencias de IA
 * todavía pendientes.
 *
 * <p>Política (EXT-04B1 sección 11, confirmada contra el sistema de revisión que realmente existe):
 * <ol>
 *   <li>existe una corrección → la geometría corregida, siempre efectiva;</li>
 *   <li>{@code origin="professional"} (dibujada a mano, sin original de IA) → su propia geometría, siempre
 *       efectiva;</li>
 *   <li>{@code origin="ai"}, revisión {@code accepted} u {@code observed} → la geometría de la IA,
 *       efectiva;</li>
 *   <li>{@code origin="ai"}, revisión {@code pending} → la geometría de la IA, efectiva solo si
 *       quien llama lo habilita ({@code includeUnreviewedSuggestions}) — ocultar una sugerencia sin revisar
 *       por defecto es una decisión de política de producto/UI, no de resolución de datos, así que este resolver
 *       expone el flag en lugar de decidir unilateralmente;</li>
 *   <li>{@code origin="ai"}, revisión {@code rejected}, sin corrección → nunca efectiva. Un
 *       rechazo seguido más tarde por una corrección deja de ser "la sugerencia rechazada de la
 *       IA" y pasa a ser el caso 1 — un profesional que vuelve a autorar activamente la
 *       geometría es una señal más fuerte y específica que un rechazo previo más grueso.</li>
 * </ol>
 *
 * <p>{@code observed} se trata exactamente igual que {@code accepted} (ver el javadoc de {@link DerivedEvidenceOrigin})
 * — la misma lectura que {@link EffectiveAnalysisRunSelector} ya le da para la selección
 * de corridas. Nada acá borra ni oculta nunca el historial: una región rechazada/pendiente se sigue
 * devolviendo (solo {@link EffectiveAnnotation#effective()} es {@code false}) — resolver
 * "effective" es una proyección, nunca un borrado (principio de EXT-04B1: "no esconder evidencia
 * histórica").
 */
@Component
public class EffectiveAnnotationResolver {
    private static final String REVIEW_REJECTED = "rejected";
    private static final String REVIEW_PENDING = "pending";

    private final StudyRepository repository;

    public EffectiveAnnotationResolver(StudyRepository repository) {
        this.repository = repository;
    }

    public record EffectiveAnnotation(
        AiEvidenceRegion region,
        String effectiveGeometryType,
        Map<String, Object> effectiveGeometry,
        DerivedEvidenceOrigin derivedOrigin,
        String reviewStatus,
        boolean effective
    ) {
    }

    public List<EffectiveAnnotation> effectiveAnnotations(AiProductAnalysisRun run, boolean includeUnreviewedSuggestions) {
        List<AiEvidenceRegion> regions = repository.findAiEvidenceRegionsByAnalysisRunId(run.id());
        if (regions.isEmpty()) return List.of();

        Map<String, AiEvidenceRegionCorrection> latestCorrections = latestCorrectionsByRegion(
            repository.findAiEvidenceRegionCorrectionsByAnalysisRunId(run.id())
        );
        Map<String, AiDiscLevelReview> discLevelReviews = new HashMap<>();
        for (AiDiscLevelReview review : repository.findAiDiscLevelReviewsByAnalysisRunId(run.id())) {
            discLevelReviews.put(review.discLevelResultId(), review);
        }
        Optional<AiProductAnalysisRunReview> runReview = repository.findAiProductAnalysisRunReview(run.id());

        List<EffectiveAnnotation> results = new ArrayList<>();
        for (AiEvidenceRegion region : regions) {
            AiEvidenceRegionCorrection correction = latestCorrections.get(region.id());
            boolean hasCorrection = correction != null;
            String reviewStatus = resolveReviewStatus(region, discLevelReviews, runReview);
            DerivedEvidenceOrigin derived = DerivedEvidenceOrigin.derive(region.origin(), hasCorrection, reviewStatus);

            boolean rejectedAndUncorrected = !hasCorrection && "ai".equals(region.origin()) && REVIEW_REJECTED.equals(reviewStatus);
            boolean effective = switch (derived) {
                case PROFESSIONAL_CORRECTED, PROFESSIONAL_DRAWN, PROFESSIONAL_VALIDATED -> true;
                case AI_SUGGESTED -> !rejectedAndUncorrected && includeUnreviewedSuggestions;
            };

            String effectiveType = hasCorrection ? correction.correctedGeometryType() : region.geometryType();
            Map<String, Object> effectiveGeometry = hasCorrection ? correction.correctedGeometry() : region.geometry();

            results.add(new EffectiveAnnotation(region, effectiveType, effectiveGeometry, derived, reviewStatus, effective));
        }
        return results;
    }

    /**
     * El mismo fallback que {@link ar.edu.uade.pfi.backend.service.StudyReportService#discLevels}
     * ya aplica: una revisión de nivel de disco cuando la región nombra uno, la revisión a nivel de corrida
     * en caso contrario, {@code "pending"} cuando no existe ninguna.
     */
    private String resolveReviewStatus(
        AiEvidenceRegion region,
        Map<String, AiDiscLevelReview> discLevelReviews,
        Optional<AiProductAnalysisRunReview> runReview
    ) {
        if (region.discLevelResultId() != null) {
            AiDiscLevelReview review = discLevelReviews.get(region.discLevelResultId());
            if (review != null) return review.reviewStatus();
        }
        return runReview.map(AiProductAnalysisRunReview::reviewStatus).orElse(REVIEW_PENDING);
    }

    private Map<String, AiEvidenceRegionCorrection> latestCorrectionsByRegion(List<AiEvidenceRegionCorrection> corrections) {
        Map<String, AiEvidenceRegionCorrection> latest = new HashMap<>();
        for (AiEvidenceRegionCorrection correction : corrections) {
            AiEvidenceRegionCorrection existing = latest.get(correction.evidenceRegionId());
            if (existing == null || correction.createdAt().isAfter(existing.createdAt())) {
                latest.put(correction.evidenceRegionId(), correction);
            }
        }
        return latest;
    }
}
