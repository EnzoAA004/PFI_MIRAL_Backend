package ar.edu.uade.pfi.backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifica, en el servidor, las referencias que cita una respuesta ({@code [M3]}, {@code [A.F2]}, {@code [D1, D2]}…)
 * contra los elementos reales del contexto con que se respondió. Una referencia que no existe se quita del texto y se
 * cuenta: nunca se presenta como cita. El texto entre corchetes que no es una referencia (por ejemplo «[2]») no se toca.
 */
final class ChatReferenceVerifier {
    private static final Pattern GROUP = Pattern.compile("\\[([^\\[\\]]{1,60})\\]");
    private static final Pattern TOKEN = Pattern.compile("(?:[AB]\\.)?[MFDHXCR]\\d*");

    record Result(String answer, List<Reference> references, int removed) {
    }

    record Reference(String ref, String label) {
    }

    private ChatReferenceVerifier() {
    }

    static Result verify(String answer, Map<String, String> elements) {
        Map<String, String> found = new LinkedHashMap<>();
        int removed = 0;
        Matcher matcher = GROUP.matcher(answer);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String[] tokens = matcher.group(1).trim().split("[,;\\s]+");
            boolean allRefs = tokens.length > 0;
            for (String token : tokens) if (!TOKEN.matcher(token).matches()) allRefs = false;
            if (!allRefs) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            List<String> valid = new ArrayList<>();
            for (String token : tokens) {
                if (elements.containsKey(token)) {
                    valid.add(token);
                    found.putIfAbsent(token, elements.get(token));
                } else {
                    removed += 1;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(valid.isEmpty() ? "" : "[" + String.join(", ", valid) + "]"));
        }
        matcher.appendTail(out);
        String cleaned = out.toString().replaceAll("[ \\t]{2,}", " ").replaceAll(" +([.,;:])", "$1").trim();
        List<Reference> references = new ArrayList<>();
        found.forEach((ref, label) -> references.add(new Reference(ref, label)));
        return new Result(cleaned, List.copyOf(references), removed);
    }
}
