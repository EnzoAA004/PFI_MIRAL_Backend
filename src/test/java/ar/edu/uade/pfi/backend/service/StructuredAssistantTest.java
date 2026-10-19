package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.service.StructuredAssistant.Answer;
import ar.edu.uade.pfi.backend.service.StructuredAssistant.Intent;
import ar.edu.uade.pfi.backend.service.StructuredAssistant.Line;
import ar.edu.uade.pfi.backend.service.StructuredAssistant.Plan;
import ar.edu.uade.pfi.backend.service.StructuredAssistant.PlanException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Modo de consulta acotado: lo que muestra el asistente sale de hechos registrados con plantillas deterministas; el modelo solo
 * elige una intención y filtros. Son pruebas del código del servidor: no usan ningún modelo de lenguaje.
 */
class StructuredAssistantTest {
    private final AssistantTestFixtures fx = new AssistantTestFixtures();
    private final AssistantContextBuilder builder = fx.builder();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Datos como los de la respuesta problemática de MENDELEY: anchos de disco y diámetros del canal sin ningún umbral. */
    private AssistantContext mendeleyLike() {
        Study study = fx.studyWithContext("CASO-M", "PAT-M", LocalDate.of(2026, 10, 7), null, null, null, null, null, null);
        fx.run(study, "run-m", AssistantTestFixtures.T0, List.of(
            AssistantTestFixtures.disc("L3-L4", "width", 34.24), AssistantTestFixtures.disc("L4-L5", "width", 35.26), AssistantTestFixtures.disc("L5-S1", "width", 33.76),
            AssistantTestFixtures.disc("L5-S1", "height", 13.43), AssistantTestFixtures.disc("L5-S1", "area", 312.23),
            AssistantTestFixtures.measurement("sagittal-canal-ap-l3-l4", "canal ap", "L3-L4", "mm", 10.94),
            AssistantTestFixtures.measurement("sagittal-canal-ap-l5-s1", "canal ap", "L5-S1", "mm", 7.66)),
            List.of(AssistantTestFixtures.bulging("L5-S1", true, 0.704), AssistantTestFixtures.bulging("L4-L5", false, 0.896),
                AssistantTestFixtures.bulging("L3-L4", false, 0.931)));
        return builder.buildStudy(study, "run-m");
    }

    private static Answer answerFor(Intent intent, AssistantContext context) {
        return StructuredAssistant.answer(Plan.of(intent), context);
    }

    private static List<String> lineTexts(Answer answer) {
        return answer.lines().stream().map(Line::text).toList();
    }

    private static void assertVerified(Answer answer, AssistantContext context) {
        assertEquals(List.of(), StructuredAssistant.verify(answer, context), answer.text());
    }

    /* ------------------------------------------------------------------ regresión: la respuesta problemática de MENDELEY */

    @Test
    void regressionTheMendeleyAnswerNeverQualifiesValuesAndEveryLineCitesItsOwnReference() {
        AssistantContext context = mendeleyLike();
        for (Intent intent : List.of(Intent.SUMMARY, Intent.LIST_MEASUREMENTS, Intent.LIST_FINDINGS)) {
            Answer answer = answerFor(intent, context);
            String text = answer.text().toLowerCase();
            for (String forbidden : List.of("elevad", "reducid", "podría indicar", "podria indicar", "anormal", "patológic", "normal", "aumentad", "disminuid", "grave", "alto ", "bajo ")) {
                assertFalse(text.contains(forbidden), intent + " contiene «" + forbidden + "»: " + answer.text());
            }
            assertVerified(answer, context);
        }
        Answer measurements = answerFor(Intent.LIST_MEASUREMENTS, context);
        // Cada cifra aparece tal cual y en la línea de SU referencia: no basta con que cite referencias que existen.
        String width = measurements.lines().stream().filter(l -> l.text().contains("34,24 mm")).findFirst().orElseThrow().text();
        assertTrue(width.contains("L3-L4") && width.contains("ancho del disco"), width);
        assertFalse(width.contains("10,94"), "la línea del ancho no puede respaldar el canal");
        String canal = measurements.lines().stream().filter(l -> l.text().contains("10,94 mm")).findFirst().orElseThrow().text();
        assertTrue(canal.contains("diámetro anteroposterior del canal") && canal.contains("L3-L4"), canal);
        assertFalse(canal.contains("34,24"));
        // La clasificación positiva real sigue estando, con su probabilidad.
        Answer findings = StructuredAssistant.answer(new Plan(Intent.LIST_FINDINGS, List.of(), List.of(), List.of(), true), context);
        assertTrue(findings.text().contains("L5-S1 · Abombamiento discal: predicción del modelo «presente» (70,4 %)"), findings.text());
        assertFalse(findings.text().contains("L4-L5"), "lo predicho como ausente no se lista entre los presentes");
    }

    @Test
    void theModelsFreeTextNeverReachesTheAnswerEvenIfItSaysTheBadPhrases() {
        AssistantContext context = mendeleyLike();
        String bad = "En L3-L4 se detectan valores elevados de ancho del disco; el canal es reducido, lo que podría indicar estrechamiento.";
        assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(bad, "Resumí los resultados", context, mapper));
        // Aunque la salida sea un JSON válido, solo se toman la intención y los filtros: su texto no existe en la respuesta.
        String json = "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false,\"comment\":\"valores elevados\"}";
        assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(json, "¿Qué mediciones hay?", context, mapper));
    }

    /* ------------------------------------------------------------------ ausente / no evaluado / sin datos */

    @Test
    void absentIsNeverConfusedWithNotEvaluatedOrWithoutData() {
        Study study = fx.study("CASO-A", "PAT-A", "S-A", LocalDate.of(2026, 10, 7));
        fx.run(study, "run-a", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.0)), List.of(
            AssistantTestFixtures.bulging("L4-L5", false, 0.9),
            AssistantTestFixtures.finding("L4-L5", "disc_narrowing", "absent", Map.of("absent", 0.8, "present", 0.2)),
            AssistantTestFixtures.bulging("L5-S1", true, 0.6)));  // L5-S1 no tiene «estrechamiento»: no fue evaluado
        AssistantContext context = builder.buildStudy(study, "run-a");
        Answer answer = answerFor(Intent.LIST_FINDINGS, context);
        assertTrue(answer.text().contains("L4-L5 · Abombamiento discal: predicción del modelo «ausente» (90,0 %)"), answer.text());
        assertTrue(answer.text().contains("L5-S1 · Estrechamiento discal"), "lo que no tiene resultado se declara como no evaluado");
        String notEvaluated = lineTexts(answer).stream().filter(t -> t.contains("Sin resultado registrado")).findFirst().orElseThrow();
        assertTrue(notEvaluated.contains("no evaluado") && notEvaluated.contains("no equivale a «ausente»"), notEvaluated);
        assertTrue(notEvaluated.contains("L5-S1 · Estrechamiento discal"));
        assertFalse(notEvaluated.contains("L4-L5"), "L4-L5 sí tiene ambos resultados");
        assertVerified(answer, context);
    }

    @Test
    void aRunWithoutFindingsSaysSoAndDoesNotCallThemAbsent() {
        Study study = fx.study("CASO-S", "PAT-S", "S-S", LocalDate.of(2026, 10, 7));
        fx.run(study, "run-s", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.0)), null);
        AssistantContext context = builder.buildStudy(study, "run-s");
        Answer answer = answerFor(Intent.LIST_FINDINGS, context);
        assertTrue(answer.text().contains("No hay clasificaciones degenerativas por IA registradas"), answer.text());
        assertTrue(answer.text().contains("Eso no equivale a que ninguna clasificación esté ausente"));
        assertFalse(answer.text().contains("«ausente»"));
        assertEquals(List.of("FS"), answer.refs());
        assertVerified(answer, context);
        Answer onlyPresent = StructuredAssistant.answer(new Plan(Intent.LIST_FINDINGS, List.of(), List.of(), List.of(), true), context);
        assertFalse(onlyPresent.text().contains("ninguna clasificación registrada") && onlyPresent.text().contains("fue predicha como presente"), "sin datos no se dice «ninguna fue presente»");
    }

    /* ------------------------------------------------------------------ Modic «none» frente a cambio Modic presente */

    @Test
    void modicNoneIsNotAModicChangeAndAPresentModicTypeIs() {
        Study study = fx.study("CASO-D", "PAT-D", "S-D", LocalDate.of(2026, 10, 7));
        fx.run(study, "run-d", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.0)), List.of(
            AssistantTestFixtures.finding("L4-L5", "modic_change", "none", Map.of("none", 0.8, "I", 0.1, "II", 0.07, "III", 0.03)),
            AssistantTestFixtures.finding("L5-S1", "modic_change", "II", Map.of("none", 0.1, "I", 0.2, "II", 0.6, "III", 0.1)),
            AssistantTestFixtures.bulging("L5-S1", true, 0.7)));
        AssistantContext context = builder.buildStudy(study, "run-d");
        Answer all = answerFor(Intent.LIST_FINDINGS, context);
        assertTrue(all.text().contains("L4-L5 · Cambio Modic: predicción del modelo «sin cambios Modic» (80,0 %)"), all.text());
        assertTrue(all.text().contains("L5-S1 · Cambio Modic: predicción del modelo «Modic tipo II» (60,0 %)"), all.text());
        Answer present = StructuredAssistant.answer(new Plan(Intent.LIST_FINDINGS, List.of(), List.of(), List.of(), true), context);
        assertTrue(present.text().contains("Modic tipo II") && present.text().contains("Abombamiento discal"), present.text());
        assertFalse(present.text().contains("sin cambios Modic"), "«none» no es un cambio Modic presente");
        assertVerified(all, context);
        assertVerified(present, context);
    }

    /* ------------------------------------------------------------------ nivel, métrica o lado incorrectos */

    @Test
    void aFilterThatTheQuestionDoesNotMentionIsRejectedNotApplied() throws Exception {
        AssistantContext context = mendeleyLike();
        String json = "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":[\"L5-S1\"],\"metrics\":[],\"classifications\":[],\"only_present\":false}";
        // La pregunta nombra L4-L5; el modelo eligió otro nivel: se rechaza en lugar de mostrar datos de otro nivel.
        assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(json, "¿Qué mediciones hay en L4-L5?", context, mapper));
        Plan ok = StructuredAssistant.parsePlan(json, "¿Qué mediciones hay en L5 S1?", context, mapper);
        assertEquals(List.of("L5-S1"), ok.levels());
        // Medición: la pregunta dice «altura», el modelo eligió «ancho».
        String metric = "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":[],\"metrics\":[\"ancho del disco\"],\"classifications\":[],\"only_present\":false}";
        assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(metric, "¿Cuál es la altura del disco?", context, mapper));
        assertEquals(List.of("ancho del disco"), StructuredAssistant.parsePlan(metric, "¿Cuál es el ancho del disco?", context, mapper).metrics());
        // Valor fuera del vocabulario real del estudio.
        String vocab = "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":[\"L1-L2\"],\"metrics\":[],\"classifications\":[],\"only_present\":false}";
        assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(vocab, "mediciones de L1-L2", context, mapper));
    }

    @Test
    void aLevelWithoutDataIsReportedAsSuchAndNeverReplaced() {
        AssistantContext context = mendeleyLike();
        assertEquals(List.of("L6-S1"), StructuredAssistant.unknownLevels("¿Qué mediciones tiene L6-S1?", context));
        assertEquals(List.of(), StructuredAssistant.unknownLevels("¿Qué mediciones tiene L5-S1?", context));
        Answer answer = answerFor(Intent.LIST_MEASUREMENTS, context);
        assertFalse(answer.text().contains("L6-S1"));
    }

    @Test
    void differencesKeepSidesAAndBAndUnitsAndNeverOrderMixedMagnitudes() {
        AssistantContext context = pairContext(List.of("L2-L3", "L4-L5"), false);
        Answer answer = answerFor(Intent.LIST_DIFFERENCES, context);
        // A (referencia) y B (actual) no se invierten: A es el valor de la referencia (5,79) y B el actual (7,3).
        String height = lineTexts(answer).stream().filter(t -> t.contains("L4-L5 · altura del disco")).findFirst().orElseThrow();
        assertTrue(height.contains("A 5,79 mm → B 7,3 mm") && height.contains("B − A +1,51 mm"), height);
        // Agrupado por medición y unidad: mm y mm² no se mezclan ni se ordenan por magnitud.
        List<String> texts = lineTexts(answer);
        int heightGroup = texts.indexOf("Altura del disco (mm):");
        int areaGroup = texts.indexOf("Área del disco (mm²):");
        assertTrue(heightGroup >= 0 && areaGroup > heightGroup, texts.toString());
        // Dentro de un grupo el orden es anatómico (L2-L3 antes que L4-L5), aunque L4-L5 tenga la mayor diferencia.
        assertTrue(texts.indexOf(texts.stream().filter(t -> t.contains("L2-L3 · área")).findFirst().orElseThrow())
            < texts.indexOf(texts.stream().filter(t -> t.contains("L4-L5 · área")).findFirst().orElseThrow()), texts.toString());
        assertTrue(answer.text().contains("no indica evolución clínica"));
        assertFalse(answer.text().toLowerCase().contains("aument") || answer.text().toLowerCase().contains("disminu") || answer.text().toLowerCase().contains("mejor") || answer.text().toLowerCase().contains("empeor"));
        assertVerified(answer, context);
    }

    /* ------------------------------------------------------------------ referencia existente pero insuficiente */

    @Test
    void aLineCitingAnExistingReferenceThatDoesNotSupportItIsRejected() {
        AssistantContext context = mendeleyLike();
        Answer real = answerFor(Intent.LIST_MEASUREMENTS, context);
        assertVerified(real, context);
        AssistantFact height = context.facts().stream().filter(f -> f.kind() == AssistantFact.Kind.MEASUREMENT && "disc height".equals(f.group())).findFirst().orElseThrow();
        AssistantFact width = context.facts().stream().filter(f -> f.kind() == AssistantFact.Kind.MEASUREMENT && "disc width".equals(f.group())).findFirst().orElseThrow();
        // Una línea que muestra el ANCHO pero cita la referencia de la ALTURA (existe, pero no la respalda).
        String widthText = StructuredAssistant.measurementLine(width);
        Line tampered = new Line(widthText.replace("[" + width.ref() + "]", "[" + height.ref() + "]"), List.of(height.ref()));
        Answer bad = new Answer(Intent.LIST_MEASUREMENTS, tampered.text(), List.of(tampered), 1, 1, true);
        List<StructuredAssistant.Violation> violations = StructuredAssistant.verify(bad, context);
        assertFalse(violations.isEmpty(), "una referencia de altura no puede respaldar un ancho");
        // Una sola referencia citando varias cifras de hechos distintos tampoco pasa.
        Line merged = new Line("[" + height.ref() + "] " + StructuredAssistant.measurementLine(height).substring(StructuredAssistant.measurementLine(height).indexOf(']') + 2)
            + " y " + StructuredAssistant.num(width.value()) + " mm", List.of(height.ref()));
        assertFalse(StructuredAssistant.verify(new Answer(Intent.LIST_MEASUREMENTS, merged.text(), List.of(merged), 1, 1, true), context).isEmpty());
        // Una referencia que no existe.
        Line invented = new Line("[M99] L3-L4 · ancho del disco: 34,24 mm", List.of("M99"));
        assertFalse(StructuredAssistant.verify(new Answer(Intent.LIST_MEASUREMENTS, invented.text(), List.of(invented), 1, 1, true), context).isEmpty());
        // Cita en el texto sin declararla.
        Line undeclared = new Line("[M1] algo", List.of());
        assertFalse(StructuredAssistant.verify(new Answer(Intent.LIST_MEASUREMENTS, undeclared.text(), List.of(undeclared), 1, 1, true), context).isEmpty());
    }

    /* ------------------------------------------------------------------ subconjunto: cuántos se muestran y cuántos hay */

    @Test
    void aPartialListSaysHowManyAreShownAndHowManyThereAre() {
        List<String> levels = List.of("T10-T11", "T11-T12", "T12-L1", "L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1");
        AssistantContext context = pairContext(levels, true);
        long compared = context.facts().stream().filter(f -> f.kind() == AssistantFact.Kind.DIFFERENCE).count();
        assertTrue(compared > StructuredAssistant.MAX_DATA_LINES, "el caso de prueba debe superar el tope: " + compared);
        Answer answer = answerFor(Intent.LIST_DIFFERENCES, context);
        assertEquals(StructuredAssistant.MAX_DATA_LINES, answer.shown());
        assertEquals((int) compared, answer.total());
        assertTrue(answer.text().contains("Mostrando " + StructuredAssistant.MAX_DATA_LINES + " de " + compared + " diferencias comparadas"), answer.text());
        assertTrue(answer.text().contains("indicá un nivel o una medición"));
        long dataLines = answer.lines().stream().filter(l -> l.refs().size() == 1 && l.refs().get(0).startsWith("D")).count();
        assertEquals(StructuredAssistant.MAX_DATA_LINES, dataLines);
        assertVerified(answer, context);
        // Con un filtro la respuesta completa del filtro cabe y no se dice que falte nada.
        Answer filtered = StructuredAssistant.answer(new Plan(Intent.LIST_DIFFERENCES, List.of("L5-S1"), List.of(), List.of(), false), context);
        assertTrue(filtered.text().contains("(nivel L5-S1)"), filtered.text());
        assertFalse(filtered.text().contains("indicá un nivel"));
        assertVerified(filtered, context);
    }

    /* ------------------------------------------------------------------ salida malformada, referencia inventada, fuera de alcance */

    @Test
    void malformedModelOutputIsRejected() {
        AssistantContext context = mendeleyLike();
        for (String bad : List.of("", "no es json", "[]", "{\"intent\":\"LIST_MEASUREMENTS\"}", "{\"intent\":1,\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false}",
            "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":\"L4-L5\",\"metrics\":[],\"classifications\":[],\"only_present\":false}",
            "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":\"si\"}",
            "```json\n{\"intent\":\"SUMMARY\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false}\n```")) {
            assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(bad, "Resumí", context, mapper), bad);
        }
    }

    @Test
    void aReferenceInventedByTheModelOrAnUnsupportedIntentIsRejected() {
        AssistantContext context = mendeleyLike();
        String withRefs = "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false,\"refs\":[\"M99\"]}";
        assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(withRefs, "mediciones", context, mapper));
        // Intención de comparación en un estudio individual.
        String pairIntent = "{\"intent\":\"LIST_DIFFERENCES\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false}";
        assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(pairIntent, "diferencias", context, mapper));
        String invented = "{\"intent\":\"DIAGNOSE\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false}";
        assertThrows(PlanException.class, () -> StructuredAssistant.parsePlan(invented, "diagnóstico", context, mapper));
    }

    @Test
    void anOutOfScopeRequestExplainsTheLimitAndOffersTheSupportedQueriesWithoutAnyInterpretation() throws Exception {
        AssistantContext context = mendeleyLike();
        String json = "{\"intent\":\"UNSUPPORTED\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false}";
        Plan plan = StructuredAssistant.parsePlan(json, "¿Es grave? ¿Qué tratamiento recomendás?", context, mapper);
        assertEquals(Intent.UNSUPPORTED, plan.intent());
        Answer answer = StructuredAssistant.answer(plan, context);
        assertFalse(answer.supported());
        assertEquals(List.of(), answer.refs(), "una consulta fuera del alcance no cita datos");
        assertTrue(answer.text().contains("fuera de lo que el asistente puede responder") && answer.text().contains("Consultas disponibles:"));
        for (StructuredAssistant.SuggestedQuestion suggestion : StructuredAssistant.suggestions(context)) assertTrue(answer.text().contains(suggestion.text()));
        String lower = answer.text().toLowerCase();
        for (String interpretation : List.of("grave", "tratamiento", "diagnóstic", "normal", "elevad", "reducid")) {
            if (interpretation.equals("grave") || interpretation.equals("tratamiento") || interpretation.equals("diagnóstic")) continue; // el límite nombra lo que no hace
            assertFalse(lower.contains(interpretation), interpretation);
        }
        assertVerified(answer, context);
    }

    @Test
    void theSuggestedQuestionsResolveWithoutTheModelAndTheyMatchTheScope() {
        AssistantContext study = mendeleyLike();
        for (StructuredAssistant.SuggestedQuestion suggestion : StructuredAssistant.suggestions(study)) {
            Plan plan = StructuredAssistant.fromSuggestion(suggestion.text(), study);
            assertNotNull(plan, suggestion.text());
            assertEquals(suggestion.intent(), plan.intent());
        }
        assertEquals(null, StructuredAssistant.fromSuggestion("¿Qué diferencias se registraron entre A y B?", study), "las consultas de un par no existen en un estudio individual");
        AssistantContext pairNoFindings = pairContext(List.of("L4-L5"), false);
        assertFalse(StructuredAssistant.suggestions(pairNoFindings).stream().anyMatch(s -> s.intent() == Intent.LIST_FINDING_CHANGES),
            "un par sin hallazgos en los dos lados no promete una comparación de clasificaciones");
    }

    @Test
    void theRouterSchemaOnlyAllowsTheRealVocabularyAndIsStrict() {
        AssistantContext context = mendeleyLike();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>) StructuredAssistant.routerSchema(context).get("json_schema")).get("schema");
        assertEquals(false, schema.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> intents = (List<String>) ((Map<String, Object>) properties.get("intent")).get("enum");
        assertTrue(intents.contains("UNSUPPORTED") && !intents.contains("LIST_DIFFERENCES"));
        String prompt = StructuredAssistant.routerPrompt(context);
        assertTrue(prompt.contains("L5-S1") && prompt.contains("ancho del disco") && prompt.contains("Abombamiento discal"));
        assertFalse(prompt.contains("34,24") || prompt.contains("34.24"), "el pedido al modelo no lleva valores: solo vocabulario");
    }

    @Test
    void levelsAreOrderedAnatomically() {
        List<String> levels = new ArrayList<>(List.of("L5-S1", "T12-L1", "L1-L2", "T10-T11", "L4", "L3-L4"));
        levels.sort(java.util.Comparator.comparingInt(StructuredAssistant::levelRank));
        assertEquals(List.of("T10-T11", "T12-L1", "L1-L2", "L3-L4", "L4", "L5-S1"), levels);
    }

    /* ------------------------------------------------------------------ apoyo: un par con diferencias */

    /** Par A/B del mismo paciente con alturas, anchos y áreas de discos (y, si se pide, vértebras) en los niveles dados. */
    private AssistantContext pairContext(List<String> discLevels, boolean withVertebrae) {
        Study reference = fx.studyWithContext("REF-1", "P-1", LocalDate.of(2026, 4, 1), 50, Study.AGE_CALCULATED, null, null, null, null);
        Study current = fx.studyWithContext("CUR-1", "P-1", LocalDate.of(2026, 10, 7), 50, Study.AGE_CALCULATED, null, null, null, null);
        List<Map<String, Object>> a = new ArrayList<>();
        List<Map<String, Object>> b = new ArrayList<>();
        int i = 0;
        for (String level : discLevels) {
            i += 1;
            boolean l45 = level.equals("L4-L5");
            a.add(AssistantTestFixtures.disc(level, "height", l45 ? 5.79 : 6.0 + i));
            b.add(AssistantTestFixtures.disc(level, "height", l45 ? 7.3 : 6.5 + i));
            a.add(AssistantTestFixtures.disc(level, "width", 30.0 + i));
            b.add(AssistantTestFixtures.disc(level, "width", 31.0 + i));
            a.add(AssistantTestFixtures.disc(level, "area", 200.0 + i * 10));
            b.add(AssistantTestFixtures.disc(level, "area", (l45 ? 400.0 : 205.0) + i * 10));
            if (withVertebrae) {
                String vertebra = level.contains("S1") ? "L5" : level.split("-")[0];
                a.add(AssistantTestFixtures.measurement("sagittal-vertebra-" + vertebra.toLowerCase() + "-height", "vertebra height", vertebra, "mm", 28.0 + i));
                b.add(AssistantTestFixtures.measurement("sagittal-vertebra-" + vertebra.toLowerCase() + "-height", "vertebra height", vertebra, "mm", 28.5 + i));
                a.add(AssistantTestFixtures.measurement("sagittal-vertebra-" + vertebra.toLowerCase() + "-width", "vertebra width", vertebra, "mm", 38.0 + i));
                b.add(AssistantTestFixtures.measurement("sagittal-vertebra-" + vertebra.toLowerCase() + "-width", "vertebra width", vertebra, "mm", 38.5 + i));
                a.add(AssistantTestFixtures.measurement("sagittal-vertebra-" + vertebra.toLowerCase() + "-area", "vertebra area", vertebra, "mm2", 700.0 + i));
                b.add(AssistantTestFixtures.measurement("sagittal-vertebra-" + vertebra.toLowerCase() + "-area", "vertebra area", vertebra, "mm2", 710.0 + i));
            }
        }
        fx.run(reference, "run-a", AssistantTestFixtures.T0, a, null);
        fx.run(current, "run-b", AssistantTestFixtures.T0.plusSeconds(100), b, null);
        return builder.buildPair(current, "run-b", reference, "run-a");
    }
}
