package com.techcomfort.landvaultbackend.storage.internal.service;

import com.techcomfort.landvaultbackend.storage.FileType;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Removes metadata (EXIF: GPS position, camera and serial, dates; XMP; IPTC;
 * comments; trailing data) from JPEG, PNG and WebP without re-encoding the
 * picture, so no quality is lost. One thing survives: the EXIF orientation,
 * rewritten as a minimal EXIF block holding nothing else, because phones often
 * store a photo sideways and rely on it to display it upright. Colour profiles
 * are kept too (they change how colours look, and say nothing about anyone).
 * <p>
 * Anything this cannot parse is refused ({@link Unreadable}) rather than
 * passed through: a file it doesn't understand is a file it can't promise is
 * clean. See AGENTS.md, "Estate photos".
 */
final class ImageMetadataStripper {

    /** The value of a valid EXIF orientation is 1..8; 1 means "as stored", so nothing needs keeping. */
    private static final int ORIENTATION_NORMAL = 1;
    private static final int ORIENTATION_TAG = 0x0112;
    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};

    private ImageMetadataStripper() {
    }

    /** The file couldn't be parsed as the type it was checked to be. */
    static final class Unreadable extends RuntimeException {
        Unreadable(String message) {
            super(message);
        }
    }

    /** The cleaned bytes. PDFs aren't images and are returned unchanged. */
    static byte[] strip(FileType type, byte[] data) {
        return switch (type) {
            case JPEG -> stripJpeg(data);
            case PNG -> stripPng(data);
            case WEBP -> stripWebp(data);
            case PDF -> data;
        };
    }

    // ------------------------------------------------------------------
    // JPEG: a run of marker segments up to the image data, then the image
    // data itself (one or more scans), ending at EOI.
    // ------------------------------------------------------------------

    private static byte[] stripJpeg(byte[] in) {
        if (in.length < 4 || u8(in, 0) != 0xFF || u8(in, 1) != 0xD8) {
            throw new Unreadable("not a JPEG");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(0xFF);
        out.write(0xD8);
        int orientation = ORIENTATION_NORMAL;
        boolean orientationWritten = false;
        int pos = 2;
        while (true) {
            if (pos >= in.length) {
                throw new Unreadable("JPEG ends before its image data");
            }
            if (u8(in, pos) != 0xFF) {
                throw new Unreadable("JPEG segment expected");
            }
            while (pos < in.length && u8(in, pos) == 0xFF) {
                pos++; // fill bytes before a marker
            }
            if (pos >= in.length) {
                throw new Unreadable("JPEG ends inside a marker");
            }
            int marker = u8(in, pos++);
            if (marker == 0xD9) {
                throw new Unreadable("JPEG ends before its image data");
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue; // standalone markers carry no length
            }
            int length = u16be(in, pos);
            if (length < 2 || pos + length > in.length) {
                throw new Unreadable("JPEG segment runs past the end of the file");
            }
            int segmentStart = pos - 2;
            int segmentEnd = pos + length;

            if (marker == 0xE1) {
                // APP1: EXIF or XMP. Read the orientation out of EXIF, keep nothing.
                if (startsWith(in, pos + 2, EXIF_HEADER)) {
                    orientation = exifOrientation(in, pos + 2 + EXIF_HEADER.length, segmentEnd);
                }
            } else if (keepJpegSegment(marker, in, pos + 2, segmentEnd)) {
                // The minimal EXIF goes straight after JFIF (APP0), where readers expect it.
                if (!orientationWritten && marker != 0xE0) {
                    orientationWritten = writeJpegOrientation(out, orientation);
                }
                out.write(in, segmentStart, segmentEnd - segmentStart);
            }
            pos = segmentEnd;

            if (marker == 0xDA) {
                if (!orientationWritten) {
                    orientationWritten = writeJpegOrientation(out, orientation);
                }
                pos = copyScan(in, pos, out);
                if (pos < 0) {
                    return out.toByteArray(); // EOI written; anything after it is dropped
                }
            }
        }
    }

    /**
     * Copies entropy-coded image data until the next marker that isn't part of
     * it. Returns where that marker starts, or -1 once EOI has been copied:
     * whatever follows EOI (phones append preview images there, with their own
     * EXIF) is left behind.
     */
    private static int copyScan(byte[] in, int pos, ByteArrayOutputStream out) {
        int start = pos;
        while (pos < in.length) {
            if (u8(in, pos) != 0xFF) {
                pos++;
                continue;
            }
            if (pos + 1 >= in.length) {
                break;
            }
            int next = u8(in, pos + 1);
            if (next == 0x00 || (next >= 0xD0 && next <= 0xD7) || next == 0xFF) {
                pos += next == 0xFF ? 1 : 2; // stuffed byte, restart marker, or fill
                continue;
            }
            out.write(in, start, pos - start);
            if (next == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                return -1;
            }
            return pos; // another segment (a progressive JPEG's next tables and scan)
        }
        throw new Unreadable("JPEG image data has no end marker");
    }

    /**
     * Kept: the structure that decodes the picture, JFIF, the ICC colour
     * profile, and Adobe's APP14 (it says how a CMYK file's colours are
     * encoded). Dropped: every other APPn (EXIF/XMP in APP1, IPTC in APP13,
     * maker data elsewhere) and comments.
     */
    private static boolean keepJpegSegment(int marker, byte[] in, int dataStart, int dataEnd) {
        if (marker == 0xFE) {
            return false; // COM
        }
        if (marker == 0xE0) {
            return startsWith(in, dataStart, "JFIF\0".getBytes(StandardCharsets.US_ASCII), dataEnd);
        }
        if (marker == 0xE2) {
            return startsWith(in, dataStart, "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII), dataEnd);
        }
        if (marker == 0xEE) {
            return startsWith(in, dataStart, "Adobe".getBytes(StandardCharsets.US_ASCII), dataEnd);
        }
        return !(marker >= 0xE0 && marker <= 0xEF);
    }

    private static boolean writeJpegOrientation(ByteArrayOutputStream out, int orientation) {
        if (orientation == ORIENTATION_NORMAL) {
            return true;
        }
        byte[] tiff = minimalTiff(orientation);
        int length = 2 + EXIF_HEADER.length + tiff.length;
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(EXIF_HEADER);
        out.writeBytes(tiff);
        return true;
    }

    // ------------------------------------------------------------------
    // PNG: signature, then chunks of length, type, data, CRC, ending at IEND.
    // ------------------------------------------------------------------

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static byte[] stripPng(byte[] in) {
        if (!startsWith(in, 0, PNG_SIGNATURE)) {
            throw new Unreadable("not a PNG");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.writeBytes(PNG_SIGNATURE);
        int orientation = ORIENTATION_NORMAL;
        boolean orientationWritten = false;
        int pos = PNG_SIGNATURE.length;
        while (true) {
            if (pos + 8 > in.length) {
                throw new Unreadable("PNG ends before IEND");
            }
            long length = u32be(in, pos);
            String type = new String(in, pos + 4, 4, StandardCharsets.US_ASCII);
            long end = pos + 12 + length;
            if (length > Integer.MAX_VALUE || end > in.length) {
                throw new Unreadable("PNG chunk runs past the end of the file");
            }
            int dataStart = pos + 8;
            if (type.equals("eXIf")) {
                orientation = exifOrientation(in, dataStart, (int) (dataStart + length));
            } else if (!type.equals("tEXt") && !type.equals("zTXt") && !type.equals("iTXt")
                    && !type.equals("tIME")) {
                // eXIf must come before the image data.
                if (!orientationWritten && type.equals("IDAT")) {
                    writePngOrientation(out, orientation);
                    orientationWritten = true;
                }
                out.write(in, pos, (int) (end - pos));
            }
            pos = (int) end;
            if (type.equals("IEND")) {
                return out.toByteArray(); // anything after IEND is dropped
            }
        }
    }

    private static void writePngOrientation(ByteArrayOutputStream out, int orientation) {
        if (orientation == ORIENTATION_NORMAL) {
            return;
        }
        byte[] tiff = minimalTiff(orientation);
        byte[] type = "eXIf".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(tiff);
        out.writeBytes(ByteBuffer.allocate(4).putInt(tiff.length).array());
        out.writeBytes(type);
        out.writeBytes(tiff);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    // ------------------------------------------------------------------
    // WebP: a RIFF container of chunks. Metadata lives in EXIF and XMP
    // chunks, announced by flags in VP8X; a plain VP8/VP8L file has none.
    // ------------------------------------------------------------------

    private static final int VP8X_FLAG_EXIF = 0x08;
    private static final int VP8X_FLAG_XMP = 0x04;

    private static byte[] stripWebp(byte[] in) {
        if (in.length < 12 || !startsWith(in, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                || !startsWith(in, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            throw new Unreadable("not a WebP");
        }
        long riffEnd = 8 + u32le(in, 4);
        if (riffEnd > in.length) {
            throw new Unreadable("WebP is shorter than its header says");
        }
        // First pass: find the orientation, so VP8X's flags can be set right.
        int orientation = ORIENTATION_NORMAL;
        for (int pos = 12; pos < riffEnd; ) {
            int[] chunk = webpChunk(in, pos, riffEnd);
            if (fourcc(in, pos).equals("EXIF")) {
                int dataStart = pos + 8;
                int dataEnd = dataStart + chunk[0];
                // Some writers include the "Exif\0\0" header inside the chunk; the spec says not to.
                int tiffStart = startsWith(in, dataStart, EXIF_HEADER) ? dataStart + EXIF_HEADER.length : dataStart;
                orientation = exifOrientation(in, tiffStart, dataEnd);
            }
            pos = chunk[1];
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream(in.length);
        body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        boolean hasVp8x = false;
        for (int pos = 12; pos < riffEnd; ) {
            int[] chunk = webpChunk(in, pos, riffEnd);
            String fourcc = fourcc(in, pos);
            if (fourcc.equals("VP8X")) {
                hasVp8x = true;
                byte[] copy = Arrays.copyOfRange(in, pos, chunk[1]);
                int flags = copy[8] & 0xFF;
                flags &= ~VP8X_FLAG_XMP;
                flags = orientation == ORIENTATION_NORMAL ? flags & ~VP8X_FLAG_EXIF : flags | VP8X_FLAG_EXIF;
                copy[8] = (byte) flags;
                body.writeBytes(copy);
            } else if (!fourcc.equals("EXIF") && !fourcc.equals("XMP ")) {
                body.write(in, pos, chunk[1] - pos);
            }
            pos = chunk[1];
        }
        // EXIF belongs after the image data, at the end. Without VP8X there is
        // nowhere to announce it, so orientation can only survive in an extended file.
        if (hasVp8x && orientation != ORIENTATION_NORMAL) {
            byte[] tiff = minimalTiff(orientation);
            body.writeBytes("EXIF".getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(tiff.length).array());
            body.writeBytes(tiff);
            if (tiff.length % 2 == 1) {
                body.write(0);
            }
        }
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());
        out.writeBytes(payload); // anything after the RIFF's declared end is dropped
        return out.toByteArray();
    }

    /** {dataLength, nextChunkStart}. Chunk data is padded to an even length. */
    private static int[] webpChunk(byte[] in, int pos, long riffEnd) {
        if (pos + 8 > riffEnd) {
            throw new Unreadable("WebP chunk header runs past the end of the file");
        }
        long size = u32le(in, pos + 4);
        long next = pos + 8 + size + (size % 2);
        if (next > riffEnd && pos + 8 + size == riffEnd) {
            next = riffEnd; // an unpadded final chunk, tolerated
        }
        if (size > Integer.MAX_VALUE || next > riffEnd) {
            throw new Unreadable("WebP chunk runs past the end of the file");
        }
        return new int[]{(int) size, (int) next};
    }

    private static String fourcc(byte[] in, int pos) {
        return new String(in, pos, 4, StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------
    // EXIF (a TIFF structure): only IFD0's orientation is read, and only a
    // TIFF holding that one tag is ever written.
    // ------------------------------------------------------------------

    /** The orientation in a TIFF block, or 1 when it has none or it can't be read. Never throws. */
    static int exifOrientation(byte[] in, int tiffStart, int end) {
        try {
            if (tiffStart + 8 > end) {
                return ORIENTATION_NORMAL;
            }
            ByteOrder order;
            if (u8(in, tiffStart) == 'I' && u8(in, tiffStart + 1) == 'I') {
                order = ByteOrder.LITTLE_ENDIAN;
            } else if (u8(in, tiffStart) == 'M' && u8(in, tiffStart + 1) == 'M') {
                order = ByteOrder.BIG_ENDIAN;
            } else {
                return ORIENTATION_NORMAL;
            }
            ByteBuffer tiff = ByteBuffer.wrap(in, tiffStart, end - tiffStart).slice().order(order);
            long ifd0 = Integer.toUnsignedLong(tiff.getInt(4));
            if (ifd0 + 2 > tiff.limit()) {
                return ORIENTATION_NORMAL;
            }
            int entries = Short.toUnsignedInt(tiff.getShort((int) ifd0));
            for (int i = 0; i < entries; i++) {
                int entry = (int) ifd0 + 2 + i * 12;
                if (entry + 12 > tiff.limit()) {
                    break;
                }
                if (Short.toUnsignedInt(tiff.getShort(entry)) == ORIENTATION_TAG
                        && Short.toUnsignedInt(tiff.getShort(entry + 2)) == 3) { // SHORT
                    int value = Short.toUnsignedInt(tiff.getShort(entry + 8));
                    return value >= 1 && value <= 8 ? value : ORIENTATION_NORMAL;
                }
            }
            return ORIENTATION_NORMAL;
        } catch (IndexOutOfBoundsException e) {
            return ORIENTATION_NORMAL;
        }
    }

    /** A big-endian TIFF with one IFD holding one entry, the orientation. Nothing else. */
    static byte[] minimalTiff(int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8); // header, IFD0 at 8
        tiff.putShort((short) 1);                                             // one entry
        tiff.putShort((short) ORIENTATION_TAG).putShort((short) 3).putInt(1)  // SHORT x1
                .putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);                                                       // no next IFD
        return tiff.array();
    }

    // ------------------------------------------------------------------

    private static int u8(byte[] in, int pos) {
        return in[pos] & 0xFF;
    }

    private static int u16be(byte[] in, int pos) {
        if (pos + 2 > in.length) {
            throw new Unreadable("file ends inside a length field");
        }
        return (u8(in, pos) << 8) | u8(in, pos + 1);
    }

    private static long u32be(byte[] in, int pos) {
        return ((long) u8(in, pos) << 24) | (u8(in, pos + 1) << 16) | (u8(in, pos + 2) << 8) | u8(in, pos + 3);
    }

    private static long u32le(byte[] in, int pos) {
        if (pos + 4 > in.length) {
            throw new Unreadable("file ends inside a length field");
        }
        return ((long) u8(in, pos + 3) << 24) | (u8(in, pos + 2) << 16) | (u8(in, pos + 1) << 8) | u8(in, pos);
    }

    private static boolean startsWith(byte[] in, int pos, byte[] prefix) {
        return startsWith(in, pos, prefix, in.length);
    }

    private static boolean startsWith(byte[] in, int pos, byte[] prefix, int end) {
        if (pos < 0 || pos + prefix.length > Math.min(end, in.length)) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (in[pos + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
