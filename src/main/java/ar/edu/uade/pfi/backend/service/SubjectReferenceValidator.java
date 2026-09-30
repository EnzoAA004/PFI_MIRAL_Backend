package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyMetadataException;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Stage 84: extracted, verbatim-unchanged, from {@link PatientHistoryService}'s own
 * {@code normalizeSubjectRef} (Stage 8E era) so the new N-Study measurement-series endpoints
 * validate {@code subjectRef} with the exact same rule instead of a second, possibly-diverging
 * copy (84 "NO inventar una segunda normalización si ya existe una helper/regla").
 */
final class SubjectReferenceValidator {
    private static final Pattern SUBJECT_REF_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");

    private SubjectReferenceValidator() {
    }

    static String normalize(String subjectRef) {
        String value = subjectRef == null ? "" : subjectRef.trim();
        if (!SUBJECT_REF_PATTERN.matcher(value).matches() || value.contains("..")) {
            throw new StudyMetadataException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SUBJECT_REFERENCE",
                "subjectRef debe ser una referencia academica de-identificada valida."
            );
        }
        return value;
    }
}
