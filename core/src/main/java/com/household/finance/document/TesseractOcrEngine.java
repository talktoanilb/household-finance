package com.household.finance.document;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local OCR with the Tesseract command-line program. Nothing leaves the machine.
 * <p>
 * Phone photos are large (12 to 24 megapixels) and are often stored sideways with no rotation tag, which Tesseract
 * cannot see. So the photo is first shrunk to at most {@value #MAX_EDGE} pixels on its long side and turned to grey,
 * then read at 0, 90, 270 and 180 degrees. The rotation with the most confidently read words wins, and reading stops
 * early once a rotation reads clearly. The text of the winning rotation is returned to the caller and never logged.
 * <p>
 * Files go in a private temp folder that is deleted afterwards, and Tesseract runs inside that folder on relative file
 * names (its image library rewrites absolute paths under /tmp on macOS). If Tesseract is not installed, reading fails
 * with a plain message. Set {@code household.ocr.tesseract-path} when the program is not on the PATH.
 */
@Component
public class TesseractOcrEngine implements OcrEngine {

    static final int MAX_EDGE = 1600;
    static final long MAX_PIXELS = 64_000_000L;
    /** A word counts as confidently read at this Tesseract confidence (0 to 100) and at least 3 characters. */
    static final double CONFIDENT = 70.0;
    /** Reading stops at the first rotation with this many confidently read words. */
    static final int CLEAR_ENOUGH = 15;
    private static final int[] ROTATIONS = {0, 90, 270, 180};
    /** Long-side sizes of the extra readings made by {@link #readAll}. Small images are enlarged, up to twice their size. */
    static final int[] EXTRA_EDGES = {2400, 3200};

    private final String executable;
    private final long timeoutSeconds;

    @Autowired
    public TesseractOcrEngine(@Value("${household.ocr.tesseract-path:tesseract}") String executable) {
        this(executable, 60);
    }

    TesseractOcrEngine(String executable, long timeoutSeconds) {
        this.executable = executable;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String read(byte[] image) throws IOException {
        return readings(image, new int[0]).get(0);
    }

    @Override
    public List<String> readAll(byte[] image) throws IOException {
        return readings(image, EXTRA_EDGES);
    }

    /** The first reading is at the standard size; one more is made at each size in {@code extraEdges}. */
    private List<String> readings(byte[] image, int[] extraEdges) throws IOException {
        BufferedImage base = decodeScaled(image, MAX_EDGE, false);
        Path dir = Files.createTempDirectory("household-ocr-");
        try {
            int bestAngle = 0;
            int bestScore = -1;
            for (int angle : ROTATIONS) {
                ImageIO.write(rotate(base, angle), "png", dir.resolve("page-" + angle + ".png").toFile());
                int score = confidentWords(run(dir, "page-" + angle + ".png", "tsv"));
                if (score > bestScore) {
                    bestScore = score;
                    bestAngle = angle;
                }
                if (score >= CLEAR_ENOUGH) {
                    break;
                }
            }
            List<String> texts = new ArrayList<>();
            texts.add(run(dir, "page-" + bestAngle + ".png", null));
            for (int edge : extraEdges) {
                String name = "page-" + bestAngle + "-" + edge + ".png";
                ImageIO.write(rotate(decodeScaled(image, edge, true), bestAngle), "png", dir.resolve(name).toFile());
                texts.add(run(dir, name, null));
            }
            return texts;
        } finally {
            deleteQuietly(dir);
        }
    }

    /** Runs Tesseract on one file inside {@code dir} and returns what it printed. {@code format} is "tsv" or null for plain text. */
    private String run(Path dir, String fileName, String format) throws IOException {
        Path output = dir.resolve("out-" + System.nanoTime() + ".txt");
        // Page segmentation mode 6: one uniform block of text, which keeps label and amount on the same row.
        ProcessBuilder builder = format == null
                ? new ProcessBuilder(executable, fileName, "stdout", "-l", "eng", "--psm", "6")
                : new ProcessBuilder(executable, fileName, "stdout", "-l", "eng", "--psm", "6", format);
        builder.directory(dir.toFile());
        builder.redirectOutput(output.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new OcrUnavailableException("Photo reading is not set up on this machine: Tesseract was not found."
                    + " Install Tesseract, or set household.ocr.tesseract-path. Nothing was saved.", e);
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Reading the photo took too long.");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Reading the photo was interrupted.");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Tesseract could not read this image.");
        }
        return new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
    }

    /** Counts words of at least 3 characters that Tesseract read with confidence of at least {@value #CONFIDENT}. */
    static int confidentWords(String tsv) {
        int count = 0;
        for (String line : tsv.split("\n")) {
            String[] cols = line.split("\t", -1);
            if (cols.length < 12 || !"5".equals(cols[0])) {
                continue;
            }
            try {
                if (Double.parseDouble(cols[10]) >= CONFIDENT && cols[11].trim().length() >= 3) {
                    count++;
                }
            } catch (NumberFormatException ignored) {
                // The header row, or a row without a confidence: not a word.
            }
        }
        return count;
    }

    /**
     * Reads the photo, refusing anything over 64 megapixels before decoding it, and subsampling large JPEGs while
     * reading so a 24-megapixel photo never sits fully in memory. The result is grey, with a long side of at most 2400.
     */
    static BufferedImage decodeScaled(byte[] image) throws IOException {
        return decodeScaled(image, MAX_EDGE, false);
    }

    /** As above, for a chosen long side. With {@code allowUpscale}, a smaller image is enlarged, up to twice its size. */
    static BufferedImage decodeScaled(byte[] image, int edge, boolean allowUpscale) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new UnusablePhotoException("The photo could not be opened as an image. Nothing was saved.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) {
                    throw new UnusablePhotoException("The photo is too large (over 64 megapixels). Nothing was saved.");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / edge);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return toGrey(reader.read(0, param), edge, allowUpscale);
            } catch (IOException e) {
                if (e instanceof UnusablePhotoException) {
                    throw e;
                }
                throw new UnusablePhotoException("The photo could not be opened as an image. Nothing was saved.");
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage toGrey(BufferedImage source, int edge, boolean allowUpscale) {
        int w = source.getWidth();
        int h = source.getHeight();
        double wanted = (double) edge / Math.max(w, h);
        double scale = allowUpscale ? Math.min(2.0, wanted) : Math.min(1.0, wanted);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** Turns the image clockwise by 0, 90, 180 or 270 degrees. */
    static BufferedImage rotate(BufferedImage source, int degrees) {
        if (degrees == 0) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        boolean sideways = degrees == 90 || degrees == 270;
        BufferedImage out = new BufferedImage(sideways ? h : w, sideways ? w : h, BufferedImage.TYPE_BYTE_GRAY);
        AffineTransform t = new AffineTransform();
        switch (degrees) {
            case 90 -> {
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
            }
            case 180 -> {
                t.translate(w, h);
                t.rotate(Math.PI);
            }
            case 270 -> {
                t.translate(0, w);
                t.rotate(3 * Math.PI / 2);
            }
            default -> throw new IllegalArgumentException("Rotation must be 0, 90, 180 or 270.");
        }
        Graphics2D g = out.createGraphics();
        g.drawImage(source, t, null);
        g.dispose();
        return out;
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // Best effort: the folder lives in the OS temp area.
        }
    }
}
