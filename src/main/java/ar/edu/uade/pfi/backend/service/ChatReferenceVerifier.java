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
    /** Espacio discal: T10-T11, L4-L5, L5-S1… (un nivel de vértebra suelto, como L4, no se compara). */
    private static final Pattern DISC_LEVEL = Pattern.compile("\\b[TL]\\d{1,2}-[TLS]\\d{1,2}\\b");
    private static final int CONTEXT_WINDOW = 160;

    record Result(String answer, List<Reference> references, int removed, List<String> inconsistencies) {
    }

    record Reference(String ref, String label) {
    }

    private ChatReferenceVerifier() {
    }

    static Result verify(String answer, Map<String, String> elements) {
        Map<String, String> found = new LinkedHashMap<>();
        List<String> inconsistencies = new ArrayList<>();
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
                    String mismatch = levelMismatch(answer, matcher.start(), token, elements.get(token));
                    if (mismatch != null && !inconsistencies.contains(mismatch)) inconsistencies.add(mismatch);
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
        return new Result(cleaned, List.copyOf(references), removed, List.copyOf(inconsistencies));
    }

    /**
     * Una cita puede existir y aun así estar al lado de otro nivel: [F20] es L5-S1 y la frase dice «en L4-L5». Se compara el nivel
     * de espacio discal que aparece en la misma frase (hasta {@value #CONTEXT_WINDOW} caracteres antes de la cita) con el del
     * elemento citado. Solo avisa; no corrige ni quita nada.
     */
    private static String levelMismatch(String answer, int citationStart, String ref, String label) {
        Matcher elementLevel = DISC_LEVEL.matcher(label);
        if (!elementLevel.find()) return null;
        String expected = elementLevel.group();
        int from = Math.max(0, citationStart - CONTEXT_WINDOW);
        String before = answer.substring(from, citationStart);
        int cut = Math.max(Math.max(before.lastIndexOf('\n'), before.lastIndexOf(". ")), before.lastIndexOf("]"));
        String sentence = cut >= 0 ? before.substring(cut + 1) : before;
        Matcher mentioned = DISC_LEVEL.matcher(sentence);
        String last = null;
        while (mentioned.find()) last = mentioned.group();
        if (last == null || last.equals(expected)) return null;
        return "La respuesta menciona " + last + " junto a [" + ref + "], que corresponde a " + expected + ": revisá ese dato.";
    }
}
