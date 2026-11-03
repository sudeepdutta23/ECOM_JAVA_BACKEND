package com.ecommerce.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.common.exception.RequestValidationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    static byte[] image(String format, int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0xFF0000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        return ByteBuffer.allocate(a.length + b.length).put(a).put(b).array();
    }

    @Test
    void jpegAndPngAreAcceptedAndReEncoded() {
        ImageProcessor.Processed png = processor.process(image("png", 40, 30));
        ImageProcessor.Processed jpeg = processor.process(image("jpg", 40, 30));

        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(png.width()).isEqualTo(40);
        assertThat(png.height()).isEqualTo(30);
        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(jpeg.extension()).isEqualTo("jpg");
    }

    @Test
    void anythingHiddenAfterTheImageDataIsGoneAfterReEncoding() {
        byte[] payload = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        byte[] polyglot = concat(image("png", 10, 10), payload);

        byte[] stored = processor.process(polyglot).bytes();

        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("<script>");
    }

    @Test
    void reEncodedJpegsCarryNoExifSegment() {
        byte[] stored = processor.process(image("jpg", 20, 20)).bytes();

        for (int i = 0; i + 1 < stored.length; i++) {
            assertThat(stored[i] == (byte) 0xFF && stored[i + 1] == (byte) 0xE1).as("APP1 (EXIF) marker at " + i).isFalse();
        }
    }

    @Test
    void theTypeComesFromTheBytesNotTheName() {
        for (byte[] notAnImage : new byte[][] {
                "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8),
                "<html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8),
                image("gif", 10, 10),
                image("bmp", 10, 10),
                new byte[0]}) {
            assertThatThrownBy(() -> processor.process(notAnImage)).isInstanceOf(RequestValidationException.class);
        }
    }

    @Test
    void aTruncatedImageIsRejected() {
        byte[] png = image("png", 50, 50);
        byte[] truncated = java.util.Arrays.copyOf(png, png.length / 2);

        assertThatThrownBy(() -> processor.process(truncated)).isInstanceOf(RequestValidationException.class);
    }

    /** A tiny PNG whose header claims 100 000 x 100 000 pixels: refused before a single pixel is decoded. */
    @Test
    void aDecompressionBombIsRefusedFromItsHeader() {
        byte[] bomb = pngHeaderClaiming(100_000, 100_000);

        assertThatThrownBy(() -> processor.process(bomb)).isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getErrors().get(0).message()).contains("6000"));
    }

    private static byte[] pngHeaderClaiming(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdr.array());
        return ByteBuffer.allocate(8 + 4 + 4 + 13 + 4)
                .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})
                .putInt(13).put(type).put(ihdr.array()).putInt((int) crc.getValue())
                .array();
    }
}
