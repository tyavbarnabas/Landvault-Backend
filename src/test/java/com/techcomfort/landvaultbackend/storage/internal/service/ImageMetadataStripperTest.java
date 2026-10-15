package com.techcomfort.landvaultbackend.storage.internal.service;

import com.techcomfort.landvaultbackend.storage.FileType;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pictures are generated here, and metadata planted in them the way cameras
 * and phones write it. Every planted value carries the word SECRET, so "no
 * metadata survived" is one search over the output.
 */
class ImageMetadataStripperTest {

    private static final String GPS = "SECRET-GPS 9.0765N 7.3986E";

    // ---------------------------------------------------------------- JPEG

    @Test
    void aJpegLosesItsMetadataKeepsItsOrientationAndDecodesToTheSamePixels() throws IOException {
        byte[] plain = jpeg(false);
        byte[] dirty = withJpegMetadata(plain, 6);

        byte[] clean = ImageMetadataStripper.strip(FileType.JPEG, dirty);

        assertThat(text(clean)).doesNotContain("SECRET");
        assertThat(jpegOrientation(clean)).isEqualTo(6);
        assertSamePixels(plain, clean);
        assertThat(clean.length).isLessThan(dirty.length);
    }

    @Test
    void anUprightJpegKeepsNoExifAtAll() throws IOException {
        byte[] clean = ImageMetadataStripper.strip(FileType.JPEG, withJpegMetadata(jpeg(false), 1));

        assertThat(text(clean)).doesNotContain("SECRET").doesNotContain("Exif");
        assertThat(jpegOrientation(clean)).isEqualTo(1);
    }

    @Test
    void aProgressiveJpegIsCleanedWithoutLosingAnyScan() throws IOException {
        byte[] plain = jpeg(true);

        byte[] clean = ImageMetadataStripper.strip(FileType.JPEG, withJpegMetadata(plain, 8));

        assertThat(text(clean)).doesNotContain("SECRET");
        assertThat(jpegOrientation(clean)).isEqualTo(8);
        assertSamePixels(plain, clean);
    }

    @Test
    void aBigEndianExifOrientationIsReadToo() throws IOException {
        byte[] dirty = insertAfterApp0(jpeg(false), app1(exifTiff(ByteOrder.BIG_ENDIAN, 3)));

        assertThat(jpegOrientation(ImageMetadataStripper.strip(FileType.JPEG, dirty))).isEqualTo(3);
    }

    @Test
    void aTruncatedJpegIsRefusedRatherThanPassedThrough() throws IOException {
        byte[] whole = withJpegMetadata(jpeg(false), 6);
        byte[] truncated = Arrays.copyOf(whole, whole.length / 2);

        assertThatThrownBy(() -> ImageMetadataStripper.strip(FileType.JPEG, truncated))
                .isInstanceOf(ImageMetadataStripper.Unreadable.class);
    }

    // ----------------------------------------------------------------- PNG

    @Test
    void aPngLosesItsTextAndExifChunksAndKeepsItsOrientation() throws IOException {
        byte[] plain = png();
        byte[] dirty = withPngMetadata(plain, 6);

        byte[] clean = ImageMetadataStripper.strip(FileType.PNG, dirty);

        assertThat(text(clean)).doesNotContain("SECRET").doesNotContain("tEXt").doesNotContain("tIME");
        assertThat(text(clean)).contains("eXIf");
        assertSamePixels(plain, clean);
    }

    @Test
    void anUprightPngKeepsNoExifChunk() throws IOException {
        byte[] clean = ImageMetadataStripper.strip(FileType.PNG, withPngMetadata(png(), 1));

        assertThat(text(clean)).doesNotContain("SECRET").doesNotContain("eXIf");
    }

    // ---------------------------------------------------------------- WebP

    @Test
    void aWebpLosesExifAndXmpKeepsOrientationAndTheImageDataUntouched() {
        byte[] imageData = "VP8L-image-bytes-not-decoded".getBytes(StandardCharsets.US_ASCII);
        byte[] dirty = webp(imageData, 3);

        byte[] clean = ImageMetadataStripper.strip(FileType.WEBP, dirty);

        assertThat(text(clean)).doesNotContain("SECRET");
        assertThat(text(clean)).contains(new String(imageData, StandardCharsets.US_ASCII));
        assertThat(u32le(clean, 4)).isEqualTo(clean.length - 8);
        int flags = clean[20] & 0xFF; // VP8X is the first chunk: RIFF(12) + header(8)
        assertThat(flags & 0x04).as("XMP flag").isZero();
        assertThat(flags & 0x08).as("EXIF flag").isNotZero();
        int exif = indexOf(clean, "EXIF");
        assertThat(ImageMetadataStripper.exifOrientation(clean, exif + 8, clean.length)).isEqualTo(3);
    }

    @Test
    void anUprightWebpLosesItsExifChunkAndFlag() {
        byte[] clean = ImageMetadataStripper.strip(FileType.WEBP, webp(new byte[]{1, 2, 3, 4}, 1));

        assertThat(text(clean)).doesNotContain("SECRET").doesNotContain("EXIF");
        assertThat(clean[20] & 0x0C).isZero();
    }

    @Test
    void aPdfIsReturnedUnchanged() {
        byte[] pdf = "%PDF-1.7 SECRET".getBytes(StandardCharsets.US_ASCII);

        assertThat(ImageMetadataStripper.strip(FileType.PDF, pdf)).isSameAs(pdf);
    }

    // ============================================================ builders

    private static BufferedImage picture() {
        BufferedImage image = new BufferedImage(64, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 120, 60));
        g.fillRect(0, 0, 64, 40);
        g.setColor(Color.ORANGE);
        g.fillOval(10, 5, 30, 30);
        g.dispose();
        return image;
    }

    private static byte[] jpeg(boolean progressive) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (progressive) {
                param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            writer.write(null, new IIOImage(picture(), null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(picture(), "png", bytes);
        return bytes.toByteArray();
    }

    /** EXIF (orientation + GPS text) and XMP in APP1, IPTC in APP13, a comment, and a trailer after EOI. */
    private static byte[] withJpegMetadata(byte[] jpeg, int orientation) {
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        extra.writeBytes(app1(exifTiff(ByteOrder.LITTLE_ENDIAN, orientation)));
        extra.writeBytes(segment(0xE1, concat("http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII),
                "<x:xmpmeta>SECRET-XMP</x:xmpmeta>".getBytes(StandardCharsets.US_ASCII))));
        extra.writeBytes(segment(0xED, "Photoshop 3.0\0SECRET-IPTC".getBytes(StandardCharsets.US_ASCII)));
        extra.writeBytes(segment(0xFE, "SECRET-COMMENT".getBytes(StandardCharsets.US_ASCII)));
        byte[] withSegments = insertAfterApp0(jpeg, extra.toByteArray());
        return concat(withSegments, "SECRET-TRAILER preview image with its own EXIF".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] app1(byte[] tiff) {
        return segment(0xE1, concat(new byte[]{'E', 'x', 'i', 'f', 0, 0}, tiff));
    }

    /** IFD0: orientation, then an ImageDescription holding the planted GPS text. */
    private static byte[] exifTiff(ByteOrder order, int orientation) {
        byte[] description = (GPS + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + description.length).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        tiff.putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(description.length).putInt(8 + 2 + 24 + 4);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        tiff.put(description);
        return tiff.array();
    }

    private static byte[] segment(int marker, byte[] data) {
        int length = data.length + 2;
        return concat(new byte[]{(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length}, data);
    }

    private static byte[] insertAfterApp0(byte[] jpeg, byte[] segments) {
        int app0Length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
        int at = 4 + app0Length;
        return concat(Arrays.copyOf(jpeg, at), segments,
                Arrays.copyOfRange(jpeg, at, jpeg.length));
    }

    private static byte[] withPngMetadata(byte[] png, int orientation) {
        int ihdrEnd = 8 + 12 + 13;
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        chunks.writeBytes(pngChunk("tEXt", "Comment\0SECRET-TEXT".getBytes(StandardCharsets.US_ASCII)));
        chunks.writeBytes(pngChunk("eXIf", exifTiff(ByteOrder.BIG_ENDIAN, orientation)));
        chunks.writeBytes(pngChunk("tIME", new byte[]{0x07, (byte) 0xEA, 10, 15, 9, 30, 0}));
        byte[] withChunks = concat(Arrays.copyOf(png, ihdrEnd), chunks.toByteArray(),
                Arrays.copyOfRange(png, ihdrEnd, png.length));
        return concat(withChunks, "SECRET-TRAILER".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return concat(ByteBuffer.allocate(4).putInt(data.length).array(), typeBytes, data,
                ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /** VP8X (announcing EXIF and XMP), the image data, an EXIF chunk with the "Exif" header some writers add, XMP. */
    private static byte[] webp(byte[] imageData, int orientation) {
        byte[] vp8x = new byte[10];
        vp8x[0] = 0x08 | 0x04;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(riffChunk("VP8X", vp8x));
        body.writeBytes(riffChunk("VP8L", imageData));
        body.writeBytes(riffChunk("EXIF", concat(new byte[]{'E', 'x', 'i', 'f', 0, 0},
                exifTiff(ByteOrder.LITTLE_ENDIAN, orientation))));
        body.writeBytes(riffChunk("XMP ", "<x:xmpmeta>SECRET-XMP</x:xmpmeta>".getBytes(StandardCharsets.US_ASCII)));
        byte[] payload = body.toByteArray();
        return concat("RIFF".getBytes(StandardCharsets.US_ASCII),
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array(), payload);
    }

    private static byte[] riffChunk(String fourcc, byte[] data) {
        byte[] padded = data.length % 2 == 0 ? data : concat(data, new byte[]{0});
        return concat(fourcc.getBytes(StandardCharsets.US_ASCII),
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array(), padded);
    }

    // ============================================================ checks

    /** The orientation in the first EXIF APP1 segment, or 1 when there is none. */
    private static int jpegOrientation(byte[] jpeg) {
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (marker == 0xDA) {
                break;
            }
            if (marker == 0xE1 && new String(jpeg, pos + 4, 4, StandardCharsets.US_ASCII).equals("Exif")) {
                return ImageMetadataStripper.exifOrientation(jpeg, pos + 10, pos + 2 + length);
            }
            pos += 2 + length;
        }
        return 1;
    }

    private static void assertSamePixels(byte[] expected, byte[] actual) throws IOException {
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(expected));
        BufferedImage b = ImageIO.read(new ByteArrayInputStream(actual));
        assertThat(b).as("cleaned file still decodes").isNotNull();
        assertThat(b.getWidth()).isEqualTo(a.getWidth());
        assertThat(b.getHeight()).isEqualTo(a.getHeight());
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                assertThat(b.getRGB(x, y)).as("pixel %d,%d", x, y).isEqualTo(a.getRGB(x, y));
            }
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] bytes, String needle) {
        return text(bytes).indexOf(needle);
    }

    private static long u32le(byte[] in, int pos) {
        return ByteBuffer.wrap(in, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
