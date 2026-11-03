package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.common.exception.RequestValidationException;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * Turns an untrusted upload into an image this server is willing to serve.
 *
 * <ol>
 *   <li><b>Type from the bytes</b>, not from the file name or the client's Content-Type: only JPEG
 *       and PNG signatures are accepted. An HTML or SVG file renamed to {@code .png} fails here.</li>
 *   <li><b>Dimensions before pixels.</b> The header is read first; more than 6000 px a side, or
 *       25 megapixels, is refused before decoding. A 50 KB "decompression bomb" claiming 100 000 x
 *       100 000 pixels would otherwise need tens of gigabytes of memory.</li>
 *   <li><b>Decode and re-encode.</b> What is stored is a fresh encoding of the decoded pixels: EXIF
 *       metadata (GPS position, camera serial, the owner's name) is gone, and so is anything
 *       appended to or hidden in the original file (polyglot payloads).</li>
 * </ol>
 */
@Component
class ImageProcessor {

    static final int MAX_SIDE = 6000;
    static final long MAX_PIXELS = 25_000_000L;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** A clean image: the re-encoded bytes and what they are. */
    record Processed(byte[] bytes, String contentType, String extension, int width, int height) {
    }

    Processed process(byte[] upload) {
        String format = formatOf(upload);
        BufferedImage image = decode(upload, format);
        return format.equals("png")
                ? new Processed(encodePng(image), "image/png", "png", image.getWidth(), image.getHeight())
                : new Processed(encodeJpeg(image), "image/jpeg", "jpg", image.getWidth(), image.getHeight());
    }

    private static String formatOf(byte[] upload) {
        if (startsWith(upload, PNG)) {
            return "png";
        }
        if (startsWith(upload, JPEG)) {
            return "jpeg";
        }
        throw invalid("must be a JPEG or PNG image");
    }

    private static BufferedImage decode(byte[] upload, String format) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext() || input == null) {
                throw invalid("could not be read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true); // ignore metadata: never parsed, never kept
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                    throw invalid("must be at most " + MAX_SIDE + " pixels on each side and 25 megapixels");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw invalid("could not be read");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof RequestValidationException validation) {
                throw validation;
            }
            throw invalid("is damaged or not a valid image");
        }
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("No PNG writer");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG encoding failed", e);
        }
    }

    /** JPEG has no transparency: draw onto an opaque RGB canvas first. */
    private static byte[] encodeJpeg(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, java.awt.Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(rgb, null, null), params); // no metadata
            stream.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed", e);
        } finally {
            writer.dispose();
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static RequestValidationException invalid(String message) {
        return RequestValidationException.of("file", message);
    }
}
