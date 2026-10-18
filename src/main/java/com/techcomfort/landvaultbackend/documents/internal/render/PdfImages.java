package com.techcomfort.landvaultbackend.documents.internal.render;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** A document's first page as a JPEG — the same page as the PDF, for sharing where a PDF is awkward (WhatsApp). */
public final class PdfImages {

    /** 150 dpi: A4 becomes about 1240 × 1754 pixels — sharp on a phone, a few hundred KB. */
    private static final float DPI = 150;
    private static final float QUALITY = 0.9f;
    /** Grey space between stacked pages, so the page breaks stay visible. */
    private static final int GAP = 12;

    private PdfImages() {
    }

    public static byte[] firstPageAsJpeg(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return jpeg(new PDFRenderer(document).renderImageWithDPI(0, DPI, ImageType.RGB));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the document as an image", e);
        }
    }

    /** Every page, one below the other, as one tall JPEG — a letter that runs onto a second page stays whole. */
    public static byte[] allPagesAsJpeg(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<BufferedImage> pages = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                pages.add(renderer.renderImageWithDPI(i, DPI, ImageType.RGB));
            }
            int width = pages.stream().mapToInt(BufferedImage::getWidth).max().orElse(1);
            int height = pages.stream().mapToInt(BufferedImage::getHeight).sum() + GAP * (pages.size() - 1);
            BufferedImage sheet = new BufferedImage(width, Math.max(height, 1), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(Color.LIGHT_GRAY);
            g.fillRect(0, 0, width, height);
            int y = 0;
            for (BufferedImage page : pages) {
                g.drawImage(page, 0, y, null);
                y += page.getHeight() + GAP;
            }
            g.dispose();
            return jpeg(sheet);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the document as an image", e);
        }
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
