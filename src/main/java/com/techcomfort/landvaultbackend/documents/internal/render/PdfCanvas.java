package com.techcomfort.landvaultbackend.documents.internal.render;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin drawing helpers over a PDF page: text, right-aligned text, wrapped
 * paragraphs, rules and boxes. Fonts are Noto Sans (SIL OFL, bundled under
 * {@code fonts/}) — the PDF standard fonts have no ₦ sign. Embedded as
 * subsets, so a receipt carries only the glyphs it uses.
 */
final class PdfCanvas {

    static final Color INK = new Color(0x1F, 0x29, 0x37);
    static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    static final Color BRAND = new Color(0x0F, 0x5C, 0x4A);
    static final Color RULE = new Color(0xD1, 0xD5, 0xDB);
    static final Color PANEL = new Color(0xF1, 0xF7, 0xF5);

    private static final byte[] REGULAR = load("fonts/NotoSans-Regular.ttf");
    private static final byte[] BOLD = load("fonts/NotoSans-Bold.ttf");

    final PDFont regular;
    final PDFont bold;
    private final PDPageContentStream content;

    PdfCanvas(PDDocument document, PDPageContentStream content) throws IOException {
        this.regular = PDType0Font.load(document, new ByteArrayInputStream(REGULAR));
        this.bold = PDType0Font.load(document, new ByteArrayInputStream(BOLD));
        this.content = content;
    }

    PDPageContentStream content() {
        return content;
    }

    void text(float x, float y, PDFont font, float size, Color color, String text) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.setNonStrokingColor(color);
        content.newLineAtOffset(x, y);
        content.showText(clean(text));
        content.endText();
    }

    void rightText(float right, float y, PDFont font, float size, Color color, String text) throws IOException {
        text(right - width(font, size, text), y, font, size, color, text);
    }

    /** Draws {@code text} wrapped to {@code maxWidth}; returns the baseline below the last line. */
    float paragraph(float x, float y, float maxWidth, PDFont font, float size, float leading, Color color,
                    String text) throws IOException {
        for (String line : wrap(font, size, maxWidth, text)) {
            text(x, y, font, size, color, line);
            y -= leading;
        }
        return y;
    }

    void rule(float x1, float x2, float y) throws IOException {
        content.setStrokingColor(RULE);
        content.setLineWidth(0.75f);
        content.moveTo(x1, y);
        content.lineTo(x2, y);
        content.stroke();
    }

    void box(float x, float y, float width, float height, Color fill) throws IOException {
        content.setNonStrokingColor(fill);
        content.addRect(x, y, width, height);
        content.fill();
    }

    static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(clean(text)) / 1000f * size;
    }

    static List<String> wrap(PDFont font, float size, float maxWidth, String text) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : clean(text).split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(font, size, candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Control characters (a pasted newline or tab in a name) can't be shown; spaces stand in. */
    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").strip();
    }

    private static byte[] load(String path) {
        try (InputStream in = PdfCanvas.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Font missing from the classpath: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
