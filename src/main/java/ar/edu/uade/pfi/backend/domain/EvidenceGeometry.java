package ar.edu.uade.pfi.backend.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Geometría de un {@link AiEvidenceRegion} — EXT-04B2. Una unión cerrada de las cuatro formas en las que
 * se asentó el diseño EXT-04B1 (BBOX / ELLIPSE / POLYGON / MASK_REFERENCE), siempre expresada en
 * la base normalizada canónica 0..256 ya compartida por {@link ReviewerAnnotation} y las
 * máscaras/landmarks multiplanares que renderiza el viewer del Frontend (auditoría EXT-04B1, sección 8) — nunca
 * coordenadas físicas DICOM, que ninguna parte de este stack guarda ni valida hoy.
 *
 * <p>Un {@code MASK_REFERENCE} nunca lleva datos de píxeles inline: solo apunta a un
 * asset ya existente y ya autenticado — el mismo
 * {@code /api/ai/assets/{runId}/{plane}/{assetName}} que el viewer ya consulta — nunca un
 * path del filesystem ni un blob crudo en esta fila (EXT-04B1 sección 7/14).
 */
public sealed interface EvidenceGeometry {
    double CANONICAL_MIN = 0.0;
    double CANONICAL_MAX = 256.0;

    String TYPE_BBOX = "BBOX";
    String TYPE_ELLIPSE = "ELLIPSE";
    String TYPE_POLYGON = "POLYGON";
    String TYPE_MASK_REFERENCE = "MASK_REFERENCE";

    /** Serializa al mapa plano persistido en la columna JSONB {@code geometry}. */
    Map<String, Object> toMap();

    static String typeOf(EvidenceGeometry geometry) {
        if (geometry instanceof Bbox) return TYPE_BBOX;
        if (geometry instanceof Ellipse) return TYPE_ELLIPSE;
        if (geometry instanceof Polygon) return TYPE_POLYGON;
        if (geometry instanceof MaskReference) return TYPE_MASK_REFERENCE;
        throw new IllegalArgumentException("tipo de geometría desconocido: " + geometry.getClass());
    }

    /**
     * Parsea de vuelta un par {@code (geometryType, geometry)} persistido/entrante a una forma tipada y
     * validada. Este es el único lugar donde ocurre la validación de forma (replicada por el
     * compact constructor propio de cada record más abajo) — un {@code geometryType} desconocido o un
     * mapa malformado siempre se rechaza acá, nunca se acepta en silencio más adelante.
     */
    @SuppressWarnings("unchecked")
    static EvidenceGeometry fromMap(String geometryType, Map<String, Object> geometry) {
        Objects.requireNonNull(geometryType, "geometryType");
        Objects.requireNonNull(geometry, "geometry");
        return switch (geometryType) {
            case TYPE_BBOX -> new Bbox(number(geometry, "x"), number(geometry, "y"), number(geometry, "width"), number(geometry, "height"));
            case TYPE_ELLIPSE -> new Ellipse(number(geometry, "centerX"), number(geometry, "centerY"), number(geometry, "radiusX"), number(geometry, "radiusY"));
            case TYPE_POLYGON -> new Polygon(polygonPoints(geometry));
            case TYPE_MASK_REFERENCE -> new MaskReference(text(geometry, "runId"), text(geometry, "plane"), text(geometry, "assetName"));
            default -> throw new IllegalArgumentException("geometryType desconocido: " + geometryType);
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Polygon.Point> polygonPoints(Map<String, Object> geometry) {
        Object raw = geometry.get("points");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("points es obligatorio y debe ser una lista para POLYGON");
        }
        return list.stream()
            .map(item -> {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("cada punto de POLYGON debe ser un objeto {x,y}");
                }
                return new Polygon.Point(number((Map<String, Object>) map, "x"), number((Map<String, Object>) map, "y"));
            })
            .toList();
    }

    private static double number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();
        throw new IllegalArgumentException("campo numérico faltante o inválido: " + key);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof String text && !text.isBlank()) return text;
        throw new IllegalArgumentException("campo de texto faltante o inválido: " + key);
    }

    private static void requireInRange(double value, String field) {
        if (Double.isNaN(value) || value < CANONICAL_MIN || value > CANONICAL_MAX) {
            throw new IllegalArgumentException(field + " fuera de rango 0.." + (int) CANONICAL_MAX + ": " + value);
        }
    }

    record Bbox(double x, double y, double width, double height) implements EvidenceGeometry {
        public Bbox {
            requireInRange(x, "x");
            requireInRange(y, "y");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width/height deben ser positivos");
            }
            requireInRange(x + width, "x+width");
            requireInRange(y + height, "y+height");
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", x);
            map.put("y", y);
            map.put("width", width);
            map.put("height", height);
            return map;
        }
    }

    record Ellipse(double centerX, double centerY, double radiusX, double radiusY) implements EvidenceGeometry {
        public Ellipse {
            requireInRange(centerX, "centerX");
            requireInRange(centerY, "centerY");
            if (radiusX <= 0 || radiusY <= 0) {
                throw new IllegalArgumentException("radiusX/radiusY deben ser positivos");
            }
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("centerX", centerX);
            map.put("centerY", centerY);
            map.put("radiusX", radiusX);
            map.put("radiusY", radiusY);
            return map;
        }
    }

    record Polygon(List<Point> points) implements EvidenceGeometry {
        public Polygon {
            Objects.requireNonNull(points, "points");
            if (points.size() < 3) {
                throw new IllegalArgumentException("un polígono necesita al menos 3 puntos");
            }
            points = List.copyOf(points);
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("points", points.stream().map(Point::toMap).toList());
            return map;
        }

        public record Point(double x, double y) {
            public Point {
                requireInRange(x, "x");
                requireInRange(y, "y");
            }

            Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("x", x);
                map.put("y", y);
                return map;
            }
        }
    }

    record MaskReference(String runId, String plane, String assetName) implements EvidenceGeometry {
        private static final List<String> PLANES = List.of("sagittal", "axial");

        public MaskReference {
            requireNonBlank(runId, "runId");
            requireNonBlank(assetName, "assetName");
            if (!PLANES.contains(plane)) {
                throw new IllegalArgumentException("plane inválido: " + plane);
            }
            if (assetName.contains("..") || assetName.contains("/") || assetName.contains("\\")) {
                throw new IllegalArgumentException("assetName inválido (posible path traversal): " + assetName);
            }
            if (runId.contains("..") || runId.contains("/") || runId.contains("\\")) {
                throw new IllegalArgumentException("runId inválido (posible path traversal): " + runId);
            }
        }

        private static void requireNonBlank(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " no puede estar vacío");
            }
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runId", runId);
            map.put("plane", plane);
            map.put("assetName", assetName);
            return map;
        }
    }
}
