package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.util.DiscDegenerativeFindingsPublicProjection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Comparación de hallazgos discales (P10.7) entre dos corridas, calculada en el servidor para que el asistente nunca
 * tenga que hacerla (ni inventarla). Es el espejo de {@code discFindingsComparison.ts} / {@code
 * discDegenerativeFindings.ts} del Frontend: mismas reglas de lectura (sobre la misma proyección pública), mismo
 * emparejamiento (nivel × tipo), mismas incompatibilidades y mismos estados. La paridad se verifica con un archivo de
 * casos compartido (ver {@code DiscFindingsComparatorParityTest} y {@code disc-findings-parity-tests.mjs}): si una regla
 * cambia en un lado y no en el otro, esas pruebas fallan.
 *
 * <p>Los textos de motivo se redactan aquí en lenguaje llano para el asistente; lo que se verifica por paridad son los
 * estados, los códigos de no comparable y los resultados invalidados, no la redacción.
 */
public final class DiscFindingsComparator {
    public static final String SCHEMA_VERSION = "pfi.disc-degenerative-findings.v1";

    public static final List<String> LEVELS = List.of("L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1");
    public static final List<String> TYPES = List.of(
        "disc_bulging", "disc_narrowing", "upper_endplate_change", "lower_endplate_change",
        "pfirrmann_grade", "modic_change", "disc_herniation", "spondylolisthesis");
    private static final List<String> DEPLOYMENT = List.of("supported_internal", "experimental", "not_product_supported");
    private static final List<String> ROLES = List.of("sagittal_t1", "sagittal_t2");

    private DiscFindingsComparator() {
    }

    public static List<String> labelsFor(String findingType) {
        if ("pfirrmann_grade".equals(findingType)) return List.of("I", "II", "III", "IV", "V");
        if ("modic_change".equals(findingType)) return List.of("none", "I", "II", "III");
        return List.of("absent", "present");
    }

    /** Un resultado que cumple el contrato. */
    public record Finding(
        String findingId,
        String findingType,
        String level,
        String label,
        Double confidence,
        Map<String, Double> classProbabilities,
        String deploymentStatus,
        String evaluationDataset,
        boolean externalValidationAvailable,
        String modelId,
        String modelSha256,
        List<String> availableRoles
    ) {
    }

    /** Un resultado que no cumple el contrato, con lo que se pudo identificar de él. */
    public record Rejected(int index, String findingId, String level, String findingType, String deploymentStatus, String reason) {
    }

    public enum SideStatus { READY, NO_DATA, INVALID }

    public record Side(SideStatus status, String reason, List<Finding> findings, List<Rejected> rejected) {
        public static Side noData(String reason) {
            return new Side(SideStatus.NO_DATA, reason, List.of(), List.of());
        }

        public static Side invalid(String reason) {
            return new Side(SideStatus.INVALID, reason, List.of(), List.of());
        }
    }

    public record Reason(String code, String text) {
    }

    public record Row(
        String key,
        String level,
        String findingType,
        String section,
        Finding reference,
        Finding current,
        String status,
        Reason reason,
        Map<String, String> rejected
    ) {
    }

    public record Result(List<Row> rows, int[] mainCounts, int[] researchCounts, int unreadableReference, int unreadableCurrent, Side reference, Side current) {
    }

    /* ------------------------------------------------------------------ lectura */

    /**
     * Los hallazgos de una corrida, a partir de su {@code metricsSnapshot} persistido. Aplica la misma proyección pública
     * que recibe el Frontend (el vector de probabilidades crudo se reduce a la confianza de la etiqueta reportada y a la
     * distribución publicada) y valida resultado por resultado.
     */
    @SuppressWarnings("unchecked")
    public static Side read(Map<String, Object> metricsSnapshot) {
        if (metricsSnapshot == null || !(metricsSnapshot.get("discDegenerativeFindings") instanceof Map<?, ?> envelope)) {
            return Side.noData("Esta corrida no tiene hallazgos discales persistidos: no hay datos para este estudio.");
        }
        Map<String, Object> governance = metricsSnapshot.get("discDegenerativeGovernance") instanceof Map<?, ?> g ? (Map<String, Object>) g : null;
        if (governance == null) return Side.invalid("Snapshot P10.7 sin governance.");
        if (!Boolean.TRUE.equals(governance.get("humanReviewRequired"))
            || !Boolean.TRUE.equals(governance.get("notClinicalDiagnosis"))
            || !Boolean.FALSE.equals(governance.get("autonomousDiagnosis"))) {
            return Side.invalid("Snapshot P10.7 inválido: faltan flags de seguridad obligatorios.");
        }
        if (!Boolean.TRUE.equals(governance.get("predictionImmutable")) || !Boolean.TRUE.equals(governance.get("reviewStoredSeparately"))) {
            return Side.invalid("Snapshot P10.7 no separa predicción y revisión.");
        }
        Map<String, Object> publicEnvelope = DiscDegenerativeFindingsPublicProjection.sanitize((Map<String, Object>) envelope);
        if (!SCHEMA_VERSION.equals(text(publicEnvelope.get("schemaVersion")))) {
            return Side.invalid("Contrato P10.7 inválido: schemaVersion inesperado.");
        }
        if (!(publicEnvelope.get("findings") instanceof List<?> raw) || raw.isEmpty()) {
            return Side.invalid("Contrato P10.7 inválido: findings ausente o vacío.");
        }
        List<Finding> findings = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (int index = 0; index < raw.size(); index++) {
            Object entry = raw.get(index);
            String[] failure = new String[1];
            Finding finding = validate(entry, failure);
            if (finding != null) findings.add(finding);
            else rejected.add(rejectedFrom(entry, index, failure[0]));
        }
        return new Side(SideStatus.READY, null, List.copyOf(findings), List.copyOf(rejected));
    }

    @SuppressWarnings("unchecked")
    private static Finding validate(Object value, String[] failure) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            failure[0] = "el resultado no es un objeto";
            return null;
        }
        Map<String, Object> raw = (Map<String, Object>) rawMap;
        String findingId = text(raw.get("findingId"));
        String findingType = text(raw.get("findingType"));
        if (findingId.isEmpty() || !TYPES.contains(findingType)) {
            failure[0] = "identificador o tipo de clasificación desconocido";
            return null;
        }
        String level = text(map(raw.get("anatomy")).get("level"));
        if (!LEVELS.contains(level)) {
            failure[0] = "nivel anatómico desconocido";
            return null;
        }
        Map<String, Object> classification = map(raw.get("classification"));
        String label = text(classification.get("label"));
        if (classification.isEmpty() || !labelsFor(findingType).contains(label)) {
            failure[0] = "la clase no pertenece a la definición de esta clasificación";
            return null;
        }
        String expectedKind = "pfirrmann_grade".equals(findingType) || "modic_change".equals(findingType) ? "categorical" : "binary";
        if (!expectedKind.equals(classification.get("kind"))) {
            failure[0] = "el tipo de clasificación no coincide con su definición";
            return null;
        }
        Double confidence = classification.get("confidence") instanceof Number n
            && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0 && n.doubleValue() <= 1 ? n.doubleValue() : null;

        Map<String, Object> evidence = map(raw.get("evidence"));
        String deployment = text(evidence.get("deploymentStatus"));
        if (evidence.isEmpty() || !DEPLOYMENT.contains(deployment) || !(evidence.get("externalValidationAvailable") instanceof Boolean external)) {
            failure[0] = "la evidencia del modelo está incompleta";
            return null;
        }
        Map<String, Object> localization = map(raw.get("localization"));
        if (localization.isEmpty()
            || !"segmentation_derived_disc_level".equals(localization.get("source"))
            || !Boolean.TRUE.equals(localization.get("researchOnly"))
            || !Boolean.FALSE.equals(localization.get("automaticAnatomicalLocalizationValidated"))) {
            failure[0] = "la localización no declara que es de investigación y sin validar";
            return null;
        }
        Map<String, Object> model = map(raw.get("model"));
        String modelId = text(model.get("modelId"));
        String modelSha = text(model.get("modelSha256"));
        if (modelId.isEmpty() || !modelSha.matches("(?i)[a-f0-9]{64}")) {
            failure[0] = "falta la identidad del modelo o del checkpoint";
            return null;
        }
        Map<String, Object> review = map(raw.get("review"));
        if (review.isEmpty() || !Boolean.TRUE.equals(review.get("required")) || !"pending".equals(review.get("status"))
            || !Boolean.TRUE.equals(raw.get("notClinicalDiagnosis"))) {
            failure[0] = "faltan las marcas de revisión profesional obligatoria";
            return null;
        }
        List<String> roles = new ArrayList<>();
        Object sourceSeries = raw.get("sourceSeries");
        if (sourceSeries != null) {
            if (!(sourceSeries instanceof List<?> list) || list.isEmpty()) {
                failure[0] = "las series de origen declaradas no son válidas";
                return null;
            }
            for (Object item : list) {
                Map<String, Object> source = map(item);
                String role = text(source.get("role"));
                if (source.isEmpty() || !ROLES.contains(role) || !(source.get("available") instanceof Boolean available)) {
                    failure[0] = "las series de origen declaradas no son válidas";
                    return null;
                }
                if (source.get("positions") instanceof List<?> positions) {
                    for (Object position : positions) {
                        if (!(position instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 0) {
                            failure[0] = "las series de origen declaradas no son válidas";
                            return null;
                        }
                    }
                }
                if (available) roles.add(role.equals("sagittal_t1") ? "T1" : "T2");
            }
            if (roles.isEmpty()) {
                failure[0] = "las series de origen declaradas no son válidas";
                return null;
            }
        }
        Map<String, Double> distribution = classProbabilities(classification.get("classProbabilities"), findingType, label, confidence);
        return new Finding(
            findingId, findingType, level, label, confidence, distribution, deployment, text(evidence.get("evaluationDataset")),
            external, modelId, modelSha, List.copyOf(roles));
    }

    /** Igual que {@code parseClassProbabilities}: claves del catálogo, valores en [0,1], suma ≈ 1 y coherente con la confianza. */
    private static Map<String, Double> classProbabilities(Object raw, String findingType, String label, Double confidence) {
        if (!(raw instanceof Map<?, ?> record) || record.isEmpty()) return null;
        List<String> catalog = labelsFor(findingType);
        Map<String, Double> result = new LinkedHashMap<>();
        double sum = 0;
        for (Map.Entry<?, ?> entry : record.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Number n)) return null;
            double v = n.doubleValue();
            if (!catalog.contains(key) || !Double.isFinite(v) || v < 0 || v > 1) return null;
            result.put(key, v);
            sum += v;
        }
        if (Math.abs(sum - 1) > 0.01) return null;
        if (confidence != null && (!result.containsKey(label) || Math.abs(result.get(label) - confidence) > 1e-6)) return null;
        return result;
    }

    private static Rejected rejectedFrom(Object value, int index, String reason) {
        Map<String, Object> raw = map(value);
        String type = text(raw.get("findingType"));
        String level = text(map(raw.get("anatomy")).get("level"));
        String status = text(map(raw.get("evidence")).get("deploymentStatus"));
        String id = text(raw.get("findingId"));
        return new Rejected(index, id.isEmpty() ? null : id, LEVELS.contains(level) ? level : null,
            TYPES.contains(type) ? type : null, DEPLOYMENT.contains(status) ? status : null, reason);
    }

    /* ------------------------------------------------------------------ comparación */

    public static String sectionOf(Finding finding) {
        return "not_product_supported".equals(finding.deploymentStatus()) ? "research" : "main";
    }

    /** Motivo por el que dos resultados del mismo nivel y tipo no se pueden comparar, o {@code null} si son compatibles. */
    public static Reason incompatibility(Finding reference, Finding current) {
        if (!reference.findingType().equals(current.findingType())) {
            return new Reason("model_differs", "Los dos resultados no son del mismo tipo de clasificación.");
        }
        List<String> catalog = labelsFor(reference.findingType());
        if (!catalog.contains(reference.label()) || !catalog.contains(current.label())) {
            return new Reason("model_differs", "Una de las clases no pertenece a la definición de esta clasificación.");
        }
        if (!reference.modelId().equals(current.modelId())) {
            return new Reason("model_differs", "Modelo distinto (A: " + reference.modelId() + "; B: " + current.modelId() + ").");
        }
        if (!reference.modelSha256().equalsIgnoreCase(current.modelSha256())) {
            return new Reason("checkpoint_differs", "Versión distinta del checkpoint del modelo: las clases no son comparables entre versiones.");
        }
        if (!reference.deploymentStatus().equals(current.deploymentStatus())) {
            return new Reason("support_status_differs", "El estado de soporte de esta clasificación difiere entre las dos corridas.");
        }
        if (!reference.evaluationDataset().isEmpty() && !current.evaluationDataset().isEmpty()
            && !reference.evaluationDataset().equals(current.evaluationDataset())) {
            return new Reason("evaluation_basis_differs", "La base de evaluación declarada difiere entre las dos corridas.");
        }
        String referenceRoles = rolesLabel(reference);
        String currentRoles = rolesLabel(current);
        if (!referenceRoles.equals(currentRoles)) {
            return new Reason("input_modalities_differ", "Se calcularon con modalidades distintas (A: " + referenceRoles + "; B: " + currentRoles + ").");
        }
        return null;
    }

    private static String rolesLabel(Finding finding) {
        Set<String> sorted = new TreeSet<>(finding.availableRoles());
        return sorted.isEmpty() ? "sin modalidad" : String.join("+", sorted);
    }

    /** Estado de dos resultados compatibles: cambia la clase o no. La probabilidad no interviene. */
    public static String statusOf(Finding reference, Finding current) {
        if (reference.label().equals(current.label())) return "unchanged";
        boolean binary = String.join("|", labelsFor(reference.findingType())).equals("absent|present");
        if (binary && reference.label().equals("absent") && current.label().equals("present")) return "absent_to_present";
        if (binary && reference.label().equals("present") && current.label().equals("absent")) return "present_to_absent";
        return "category_changed";
    }

    private static Reason sideReason(String sideName, Side side) {
        return switch (side.status()) {
            case READY -> null;
            case NO_DATA -> new Reason("side_no_data", sideName + " no tiene hallazgos discales persistidos. Sin datos no hay «Ausente».");
            case INVALID -> new Reason("side_invalid", "Los hallazgos de " + sideName + " no cumplen el contrato y no se muestran: " + side.reason());
        };
    }

    private record Indexed(Map<String, Finding> byKey, Set<String> duplicates) {
    }

    private static Indexed index(Side side) {
        Map<String, Finding> byKey = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        if (side.status() != SideStatus.READY) return new Indexed(byKey, duplicates);
        for (Finding finding : side.findings()) {
            String key = key(finding.level(), finding.findingType());
            if (byKey.containsKey(key)) duplicates.add(key);
            else byKey.put(key, finding);
        }
        return new Indexed(byKey, duplicates);
    }

    public static String key(String level, String findingType) {
        return level + "|" + findingType;
    }

    /** {@code reference} = A, {@code current} = B. */
    public static Result compare(Side reference, Side current) {
        Indexed ref = index(reference);
        Indexed cur = index(current);
        Map<String, Rejected> refRejected = new LinkedHashMap<>();
        Map<String, Rejected> curRejected = new LinkedHashMap<>();
        int unreadableRef = rejectedIndex(reference, refRejected);
        int unreadableCur = rejectedIndex(current, curRejected);
        Reason referenceBlock = sideReason("A", reference);
        Reason currentBlock = sideReason("B", current);
        List<Row> rows = new ArrayList<>();

        for (String level : LEVELS) {
            for (String findingType : TYPES) {
                String key = key(level, findingType);
                Rejected ra = refRejected.get(key);
                Rejected rb = curRejected.get(key);
                // Un resultado inválido en un lado manda sobre uno válido del mismo nivel y tipo.
                Finding a = ra != null ? null : ref.byKey().get(key);
                Finding b = rb != null ? null : cur.byKey().get(key);
                if (a == null && b == null && ra == null && rb == null) continue;
                Map<String, String> rejected = new LinkedHashMap<>();
                if (ra != null) rejected.put("reference", ra.reason());
                if (rb != null) rejected.put("current", rb.reason());
                String section;
                if (a != null || b != null) section = sectionOf(a != null ? a : b);
                else {
                    Rejected first = ra != null ? ra : rb;
                    section = "not_product_supported".equals(first.deploymentStatus()) ? "research" : "main";
                }
                String status;
                Reason reason = null;
                if (referenceBlock != null || currentBlock != null) {
                    status = "not_comparable";
                    reason = referenceBlock != null ? referenceBlock : currentBlock;
                } else if (ra != null || rb != null) {
                    status = "not_comparable";
                    String names = java.util.stream.Stream.of(ra != null ? "A" : null, rb != null ? "B" : null)
                        .filter(java.util.Objects::nonNull).collect(Collectors.joining(" y "));
                    reason = new Reason("classification_invalid", "El resultado de " + names
                        + " para este nivel y clasificación no cumple el contrato y no se compara (" + (ra != null ? ra : rb).reason()
                        + "). No se interpreta como «Ausente».");
                } else if (ref.duplicates().contains(key) || cur.duplicates().contains(key)) {
                    status = "not_comparable";
                    reason = new Reason("duplicate_result", "Hay más de un resultado para este nivel y clasificación en una de las corridas: no se elige uno.");
                } else if (a == null) {
                    status = "not_comparable";
                    reason = new Reason("missing_reference", "A no tiene resultado para este nivel y clasificación. La falta de dato no equivale a «Ausente».");
                } else if (b == null) {
                    status = "not_comparable";
                    reason = new Reason("missing_current", "B no tiene resultado para este nivel y clasificación. La falta de dato no equivale a «Ausente».");
                } else {
                    Reason conflict = incompatibility(a, b);
                    if (conflict != null) {
                        status = "not_comparable";
                        reason = conflict;
                    } else {
                        status = statusOf(a, b);
                    }
                }
                rows.add(new Row(key, level, findingType, section, a, b, status, reason, rejected));
            }
        }
        int[] main = new int[4];
        int[] research = new int[4];
        for (Row row : rows) {
            int[] bucket = "main".equals(row.section()) ? main : research;
            bucket[0] += 1; // all
            if ("not_comparable".equals(row.status())) bucket[3] += 1;
            else if ("unchanged".equals(row.status())) bucket[2] += 1;
            else bucket[1] += 1; // changes
        }
        return new Result(List.copyOf(rows), main, research, unreadableRef, unreadableCur, reference, current);
    }

    private static int rejectedIndex(Side side, Map<String, Rejected> byKey) {
        int unreadable = 0;
        if (side.status() != SideStatus.READY) return 0;
        for (Rejected item : side.rejected()) {
            if (item.level() == null || item.findingType() == null) unreadable += 1;
            else byKey.putIfAbsent(key(item.level(), item.findingType()), item);
        }
        return unreadable;
    }

    /* ------------------------------------------------------------------ utilidades */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String s ? s.trim() : "";
    }

    /** Solo para pruebas y registros: «L4-L5|disc_bulging». */
    public static String describe(Row row) {
        return row.key() + " " + row.status() + (row.reason() == null ? "" : " " + row.reason().code().toLowerCase(Locale.ROOT));
    }
}
