package com.techcomfort.landvaultbackend.testsupport;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Real, decodable photos for tests, with metadata planted the way phones write it. */
public final class TestImages {

    /** Every planted value contains this, so "nothing survived" is one search. */
    public static final String PLANTED = "SECRET-GPS 9.0765N 7.3986E";

    private TestImages() {
    }

    /** A JPEG with an EXIF block (the orientation, and {@link #PLANTED} as its description) and a trailer after the image. */
    public static byte[] jpegWithMetadata(int orientation) {
        byte[] jpeg = jpeg();
        int app0End = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        byte[] exif = concat(new byte[]{'E', 'x', 'i', 'f', 0, 0}, tiff(orientation));
        int length = exif.length + 2;
        byte[] app1 = concat(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length}, exif);
        return concat(Arrays.copyOf(jpeg, app0End), app1, Arrays.copyOfRange(jpeg, app0End, jpeg.length),
                (PLANTED + " trailer").getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] jpeg() {
        BufferedImage image = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(40, 110, 70));
        g.fillRect(0, 0, 48, 32);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpeg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] tiff(int orientation) {
        byte[] description = (PLANTED + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 24 + 4 + description.length).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(description.length).putInt(8 + 2 + 24 + 4);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        tiff.put(description);
        return tiff.array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
