package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.Study;
import java.util.Locale;

/**
 * Cuándo dos estudios se pueden tratar como del mismo paciente.
 *
 * <ol>
 *   <li>Si los DOS tienen {@code patientId}: se comparan solo si coincide. Si difiere, no hay
 *       comparación aunque el {@code subjectRef} coincida: un identificador de paciente explícito
 *       gana sobre una referencia técnica.</li>
 *   <li>Si falta el {@code patientId} en alguno (estudios históricos o sin paciente asociado), se
 *       acepta la asociación por {@code subjectRef} solo cuando es inequívoca: los dos lo tienen,
 *       no está vacío y coincide (sin distinguir mayúsculas ni espacios).</li>
 *   <li>Nunca se relaciona por nombre ni se supone que dos estudios sin paciente y sin referencia
 *       son de la misma persona.</li>
 * </ol>
 */
public final class StudyPatientIdentityPolicy {
    /** Cómo se estableció la relación; se informa para que se vea sobre qué base se compara. */
    public enum Link {
        PATIENT_ID("patient_id"),
        SUBJECT_REF_HISTORICAL("subject_ref_historical"),
        NONE("none");

        private final String code;

        Link(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public boolean allowed() {
            return this != NONE;
        }
    }

    private StudyPatientIdentityPolicy() {
    }

    public static Link relate(Study a, Study b) {
        String patientA = blankToNull(a.patientId());
        String patientB = blankToNull(b.patientId());
        if (patientA != null && patientB != null) {
            return patientA.equals(patientB) ? Link.PATIENT_ID : Link.NONE;
        }
        String subjectA = normalizedSubjectRef(a.subjectRef());
        String subjectB = normalizedSubjectRef(b.subjectRef());
        if (subjectA != null && subjectA.equals(subjectB)) return Link.SUBJECT_REF_HISTORICAL;
        return Link.NONE;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizedSubjectRef(String subjectRef) {
        return subjectRef == null || subjectRef.isBlank() ? null : subjectRef.trim().toLowerCase(Locale.ROOT);
    }
}
