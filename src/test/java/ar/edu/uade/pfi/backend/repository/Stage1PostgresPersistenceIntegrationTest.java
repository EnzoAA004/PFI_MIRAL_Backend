package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.Patient;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL acceptance check for the first LumbarAI patient/context stage.
 *
 * <p>Uses an isolated PostgreSQL Testcontainer, applies the repository's real pre-context and
 * context migrations, seeds a legacy study, and reopens every object through a new repository.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Stage1PostgresPersistenceIntegrationTest {
  private static final java.util.List<String> CONTEXT_MIGRATIONS = java.util.List.of(
      "V20261120_027_patient_profile.sql",
      "V20261120_028_study_clinical_context.sql",
      "V20261120_029_chat_context_version.sql");

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
      .withDatabaseName("pfi_stage1_persistence")
      .withUsername("pfi")
      .withPassword("pfi");

  private String databaseUrl;

  @BeforeAll
  void createIsolatedDatabaseAndApplyMigrations() throws Exception {
    databaseUrl = postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword();

    Path root = Files.createTempDirectory("lumbarai-stage1-migrations-");
    Path legacy = Files.createDirectory(root.resolve("legacy"));
    Path newMigrations = Files.createDirectory(root.resolve("new"));
    try (var stream = Files.list(SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY)) {
      for (Path migration : stream.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
        if (CONTEXT_MIGRATIONS.contains(migration.getFileName().toString())) {
          Files.copy(migration, newMigrations.resolve(migration.getFileName()));
        } else {
          Files.copy(migration, legacy.resolve(migration.getFileName()));
        }
      }
    }
    try (var connection = DriverManager.getConnection(databaseUrl)) {
      new SqlMigrationRunner(legacy).apply(connection);
      try (var statement = connection.prepareStatement(
          "INSERT INTO domain_studies(id, case_id, status, subject_ref) VALUES (?::uuid, ?, ?, ?)")) {
        statement.setString(1, UUID.randomUUID().toString());
        statement.setString(2, "SYN-LEGACY-STUDY");
        statement.setString(3, "ready");
        statement.setString(4, "SYN-LEGACY-SUBJECT");
        statement.executeUpdate();
      }
    }
    try (var connection = DriverManager.getConnection(databaseUrl)) {
      new SqlMigrationRunner(newMigrations).apply(connection);
    }
  }

  @Test
    void persistsContextAndKeepsLegacyRelationshipsAcrossFreshReads() throws Exception {
    PostgresPatientRepository patients = new PostgresPatientRepository(databaseUrl, false);
    PostgresStudyRepository studies = new PostgresStudyRepository(new ObjectMapper().findAndRegisterModules(), databaseUrl, false);
    Instant now = Instant.parse("2026-10-06T12:00:00Z");
    Patient patient = new Patient(UUID.randomUUID().toString(), "SYN-PATIENT-001", "Ada", "Prueba", LocalDate.of(1985, 6, 15), now, now);
    patients.save(patient);

    Patient reloadedPatient = new PostgresPatientRepository(databaseUrl, false).findById(patient.id()).orElseThrow();
    assertEquals("SYN-PATIENT-001", reloadedPatient.patientReference());
    assertEquals("Ada", reloadedPatient.firstName());
    assertEquals(LocalDate.of(1985, 6, 15), reloadedPatient.dateOfBirth());

    new StudyRunService(studies, patients).upsertStudyMetadata("SYN-STUDY-CALCULATED", "ready", new StudyMetadataDto(
        null, LocalDate.of(2026, 6, 14), "MR", "Edad calculada", "medium", patient.id(), null, null,
        null, null, null, null, null));
    Study calculatedAgeStudy = new PostgresStudyRepository(new ObjectMapper().findAndRegisterModules(), databaseUrl, false)
        .findStudyByCaseId("SYN-STUDY-CALCULATED").orElseThrow();
    assertEquals(40, calculatedAgeStudy.ageAtStudy());
    assertEquals("calculated", calculatedAgeStudy.ageProvenance());

    Study study = new Study(
        UUID.randomUUID().toString(), "SYN-STUDY-CONTEXT", "ready", patient.id(), "SYN-LONGITUDINAL-001",
        LocalDate.of(2026, 6, 14), "MR", "Dolor lumbar", "high", 40, "reported",
        "Dolor mecánico", "Irradiación", "3 meses", "No informado", "pfi.study-context.v1", now, now);
    studies.saveStudy(study);

    Study reloadedStudy = new PostgresStudyRepository(new ObjectMapper().findAndRegisterModules(), databaseUrl, false)
        .findStudyByCaseId("SYN-STUDY-CONTEXT").orElseThrow();
    assertEquals(patient.id(), reloadedStudy.patientId());
    assertEquals("SYN-LONGITUDINAL-001", reloadedStudy.subjectRef());
    assertEquals(40, reloadedStudy.ageAtStudy());
    assertEquals("reported", reloadedStudy.ageProvenance());
    assertEquals("Dolor mecánico", reloadedStudy.clinicalReason());
    assertEquals("Irradiación", reloadedStudy.symptoms());
    assertEquals("3 meses", reloadedStudy.symptomDuration());
    assertEquals("No informado", reloadedStudy.relevantHistory());
    assertEquals("pfi.study-context.v1", reloadedStudy.contextVersion());

    patients.updateProfile(patient.id(), patient.patientReference(), patient.firstName(), patient.lastName(), LocalDate.of(1985, 7, 1), now.plusSeconds(1));
    Study afterPatientChange = new PostgresStudyRepository(new ObjectMapper().findAndRegisterModules(), databaseUrl, false)
        .findStudyByCaseId("SYN-STUDY-CONTEXT").orElseThrow();
    assertEquals(40, afterPatientChange.ageAtStudy());
    assertEquals("reported", afterPatientChange.ageProvenance());

    Study legacy = new PostgresStudyRepository(new ObjectMapper().findAndRegisterModules(), databaseUrl, false)
        .findStudyByCaseId("SYN-LEGACY-STUDY").orElseThrow();
    assertEquals("SYN-LEGACY-SUBJECT", legacy.subjectRef());
    assertNull(legacy.patientId());
    assertTrue(countStudiesWithoutPatient() >= 1);
  }

  private int countStudiesWithoutPatient() throws Exception {
    try (var connection = DriverManager.getConnection(databaseUrl);
        var statement = connection.prepareStatement("SELECT COUNT(*) FROM domain_studies WHERE patient_id IS NULL");
        var result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }
}
