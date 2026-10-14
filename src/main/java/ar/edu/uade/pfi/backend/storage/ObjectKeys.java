package ar.edu.uade.pfi.backend.storage;

import java.util.regex.Pattern;

/**
 * Builds and validates object keys. No PHI (patient name, DNI) may appear in a key —
 * only opaque studyId/runId/reportId/assetId values already used as database primary
 * keys elsewhere in the backend.
 */
public final class ObjectKeys {

    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    private ObjectKeys() {
    }

    public static String studyInput(String studyId, String assetId, String filename) {
        return "studies/%s/inputs/%s/%s".formatted(
            requireSegment(studyId, "studyId"),
            requireSegment(assetId, "assetId"),
            requireFilename(filename));
    }

    public static String runOutput(String studyId, String runId, String filename) {
        return "studies/%s/outputs/%s/%s".formatted(
            requireSegment(studyId, "studyId"),
            requireSegment(runId, "runId"),
            requireFilename(filename));
    }

    public static String studyReport(String studyId, String reportId, String filename) {
        return "studies/%s/reports/%s/%s".formatted(
            requireSegment(studyId, "studyId"),
            requireSegment(reportId, "reportId"),
            requireFilename(filename));
    }

    private static String requireSegment(String value, String label) {
        if (value == null || !SEGMENT.matcher(value).matches()) {
            throw new ObjectStorageException(label + " invalido para una clave de almacenamiento durable.");
        }
        return value;
    }

    private static String requireFilename(String filename) {
        if (filename == null || filename.isBlank()
            || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new ObjectStorageException("filename invalido para una clave de almacenamiento durable.");
        }
        return filename;
    }
}
