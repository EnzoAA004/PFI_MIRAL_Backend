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
 * EXT-04B4: composes one evidence base image + its geometry into a single raster PNG, entirely
 * in memory (Java2D {@link BufferedImage} — no temp files, no GUI/headful dependency, works the
 * same in a headless Docker container). Pure, stateless, and framework-free by design — no
 * Spring dependency needed here beyond the {@code @Component} annotation for wiring — so it is
 * trivially unit-testable without a Backend/Spring context.
 *
 * <p>{@code geometry} is always already in the canonical normalised 0..256 basis (same rule
 * EXT-04B2/B3 already established); this class only ever scales those coordinates to the actual
 * decoded image's own pixel dimensions — it never re-derives or reinterprets the coordinate
 * system itself.
 *
 * <p>Color/style choice (Phase "COLOR"): a visible red stroke, no fill — the same visual
 * language a radiology workstation already uses to point at "look here," never a severity scale.
 * Red here means "región de interés señalada," nothing about gravity/malignancy/pathology/being
 * worse — documented here once so it is never silently reinterpreted while this file is touched
 * again. {@code MASK_REFERENCE} draws nothing on top: the fetched asset already IS the rendered
 * mask (same precedent {@code EvidenceRegionOverlay.tsx} already set on the Frontend).
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
        // MASK_REFERENCE: nothing to draw — the fetched asset is already the rendered mask.
    }

    private int round(double value) {
        return (int) Math.round(value);
    }
}
