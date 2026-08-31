package com.democode.mlmsittu.shared.storage.internal;

import com.democode.mlmsittu.shared.error.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Decides whether an upload is safe to keep, and rewrites it so it stays that way (P4-08).
 *
 * <p>Three defences, each closing a different hole:
 *
 * <ol>
 *   <li><b>Content sniffed from magic bytes, never the filename.</b> A filename is caller-supplied
 *       text; {@code payload.exe} renamed to {@code nic.jpg} passes any extension check ever
 *       written.
 *   <li><b>Images are decoded and re-encoded.</b> That drops EXIF — including the GPS coordinates
 *       of somebody's home — and neutralises anything smuggled in the file's metadata segments,
 *       because only pixels survive the round trip.
 *   <li><b>A hard size ceiling</b>, checked before decoding.
 * </ol>
 *
 * <p>Applies to bank payment slips exactly as it does to NIC scans: a slip is a photograph taken on
 * a phone, so it carries the same location metadata and deserves the same treatment.
 */
@Component
public class UploadSanitiser {

    private static final Logger log = LoggerFactory.getLogger(UploadSanitiser.class);

    /** Architecture §7.2. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    public record SanitisedUpload(byte[] content, String contentType) {}

    public SanitisedUpload sanitise(byte[] uploaded, String declaredContentType) {
        if (uploaded == null || uploaded.length == 0) {
            throw bad("EMPTY_UPLOAD", "The file is empty.");
        }
        if (uploaded.length > MAX_BYTES) {
            ApiException tooLarge =
                    bad("FILE_TOO_LARGE", "Files must be 10 MB or smaller.");
            tooLarge.with("maxBytes", MAX_BYTES);
            tooLarge.with("actualBytes", uploaded.length);
            throw tooLarge;
        }

        String sniffed = sniff(uploaded);
        if (sniffed == null) {
            // Deliberately does not say what was detected. Telling an attacker which byte patterns
            // are recognised is free reconnaissance, and a legitimate user only needs to know
            // which formats are accepted.
            log.warn(
                    "Rejected upload declaring {} — magic bytes match no accepted format",
                    declaredContentType);
            throw bad(
                    "UNSUPPORTED_FILE_TYPE",
                    "Only JPEG, PNG and PDF files are accepted. Renaming a file does not change"
                        + " its type.");
        }

        if (sniffed.equals("application/pdf")) {
            // A PDF cannot be re-encoded without a rendering library, so it is stored as-is.
            // Acceptable because nothing ever serves it inline: downloads go out as an attachment
            // with a nosniff header, so an embedded script has no context to execute in.
            return new SanitisedUpload(uploaded, sniffed);
        }

        return new SanitisedUpload(stripMetadataByReEncoding(uploaded, sniffed), sniffed);
    }

    /**
     * Identifies content by its leading bytes.
     *
     * @return the detected media type, or null when it is not one we accept
     */
    private String sniff(byte[] content) {
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(content, 0x25, 0x50, 0x44, 0x46)) {
            return "application/pdf";
        }
        return null;
    }

    private boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((content[index] & 0xFF) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes to pixels and writes a fresh file.
     *
     * <p>Everything that is not image data — EXIF, GPS, XMP, colour profiles, arbitrary
     * application segments — is left behind, because the output is built from the decoded raster
     * alone. That is a stronger guarantee than walking the metadata and deleting the tags one
     * happens to know about.
     */
    private byte[] stripMetadataByReEncoding(byte[] content, String contentType) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(content));
            if (decoded == null) {
                // Magic bytes said image, the decoder disagrees. A malformed or hostile file.
                throw bad("UNREADABLE_IMAGE", "That image could not be read.");
            }

            String format = contentType.equals("image/png") ? "png" : "jpg";
            BufferedImage forOutput = decoded;

            if (format.equals("jpg") && decoded.getColorModel().hasAlpha()) {
                // JPEG has no alpha channel; writing one produces a corrupt file or a colour
                // shift, so flatten onto white first.
                forOutput =
                        new BufferedImage(
                                decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = forOutput.createGraphics();
                graphics.drawImage(decoded, 0, 0, java.awt.Color.WHITE, null);
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(forOutput, format, output)) {
                throw bad("UNREADABLE_IMAGE", "That image could not be processed.");
            }
            return output.toByteArray();

        } catch (IOException e) {
            log.warn("Failed to re-encode an uploaded image", e);
            throw bad("UNREADABLE_IMAGE", "That image could not be read.");
        }
    }

    private ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
