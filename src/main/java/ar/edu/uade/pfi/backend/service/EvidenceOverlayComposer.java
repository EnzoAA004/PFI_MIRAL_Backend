package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * EXT-04B4: compone una imagen base de evidencia + su geometría en un único PNG raster, completamente
 * en memoria (Java2D {@link BufferedImage} — sin archivos temporales, sin dependencia de GUI/headful, funciona igual
 * en un contenedor Docker headless). Puro, sin estado y sin framework por diseño — sin
 * dependencia de Spring necesaria acá más allá de la anotación {@code @Component} para el wiring — por lo que es
 * trivialmente testeable de forma unitaria sin un contexto de Backend/Spring.
 *
 * <p>{@code geometry} ya está siempre en la base normalizada canónica 0..256 (la misma regla que
 * EXT-04B2/B3 ya estableció); esta clase solo escala esas coordenadas a las dimensiones de píxeles de
 * la imagen decodificada real — nunca vuelve a derivar ni reinterpreta el sistema de
 * coordenadas en sí.
 *
 * <p>Elección de color/estilo (Fase "COLOR"): un trazo rojo visible, sin relleno — el mismo lenguaje
 * visual que una estación de trabajo de radiología ya usa para señalar "mirá acá", nunca una escala de severidad.
 * Acá el rojo significa "región de interés señalada", nada sobre gravedad/malignidad/patología/estar
 * peor — documentado acá una sola vez para que nunca se reinterprete en silencio cuando se vuelva a tocar este archivo.
 * {@code MASK_REFERENCE} no dibuja nada encima: el asset obtenido ya ES la
 * máscara renderizada (el mismo precedente que {@code EvidenceRegionOverlay.tsx} ya sentó en el Frontend).
 */
@Component
public class EvidenceOverlayComposer {
    private static final Color OVERLAY_COLOR = new Color(220, 38, 38);
    private static final float STROKE_WIDTH = 2.5f;

    public byte[] composePng(byte[] baseImageBytes, EvidenceGeometry geometry) throws IOException {
        BufferedImage base = ImageIO.read(new ByteArrayInputStream(baseImageBytes));
        if (base == null) {
            throw new IOException("El recurso base no pudo decodificarse como imagen.");
        }
        BufferedImage canvas = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(base, 0, 0, null);
            drawGeometry(g, geometry, base.getWidth(), base.getHeight());
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        return out.toByteArray();
    }

    private void drawGeometry(Graphics2D g, EvidenceGeometry geometry, int widthPx, int heightPx) {
        double scaleX = widthPx / EvidenceGeometry.CANONICAL_MAX;
        double scaleY = heightPx / EvidenceGeometry.CANONICAL_MAX;
        g.setColor(OVERLAY_COLOR);
        g.setStroke(new BasicStroke(STROKE_WIDTH));
        if (geometry instanceof EvidenceGeometry.Bbox bbox) {
            g.drawRect(
                round(bbox.x() * scaleX), round(bbox.y() * scaleY),
                round(bbox.width() * scaleX), round(bbox.height() * scaleY)
            );
        } else if (geometry instanceof EvidenceGeometry.Ellipse ellipse) {
            int rx = round(ellipse.radiusX() * scaleX);
            int ry = round(ellipse.radiusY() * scaleY);
            int cx = round(ellipse.centerX() * scaleX);
            int cy = round(ellipse.centerY() * scaleY);
            g.drawOval(cx - rx, cy - ry, rx * 2, ry * 2);
        } else if (geometry instanceof EvidenceGeometry.Polygon polygon) {
            List<EvidenceGeometry.Polygon.Point> points = polygon.points();
            int[] xs = new int[points.size()];
            int[] ys = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                xs[i] = round(points.get(i).x() * scaleX);
                ys[i] = round(points.get(i).y() * scaleY);
            }
            g.drawPolygon(xs, ys, points.size());
        }
        // MASK_REFERENCE: nada que dibujar — el asset obtenido ya es la máscara renderizada.
    }

    private int round(double value) {
        return (int) Math.round(value);
    }
}
