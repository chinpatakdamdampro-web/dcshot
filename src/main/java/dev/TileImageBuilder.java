package dev.discordshot;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.logging.Logger;

/**
 * Fetches BlueMap lowres tiles via plain HTTP and stitches them into one image.
 *
 * BlueMap serves flat PNG tiles at:
 *   {bluemap-url}/maps/{mapId}/tiles/0/{tileX}_{tileZ}.png   (lowres, zoom-level 0)
 *
 * Each lowres tile covers a 512×512 block region at 1 px per 2 blocks (256×256 px).
 * We fetch a grid of tiles centred on the requested block coordinate and stitch them.
 *
 * No browser, no Chromium, no Playwright — just Java's built-in HttpClient and ImageIO.
 */
public class TileImageBuilder {

    // BlueMap lowres: each tile = 512 blocks wide/deep, rendered as 256×256 px.
    private static final int BLOCKS_PER_TILE = 512;
    private static final int PIXELS_PER_TILE = 256;

    private final String bluemapUrl;
    private final int gridSize;   // e.g. 3 → 3×3 tile grid
    private final String format;  // "PNG" or "JPEG"
    private final int jpegQuality;
    private final Logger log;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public TileImageBuilder(String bluemapUrl, int gridSize, String format, int jpegQuality, Logger log) {
        // Strip trailing slash so URL construction is uniform
        this.bluemapUrl  = bluemapUrl.endsWith("/") ? bluemapUrl.substring(0, bluemapUrl.length() - 1) : bluemapUrl;
        this.gridSize    = (gridSize < 1) ? 3 : gridSize;
        this.format      = format.toUpperCase().equals("JPEG") ? "JPEG" : "PNG";
        this.jpegQuality = jpegQuality;
        this.log         = log;
    }

    /**
     * Builds a stitched image centred on (blockX, blockZ) in the given BlueMap map.
     *
     * @param mapId  BlueMap map ID (e.g. "world")
     * @param blockX Minecraft X coordinate
     * @param blockZ Minecraft Z coordinate
     * @return raw image bytes (PNG or JPEG)
     */
    public byte[] buildImage(String mapId, int blockX, int blockZ) throws IOException, InterruptedException {

        // Convert block coordinates to tile coordinates
        int centreTileX = Math.floorDiv(blockX, BLOCKS_PER_TILE);
        int centreTileZ = Math.floorDiv(blockZ, BLOCKS_PER_TILE);

        int half   = gridSize / 2;
        int startX = centreTileX - half;
        int startZ = centreTileZ - half;

        int canvasW = gridSize * PIXELS_PER_TILE;
        int canvasH = gridSize * PIXELS_PER_TILE;

        // ARGB canvas so we can paint a dark background where tiles are missing
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, canvasW, canvasH);

        int tilesLoaded = 0;
        for (int dz = 0; dz < gridSize; dz++) {
            for (int dx = 0; dx < gridSize; dx++) {
                int tileX = startX + dx;
                int tileZ = startZ + dz;
                BufferedImage tile = fetchTile(mapId, tileX, tileZ);
                if (tile != null) {
                    int px = dx * PIXELS_PER_TILE;
                    int py = dz * PIXELS_PER_TILE;
                    g.drawImage(tile, px, py, PIXELS_PER_TILE, PIXELS_PER_TILE, null);
                    tilesLoaded++;
                }
            }
        }

        // Draw a small crosshair at the exact requested coordinate
        drawCrosshair(g, blockX, blockZ, centreTileX, centreTileZ, startX, startZ);

        g.dispose();

        if (tilesLoaded == 0) {
            throw new IOException(
                "No BlueMap tiles found for map '" + mapId + "' around (" + blockX + ", " + blockZ + "). " +
                "Check that BlueMap is running and has rendered this area.");
        }

        log.info("[DiscordShot] Stitched " + tilesLoaded + "/" + (gridSize * gridSize) + " tiles for " + mapId);
        return encodeImage(canvas);
    }

    // -----------------------------------------------------------------------

    /** Fetch one lowres tile PNG from BlueMap, return null if 404 / error. */
    private BufferedImage fetchTile(String mapId, int tileX, int tileZ) throws InterruptedException {
        // BlueMap lowres tile URL format (v3+):
        //   /maps/{mapId}/tiles/0/{tileX}_{tileZ}.png
        String url = bluemapUrl + "/maps/" + mapId + "/tiles/0/" + tileX + "_" + tileZ + ".png";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 200) {
                try (InputStream is = resp.body()) {
                    return ImageIO.read(is);
                }
            }
            // 404 is normal for unrendered tiles — just return null
        } catch (IOException e) {
            log.warning("[DiscordShot] Could not fetch tile " + tileX + "_" + tileZ + ": " + e.getMessage());
        }
        return null;
    }

    /** Draw a small red crosshair at the exact block coordinate on the canvas. */
    private void drawCrosshair(Graphics2D g,
                                int blockX, int blockZ,
                                int centreTileX, int centreTileZ,
                                int startTileX, int startTileZ) {
        // Pixel offset within the tile for the centre block
        double fracX = (blockX - (double)(centreTileX * BLOCKS_PER_TILE)) / BLOCKS_PER_TILE;
        double fracZ = (blockZ - (double)(centreTileZ * BLOCKS_PER_TILE)) / BLOCKS_PER_TILE;

        int cx = (centreTileX - startTileX) * PIXELS_PER_TILE + (int)(fracX * PIXELS_PER_TILE);
        int cz = (centreTileZ - startTileZ) * PIXELS_PER_TILE + (int)(fracZ * PIXELS_PER_TILE);

        int arm = 8;
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(220, 0, 0, 200));
        g.drawLine(cx - arm, cz, cx + arm, cz);
        g.drawLine(cx, cz - arm, cx, cz + arm);
        // White outline for visibility on dark terrain
        g.setColor(new Color(255, 255, 255, 120));
        g.setStroke(new BasicStroke(4f));
        g.drawLine(cx - arm, cz, cx + arm, cz);
        g.drawLine(cx, cz - arm, cx, cz + arm);
        // Redraw red on top
        g.setColor(new Color(220, 0, 0, 200));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(cx - arm, cz, cx + arm, cz);
        g.drawLine(cx, cz - arm, cx, cz + arm);
    }

    /** Encode the canvas to the configured format. */
    private byte[] encodeImage(BufferedImage img) throws IOException {
        BufferedImage out = img;

        // JPEG cannot have alpha channel — flatten to RGB
        if (format.equals("JPEG")) {
            out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(new Color(30, 30, 30));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (format.equals("JPEG")) {
            Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) throw new IOException("No JPEG writer found");
            javax.imageio.ImageWriter writer = writers.next();
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(jpegQuality / 100f);
            javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(out, null, null), param);
            writer.dispose();
        } else {
            ImageIO.write(out, "PNG", baos);
        }
        return baos.toByteArray();
    }
}
