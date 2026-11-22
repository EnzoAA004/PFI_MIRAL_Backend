package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Etapa 79-C: elige UNA {@link AiProductAnalysisRun} por Study para usar en la comparación
 * longitudinal, de forma determinística. No se encontró ninguna convención de selección existente en el dominio para
 * esto (reconocimiento de la etapa 79-A: terreno virgen) — esta es la regla explícita elegida, documentada acá en lugar
 * de dejarla implícita:
 *
 * <ol>
 *   <li>Solo las corridas {@code completed}/{@code partial} son elegibles — una {@code failed} nunca es
 *       utilizable, sin importar cuán reciente sea (etapa 79-C punto 3).</li>
 *   <li>Entre las corridas elegibles, si al menos una tiene una revisión a nivel de corrida {@code accepted} o
 *       {@code observed} (etapa 78), gana la más reciente (por {@code createdAt}) de ESAS corridas — una
 *       corrida revisada profesionalmente se prefiere a una simplemente más reciente sin revisar.</li>
 *   <li>En caso contrario, gana la corrida elegible más reciente (por {@code createdAt}), sin importar el
 *       estado de revisión (incluidos {@code pending} o {@code rejected}).</li>
 * </ol>
 *
 * Una revisión {@code rejected} no saca a una corrida de la elegibilidad — rechazar una revisión es un
 * juicio profesional sobre la calidad de la corrida, no una afirmación de que no exista análisis; la etapa 79
 * no inventa una regla de exclusión más fuerte de la que el dominio realmente modela.
 */
@Component
public class EffectiveAnalysisRunSelector {
    private static final Set<String> USABLE_STATUSES = Set.of("completed", "partial");
    private static final Set<String> PREFERRED_REVIEW_STATUSES = Set.of("accepted", "observed");

    private final StudyRepository repository;

    public EffectiveAnalysisRunSelector(StudyRepository repository) {
        this.repository = repository;
    }

    public Optional<AiProductAnalysisRun> selectEffectiveRun(String studyId) {
        List<AiProductAnalysisRun> usable = repository.findAiProductAnalysisRunsByStudyId(studyId).stream()
            .filter(run -> USABLE_STATUSES.contains(run.status()))
            .toList();
        if (usable.isEmpty()) return Optional.empty();

        Optional<AiProductAnalysisRun> preferredReviewed = usable.stream()
            .filter(this::hasPreferredReview)
            .max(Comparator.comparing(AiProductAnalysisRun::createdAt));
        return preferredReviewed.isPresent()
            ? preferredReviewed
            : usable.stream().max(Comparator.comparing(AiProductAnalysisRun::createdAt));
    }

    private boolean hasPreferredReview(AiProductAnalysisRun run) {
        return repository.findAiProductAnalysisRunReview(run.id())
            .map(review -> PREFERRED_REVIEW_STATUSES.contains(review.reviewStatus()))
            .orElse(false);
    }
}
