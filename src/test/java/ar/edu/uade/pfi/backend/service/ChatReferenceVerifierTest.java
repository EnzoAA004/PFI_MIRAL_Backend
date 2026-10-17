package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Las referencias que cita una respuesta se verifican contra el contexto real; las inexistentes se quitan y se cuentan. */
class ChatReferenceVerifierTest {
    private final Map<String, String> elements = Map.of("M1", "altura del disco L4-L5", "F2", "Abombamiento discal L5-S1", "A.C", "Contexto clínico de A", "D1", "diferencia");

    @Test
    void keepsExistingReferencesAndListsThemOnce() {
        ChatReferenceVerifier.Result result = ChatReferenceVerifier.verify("La altura es 5.79 mm [M1]. Hay abombamiento [F2] y [M1].", elements);
        assertEquals("La altura es 5.79 mm [M1]. Hay abombamiento [F2] y [M1].", result.answer());
        assertEquals(List.of("M1", "F2"), result.references().stream().map(ChatReferenceVerifier.Reference::ref).toList());
        assertEquals(0, result.removed());
    }

    @Test
    void removesInventedReferencesAndCountsThem() {
        ChatReferenceVerifier.Result result = ChatReferenceVerifier.verify("Según [M9] y [M1, F7] el valor es 5.79 [D1].", elements);
        assertEquals("Según y [M1] el valor es 5.79 [D1].", result.answer());
        assertEquals(List.of("M1", "D1"), result.references().stream().map(ChatReferenceVerifier.Reference::ref).toList());
        assertEquals(2, result.removed());
    }

    @Test
    void leavesNonReferenceBracketsAlone() {
        ChatReferenceVerifier.Result result = ChatReferenceVerifier.verify("Ver [1] y [nota aclaratoria] [A.C]", elements);
        assertEquals("Ver [1] y [nota aclaratoria] [A.C]", result.answer());
        assertEquals(0, result.removed());
    }

    @Test
    void warnsWhenTheCitedElementBelongsToAnotherLevelThanTheSentence() {
        Map<String, String> byLevel = Map.of("F20", "Abombamiento discal L5-S1", "M3", "altura del disco L4-L5");
        ChatReferenceVerifier.Result bad = ChatReferenceVerifier.verify("En L4-L5 hay abombamiento presente (70.4 %) [F20].", byLevel);
        assertEquals(List.of("La respuesta menciona L4-L5 junto a [F20], que corresponde a L5-S1: revisá ese dato."), bad.inconsistencies());
        assertEquals("En L4-L5 hay abombamiento presente (70.4 %) [F20].", bad.answer(), "solo avisa, no reescribe");
        assertEquals(List.of(), ChatReferenceVerifier.verify("En L5-S1 hay abombamiento presente [F20]. En L4-L5 la altura es 9.3 mm [M3].", byLevel).inconsistencies());
        assertEquals(List.of(), ChatReferenceVerifier.verify("Hay abombamiento presente [F20].", byLevel).inconsistencies(), "sin nivel en la frase no hay nada que contrastar");
    }

    @Test
    void sideQualifiedReferencesMustExistToo() {
        ChatReferenceVerifier.Result result = ChatReferenceVerifier.verify("[B.C] no existe pero [A.C] sí.", elements);
        assertEquals("no existe pero [A.C] sí.", result.answer());
        assertEquals(1, result.removed());
    }
}
