package ar.edu.uade.pfi.backend.domain;

/**
 * Las cuatro etiquetas conceptuales de EXT-04B1 para un {@link AiEvidenceRegion} — siempre DERIVADAS, nunca
 * persistidas como columna propia (EXT-04B1 principio 1: ninguna segunda máquina de estados independiente).
 *
 * <p>Se calculan puramente a partir de {@code origin} (ai|professional), de si existe un
 * {@link AiEvidenceRegionCorrection} para la región, y del vocabulario de revisión ya
 * modelado por {@code AiDiscLevelReview}/{@code AiProductAnalysisRunReview}
 * ({@code pending|accepted|observed|rejected}) — reutilizado tal cual, nunca recodificado.
 *
 * <p>{@code observed} se trata exactamente igual que {@code accepted} acá (ambos cuentan como
 * "validado"), el mismo precedente que {@code ar.edu.uade.pfi.backend.service.EffectiveAnalysisRunSelector}
 * ya sentó al agruparlos en un único conjunto {@code PREFERRED_REVIEW_STATUSES} para la selección de corridas.
 * Inventar una lectura distinta de "observed" para las regiones de evidencia que la ya usada
 * para la selección de corridas recrearía el riesgo de "dos interpretaciones divergentes del mismo vocabulario"
 * que este diseño evita explícitamente.
 */
public enum DerivedEvidenceOrigin {
    AI_SUGGESTED,
    PROFESSIONAL_VALIDATED,
    PROFESSIONAL_CORRECTED,
    PROFESSIONAL_DRAWN;

    private static final String ORIGIN_PROFESSIONAL = "professional";
    private static final String REVIEW_ACCEPTED = "accepted";
    private static final String REVIEW_OBSERVED = "observed";

    public static DerivedEvidenceOrigin derive(String origin, boolean hasCorrection, String reviewStatus) {
        if (hasCorrection) return PROFESSIONAL_CORRECTED;
        if (ORIGIN_PROFESSIONAL.equals(origin)) return PROFESSIONAL_DRAWN;
        boolean validated = REVIEW_ACCEPTED.equals(reviewStatus) || REVIEW_OBSERVED.equals(reviewStatus);
        return validated ? PROFESSIONAL_VALIDATED : AI_SUGGESTED;
    }
}
