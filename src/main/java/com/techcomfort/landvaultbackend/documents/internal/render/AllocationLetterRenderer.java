package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.documents.internal.service.VerificationCodes;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static com.techcomfort.landvaultbackend.documents.internal.render.PdfCanvas.INK;
import static com.techcomfort.landvaultbackend.documents.internal.render.PdfCanvas.MUTED;

/**
 * Draws an allocation letter on A4 from its content, running onto further pages
 * when the conditions are long. The developer's letterhead heads the first
 * page; every page carries HomeTrue's footer (pay only through HomeTrue), its
 * page number and, on a sample, the watermark. Nothing here reads the database
 * or the clock.
 */
@Component
public class AllocationLetterRenderer {

    private static final float MARGIN = 56;
    /** Kept clear at the bottom of every page for the footer. */
    private static final float FOOTER_SPACE = 22;
    private static final float LOGO_WIDTH = 110;
    private static final float LOGO_HEIGHT = 64;
    private static final float NUMBER_INDENT = 22;
    private static final float BODY = 10.5f;
    private static final float LEADING = 14.5f;
    private static final Color WATERMARK = new Color(0xB9, 0x1C, 0x1C);

    public byte[] pdf(AllocationLetterContent letter) {
        try (PDDocument document = new PDDocument()) {
            describe(document, letter);
            Layout layout = new Layout(document, letter);
            layout.draw();
            layout.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not draw the allocation letter", e);
        }
    }

    /** One letter's drawing state: the page in hand and how far down it the text has reached. */
    private static final class Layout {

        private final PDDocument document;
        private final AllocationLetterContent l;
        private final PdfCanvas c;
        private final List<PDPage> pages = new ArrayList<>();
        private PDPageContentStream stream;
        private float y;
        private final float left;
        private final float right;
        private final float width;

        Layout(PDDocument document, AllocationLetterContent letter) throws IOException {
            this.document = document;
            this.l = letter;
            this.c = new PdfCanvas(document);
            this.left = MARGIN;
            this.right = PDRectangle.A4.getWidth() - MARGIN;
            this.width = right - left;
        }

        void draw() throws IOException {
            newPage(false);
            letterhead();
            addressee();
            body();
            signOff();
        }

        private void letterhead() throws IOException {
            AllocationLetterContent.Company co = l.company();
            float top = y;
            float textWidth = l.logo() == null ? width : width - LOGO_WIDTH - 16;
            if (l.logo() != null) {
                image(l.logo(), "logo", right - LOGO_WIDTH, top - LOGO_HEIGHT + 12, LOGO_WIDTH, LOGO_HEIGHT, true);
            }
            y = c.paragraph(left, y, textWidth, c.bold, 15, 18, INK, upper(co.name()));
            if (present(co.rcNumber())) {
                c.text(left, y, c.regular, 9, MUTED, DocumentFormats.rcNumber(co.rcNumber()));
                y -= 12;
            }
            if (present(co.address())) {
                y = c.paragraph(left, y, textWidth, c.regular, 9, 12, MUTED, co.address());
            }
            List<String> contact = new ArrayList<>();
            for (String part : new String[]{co.phone(), co.email(), co.website()}) {
                if (present(part)) {
                    contact.add(part);
                }
            }
            if (!contact.isEmpty()) {
                y = c.paragraph(left, y, textWidth, c.regular, 9, 12, MUTED, String.join("  ·  ", contact));
            }
            y = Math.min(y, top - LOGO_HEIGHT) - 6;
            c.rule(left, right, y);
            y -= 26;
        }

        private void addressee() throws IOException {
            float top = y;
            c.rightText(right, top, c.regular, BODY, INK, DocumentFormats.date(l.date()));
            if (present(l.number())) {
                c.rightText(right, top - LEADING, c.regular, 9, MUTED, "Ref: " + l.number());
            }
            if (present(l.stamp())) {
                c.rightText(right, top - 2 * LEADING, c.bold, 9, INK, l.stamp());
            }
            boolean first = true;
            for (String line : l.buyerLines()) {
                y = c.paragraph(left, y, width * 0.6f, first ? c.bold : c.regular, first ? BODY : 10, LEADING, INK,
                        first ? upper(line) : line);
                first = false;
            }
            y -= 16;
            c.text(left, y, c.regular, BODY, INK, l.salutation());
            y -= 26;
        }

        private void body() throws IOException {
            String title = upper(l.title() + " " + l.estateName()
                    + (present(l.estateLocation()) ? ", " + l.estateLocation() : ""));
            for (String line : PdfCanvas.wrap(c.bold, 11.5f, width, title)) {
                float lineWidth = PdfCanvas.width(c.bold, 11.5f, line);
                float x = left + (width - lineWidth) / 2;
                c.text(x, y, c.bold, 11.5f, INK, line);
                c.content().setStrokingColor(INK);
                c.content().setLineWidth(0.6f);
                c.content().moveTo(x, y - 2.5f);
                c.content().lineTo(x + lineWidth, y - 2.5f);
                c.content().stroke();
                y -= 16;
            }
            y -= 8;
            y = c.paragraph(left, y, width, c.regular, BODY, LEADING, INK, l.opening());
            y -= 8;

            int number = 1;
            for (String clause : l.clauses()) {
                List<String> lines = PdfCanvas.wrap(c.regular, BODY, width - NUMBER_INDENT, clause);
                need(lines.size() * LEADING + 4);
                c.text(left, y, c.regular, BODY, INK, number++ + ".");
                for (String line : lines) {
                    c.text(left + NUMBER_INDENT, y, c.regular, BODY, INK, line);
                    y -= LEADING;
                }
                y -= 4;
            }
        }

        private void signOff() throws IOException {
            if (present(l.closing())) {
                List<String> lines = PdfCanvas.wrap(c.regular, BODY, width, l.closing());
                need(lines.size() * LEADING + 10);
                y -= 6;
                y = c.paragraph(left, y, width, c.regular, BODY, LEADING, INK, l.closing());
            }
            need(170);
            y -= 10;
            c.text(left, y, c.bold, BODY, INK, l.signOff());
            y -= 24;
            c.text(left, y, c.regular, BODY, INK, "Yours faithfully,");
            y -= 8;

            float signatureTop = y;
            if (l.signature() != null) {
                image(l.signature(), "signature", left, signatureTop - 50, 160, 50, false);
            } else {
                c.text(left, signatureTop - 34, c.regular, 9, MUTED, "[Signature]");
            }
            c.rule(left, left + 170, signatureTop - 54);
            if (l.seal() != null) {
                image(l.seal(), "seal", left + 172, signatureTop - 84, 84, 84, false);
            }
            y = signatureTop - 70;
            c.text(left, y, c.bold, BODY, present(l.signatoryName()) ? INK : MUTED,
                    present(l.signatoryName()) ? upper(l.signatoryName()) : "[Signatory's name]");
            y -= LEADING;
            c.text(left, y, c.regular, 10, present(l.signatoryTitle()) ? INK : MUTED,
                    present(l.signatoryTitle()) ? l.signatoryTitle() : "[Signatory's title]");
            y -= LEADING;
            c.text(left, y, c.regular, 9, MUTED, "For and on behalf of " + l.company().name());

            float size = 76;
            float qrX = right - size;
            float qrY = signatureTop - size - 4;
            c.rightText(qrX - 8, qrY + size - 14, c.bold, 9, INK, "Check this letter is genuine");
            c.rightText(qrX - 8, qrY + size - 27, c.regular, 8, MUTED, "Scan the code, or enter");
            if (l.sample()) {
                // Where the issued letter's QR code goes, so the developer approves the real layout.
                // Nothing here can be scanned: a sample must never pass for a genuine letter.
                c.box(qrX, qrY, size, size, PdfCanvas.PANEL);
                c.content().setStrokingColor(PdfCanvas.RULE);
                c.content().setLineWidth(0.75f);
                c.content().setLineDashPattern(new float[]{3, 2}, 0);
                c.content().addRect(qrX, qrY, size, size);
                c.content().stroke();
                c.content().setLineDashPattern(new float[]{}, 0);
                centred(qrX, size, qrY + size / 2 + 6, c.bold, 8, "QR code");
                centred(qrX, size, qrY + size / 2 - 5, c.regular, 6.5f, "added to the");
                centred(qrX, size, qrY + size / 2 - 14, c.regular, 6.5f, "issued letter");
                c.rightText(qrX - 8, qrY + size - 39, c.regular, 8, MUTED, "[verification code]");
            } else {
                QrCodes.draw(c.content(), l.verifyUrl(), qrX, qrY, size);
                c.rightText(qrX - 8, qrY + size - 39, c.bold, 8, INK,
                        VerificationCodes.display(l.verificationCode()));
            }
        }

        private void centred(float x, float boxWidth, float baseline, PDFont font,
                             float size, String text) throws IOException {
            c.text(x + (boxWidth - PdfCanvas.width(font, size, text)) / 2, baseline, font, size, MUTED, text);
        }

        /** Every page's footer and number, and a sample's watermark — added once the page count is known. */
        void finish() throws IOException {
            stream.close();
            int total = pages.size();
            for (int i = 0; i < total; i++) {
                PDPage page = pages.get(i);
                try (PDPageContentStream content = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    c.use(content);
                    float footY = MARGIN - 18;
                    c.rule(left, right, footY + 14);
                    c.paragraph(left, footY, width - 70, c.regular, 7.5f, 10, MUTED, """
                            Issued through HomeTrue (hometrue.com.ng). Pay only through HomeTrue — never into a bank \
                            account named on a letter, message or flyer.""");
                    c.rightText(right, footY, c.regular, 7.5f, MUTED, "Page " + (i + 1) + " of " + total);
                    watermark(content, page);
                }
            }
        }

        private void watermark(PDPageContentStream content, PDPage page) throws IOException {
            if (l.watermark().isEmpty()) {
                return;
            }
            PDExtendedGraphicsState faint = new PDExtendedGraphicsState();
            faint.setNonStrokingAlphaConstant(0.14f);
            content.saveGraphicsState();
            content.setGraphicsStateParameters(faint);
            double angle = Math.toRadians(35);
            float cx = page.getMediaBox().getWidth() / 2;
            float cy = page.getMediaBox().getHeight() / 2;
            float offset = (l.watermark().size() - 1) * 26f;
            for (int i = 0; i < l.watermark().size(); i++) {
                String line = l.watermark().get(i);
                float size = i == 0 ? 44 : 20;
                float w = PdfCanvas.width(c.bold, size, line);
                // The line's start, rotated about the page centre, so each line is centred on the diagonal.
                float dx = -w / 2;
                float x = (float) (cx + dx * Math.cos(angle) - offset * Math.sin(angle));
                float yy = (float) (cy + dx * Math.sin(angle) + offset * Math.cos(angle));
                content.beginText();
                content.setFont(c.bold, size);
                content.setNonStrokingColor(WATERMARK);
                content.setTextMatrix(Matrix.getRotateInstance(angle, x, yy));
                content.showText(line);
                content.endText();
                offset -= 52;
            }
            content.restoreGraphicsState();
        }

        private void need(float height) throws IOException {
            if (y - height < MARGIN + FOOTER_SPACE) {
                newPage(true);
            }
        }

        private void newPage(boolean continued) throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            pages.add(page);
            stream = new PDPageContentStream(document, page);
            c.use(stream);
            y = page.getMediaBox().getHeight() - MARGIN;
            if (continued) {
                c.text(left, y, c.regular, 8.5f, MUTED, l.company().name() + " — "
                        + l.kind().toLowerCase(Locale.ROOT) + ", " + l.estateName() + " (continued)");
                y -= 24;
            }
        }

        /** Fits the image inside the box, keeping its shape; right-aligned in the box when asked. */
        private void image(byte[] bytes, String name, float x, float y, float boxWidth, float boxHeight,
                           boolean alignRight) throws IOException {
            PDImageXObject image = PDImageXObject.createFromByteArray(document, bytes, name);
            float scale = Math.min(boxWidth / image.getWidth(), boxHeight / image.getHeight());
            float w = image.getWidth() * scale;
            float h = image.getHeight() * scale;
            stream.drawImage(image, alignRight ? x + boxWidth - w : x, y + (boxHeight - h), w, h);
        }
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.UK);
    }

    private static void describe(PDDocument document, AllocationLetterContent l) {
        PDDocumentInformation info = document.getDocumentInformation();
        info.setTitle((l.sample() ? "Sample " + l.kind().toLowerCase(Locale.ROOT) : l.kind()) + ", " + l.estateName());
        info.setAuthor(l.company().name());
        info.setCreator("HomeTrue");
        Calendar date = new GregorianCalendar(TimeZone.getTimeZone(DocumentFormats.NIGERIA));
        date.setTimeInMillis(l.date().toEpochMilli());
        info.setCreationDate(date);
    }
}
