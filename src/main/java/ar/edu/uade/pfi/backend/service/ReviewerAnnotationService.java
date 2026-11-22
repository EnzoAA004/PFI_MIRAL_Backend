package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.ReviewerAnnotation;
import ar.edu.uade.pfi.backend.dto.ReviewerAnnotationDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lee y reemplaza las anotaciones del revisor de una corrida.
 *
 * <p>Las anotaciones del revisor son su propio producto de trabajo clínico, así que nada acá las deriva,
 * completa ni reinterpreta: un payload que no puede ubicarse en una imagen (alcance slice sin
 * un slice, un alcance desconocido, un timestamp inválido) se rechaza con 400 en lugar de forzarse a
 * algo almacenable. El record de dominio aplica los mismos invariantes, que es lo que mantiene una
 * anotación malformada fuera de la base de datos aunque llegue por otro camino.
 */
@Service
public class ReviewerAnnotationService {
  private final StudyRepository repository;

  public ReviewerAnnotationService(StudyRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  public List<ReviewerAnnotationDto> find(String multiplanarRunId) {
    return repository.findAnnotationsByRunId(multiplanarRunId).stream().map(this::toDto).toList();
  }

  public List<ReviewerAnnotationDto> replace(
      String multiplanarRunId, List<ReviewerAnnotationDto> annotations) {
    List<ReviewerAnnotationDto> incoming = annotations == null ? List.of() : annotations;
    List<ReviewerAnnotation> domain =
        incoming.stream().map(dto -> toDomain(multiplanarRunId, dto)).toList();
    try {
      return repository.replaceAnnotations(multiplanarRunId, domain).stream()
          .map(this::toDto)
          .toList();
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Corrida no encontrada.");
    }
  }

  private ReviewerAnnotation toDomain(String multiplanarRunId, ReviewerAnnotationDto dto) {
    try {
      return new ReviewerAnnotation(
          identifier(dto.id()),
          multiplanarRunId,
          dto.scope(),
          dto.kind(),
          dto.measurementKind(),
          dto.plane(),
          dto.seriesId(),
          dto.sliceIndex(),
          dto.level(),
          dto.points(),
          dto.value(),
          dto.unit(),
          dto.text(),
          dto.author(),
          createdAt(dto.createdAt()));
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Anotacion invalida: " + ex.getMessage());
    }
  }

  /**
   * Conserva el id del cliente cuando es un UUID, genera uno en caso contrario.
   *
   * <p>La sala de lectura crea anotaciones localmente (ids como {@code measure-<ts>}) y solo
   * persiste al guardar, así que el primer ida y vuelta legítimamente trae un id que no es UUID. Generarlo en silencio
   * en ese caso es seguro — el cliente concilia contra la respuesta — mientras que obligar al cliente a
   * pregenerar UUIDs rompería el flujo local-first sin ninguna ganancia.
   */
  private String identifier(String id) {
    if (id == null || id.isBlank()) return UUID.randomUUID().toString();
    try {
      return UUID.fromString(id).toString();
    } catch (IllegalArgumentException ex) {
      return UUID.randomUUID().toString();
    }
  }

  private Instant createdAt(String value) {
    if (value == null || value.isBlank()) return Instant.now();
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "createdAt no es una fecha ISO-8601.");
    }
  }

  private ReviewerAnnotationDto toDto(ReviewerAnnotation annotation) {
    return new ReviewerAnnotationDto(
        annotation.id(),
        annotation.scope(),
        annotation.kind(),
        annotation.measurementKind(),
        annotation.plane(),
        annotation.seriesId(),
        annotation.sliceIndex(),
        annotation.level(),
        annotation.points(),
        annotation.value(),
        annotation.unit(),
        annotation.text(),
        annotation.author(),
        annotation.createdAt().toString());
  }
}
