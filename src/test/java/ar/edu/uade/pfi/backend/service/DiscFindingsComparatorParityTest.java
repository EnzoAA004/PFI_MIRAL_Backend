package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.util.DiscDegenerativeFindingsPublicProjection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Paridad de la comparación de hallazgos P10.7 del servidor con la del Frontend. El archivo de casos lo genera el código TS
 * real ({@code scripts/gen-disc-findings-parity.mjs}); aquí se verifica que {@link DiscFindingsComparator} da el mismo
 * resultado (estados, códigos de no comparable, resultados invalidados, conteos) y que la proyección pública del Backend
 * coincide con la que usó el Frontend. Si una regla cambia de un solo lado, esta prueba o la del Frontend fallan.
 */
class DiscFindingsComparatorParityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshotOf(JsonNode side) {
        return MAPPER.convertValue(side.get("snapshot"), new TypeReference<Map<String, Object>>() { });
    }

    @Test
    void serverComparisonMatchesTheFrontendOnEverySharedCase() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/parity/disc-findings-comparison-cases.json")) {
            assertTrue(in != null, "falta el archivo de paridad: node scripts/gen-disc-findings-parity.mjs");
            root = MAPPER.readTree(in);
        }
        JsonNode cases = root.get("cases");
        assertTrue(cases.size() >= 15, "se esperaban los casos de paridad");
        for (JsonNode testCase : cases) {
            String name = testCase.get("name").asText();
            DiscFindingsComparator.Side reference = DiscFindingsComparator.read(snapshotOf(testCase.get("reference")));
            DiscFindingsComparator.Side current = DiscFindingsComparator.read(snapshotOf(testCase.get("current")));
            DiscFindingsComparator.Result result = DiscFindingsComparator.compare(reference, current);
            JsonNode expected = testCase.get("expected");

            assertEquals(expected.get("sides").get(0).asText(), reference.status().name(), name + ": lado A");
            assertEquals(expected.get("sides").get(1).asText(), current.status().name(), name + ": lado B");
            JsonNode rows = expected.get("rows");
            assertEquals(rows.size(), result.rows().size(), name + ": cantidad de filas " + describe(result));
            for (int i = 0; i < rows.size(); i++) {
                JsonNode want = rows.get(i);
                DiscFindingsComparator.Row got = result.rows().get(i);
                String where = name + " fila " + want.get("key").asText();
                assertEquals(want.get("key").asText(), got.key(), where);
                assertEquals(want.get("section").asText(), got.section(), where + " sección");
                assertEquals(want.get("status").asText(), got.status(), where + " estado");
                assertEquals(want.get("code").isNull() ? null : want.get("code").asText(), got.reason() == null ? null : got.reason().code(), where + " código");
                List<String> rejected = new ArrayList<>();
                want.get("rejected").forEach(node -> rejected.add(node.asText()));
                assertEquals(rejected, new ArrayList<>(new TreeSet<>(got.rejected().keySet())), where + " resultados inválidos");
                assertEquals(want.get("reference").isNull() ? null : want.get("reference").asText(), got.reference() == null ? null : got.reference().label(), where + " clase A");
                assertEquals(want.get("current").isNull() ? null : want.get("current").asText(), got.current() == null ? null : got.current().label(), where + " clase B");
            }
            assertCounts(expected.get("counts").get("main"), result.mainCounts(), name + " principales");
            assertCounts(expected.get("counts").get("research"), result.researchCounts(), name + " investigación");
            assertEquals(expected.get("unreadable").get(0).asInt(), result.unreadableReference(), name + " ilegibles A");
            assertEquals(expected.get("unreadable").get(1).asInt(), result.unreadableCurrent(), name + " ilegibles B");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void backendPublicProjectionEqualsTheOneTheFrontendTestsUse() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/parity/disc-findings-comparison-cases.json")) {
            root = MAPPER.readTree(in);
        }
        int checked = 0;
        for (JsonNode testCase : root.get("cases")) {
            for (String side : List.of("reference", "current")) {
                JsonNode publicFindings = testCase.get(side.equals("reference") ? "publicReference" : "publicCurrent");
                JsonNode snapshotFindings = testCase.get(side).get("snapshot").path("discDegenerativeFindings").path("findings");
                if (publicFindings == null || publicFindings.isNull() || !snapshotFindings.isArray()) continue;
                Map<String, Object> envelope = MAPPER.convertValue(testCase.get(side).get("snapshot").get("discDegenerativeFindings"), new TypeReference<Map<String, Object>>() { });
                Map<String, Object> sanitized = DiscDegenerativeFindingsPublicProjection.sanitize(envelope);
                JsonNode sanitizedNode = MAPPER.valueToTree(sanitized.get("findings"));
                for (int i = 0; i < publicFindings.size(); i++) {
                    assertTrue(sameJson(publicFindings.get(i), sanitizedNode.get(i)),
                        testCase.get("name").asText() + ": proyección pública del resultado " + i + " " + publicFindings.get(i) + " <> " + sanitizedNode.get(i));
                    checked += 1;
                }
            }
        }
        assertTrue(checked > 20, "se esperaba comprobar la proyección de varios resultados");
    }

    /** Igualdad de JSON donde 1 y 1.0 son el mismo número (JS no distingue enteros de decimales). */
    private static boolean sameJson(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            var names = a.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!b.has(name) || !sameJson(a.get(name), b.get(name))) return false;
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameJson(a.get(i), b.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }

    private static void assertCounts(JsonNode want, int[] got, String where) {
        for (int i = 0; i < 4; i++) assertEquals(want.get(i).asInt(), got[i], where + " contador " + i);
    }

    private static String describe(DiscFindingsComparator.Result result) {
        return result.rows().stream().map(DiscFindingsComparator::describe).toList().toString();
    }
}
