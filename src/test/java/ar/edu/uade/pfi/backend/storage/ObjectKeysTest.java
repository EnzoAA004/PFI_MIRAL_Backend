package ar.edu.uade.pfi.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ObjectKeysTest {

    @Test
    void studyInputKeyHasNoPhiAndFollowsContract() {
        String key = ObjectKeys.studyInput("study-1", "asset-1", "study.zip");
        assertEquals("studies/study-1/inputs/asset-1/study.zip", key);
        assertFalse(key.toLowerCase(java.util.Locale.ROOT).contains("dni"));
    }

    @Test
    void runOutputKeyFollowsContract() {
        assertEquals("studies/s1/outputs/r1/overlay.png", ObjectKeys.runOutput("s1", "r1", "overlay.png"));
    }

    @Test
    void reportKeyFollowsContract() {
        assertEquals("studies/s1/reports/rep1/report.pdf", ObjectKeys.studyReport("s1", "rep1", "report.pdf"));
    }

    @Test
    void rejectsPathTraversalInFilename() {
        assertThrows(ObjectStorageException.class, () -> ObjectKeys.studyInput("s1", "a1", "../../etc/passwd"));
    }

    @Test
    void rejectsBlankSegments() {
        assertThrows(ObjectStorageException.class, () -> ObjectKeys.studyInput("", "a1", "study.zip"));
        assertThrows(ObjectStorageException.class, () -> ObjectKeys.studyInput("s1", null, "study.zip"));
    }
}
