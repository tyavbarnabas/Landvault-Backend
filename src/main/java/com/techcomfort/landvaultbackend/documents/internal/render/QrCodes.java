package com.techcomfort.landvaultbackend.documents.internal.render;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.awt.Color;
import java.io.IOException;
import java.util.Map;

/** Draws a QR code straight into a PDF as vector squares — sharp at any zoom, and in the JPEG. */
final class QrCodes {

    private QrCodes() {
    }

    /** {@code x, y} is the bottom-left corner; {@code size} the side in points (quiet zone included). */
    static void draw(PDPageContentStream content, String text, float x, float y, float size) throws IOException {
        BitMatrix matrix;
        try {
            // Quartile correction survives a crease or a smudge on a printed copy.
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q, EncodeHintType.MARGIN, 2));
        } catch (WriterException e) {
            throw new IllegalStateException("Could not encode the verification link as a QR code", e);
        }
        int modules = matrix.getWidth();
        float cell = size / modules;
        content.setNonStrokingColor(Color.WHITE);
        content.addRect(x, y, size, size);
        content.fill();
        content.setNonStrokingColor(Color.BLACK);
        for (int row = 0; row < modules; row++) {
            for (int col = 0; col < modules; col++) {
                if (matrix.get(col, row)) {
                    // PDF's y axis points up; the matrix's rows go down.
                    content.addRect(x + col * cell, y + size - (row + 1) * cell, cell, cell);
                }
            }
        }
        content.fill();
    }
}
