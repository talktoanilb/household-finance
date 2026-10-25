package com.household.finance.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local OCR with the Tesseract command-line program. Nothing leaves the machine.
 * <p>
 * The photo is written to a private temp folder, read, and the folder is deleted afterwards. The recognised text is
 * returned to the caller and never logged. If Tesseract is not installed, reading fails with a plain message.
 * Set {@code household.ocr.tesseract-path} when the program is not on the PATH.
 */
@Component
public class TesseractOcrEngine implements OcrEngine {

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
        Path dir = Files.createTempDirectory("household-ocr-");
        try {
            Path input = dir.resolve("photo");
            Path output = dir.resolve("text.txt");
            Files.write(input, image);

            // Page segmentation mode 6: one uniform block of text, which keeps label and amount on the same row.
            ProcessBuilder builder = new ProcessBuilder(executable, input.toString(), "stdout", "-l", "eng", "--psm", "6");
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
        } finally {
            deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // Best effort: the folder lives in the OS temp area.
        }
    }
}
