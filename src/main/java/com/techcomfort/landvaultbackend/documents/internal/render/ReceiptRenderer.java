package com.techcomfort.landvaultbackend.documents.internal.render;

import com.techcomfort.landvaultbackend.documents.internal.service.VerificationCodes;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

import static com.techcomfort.landvaultbackend.documents.internal.render.PdfCanvas.BRAND;
import static com.techcomfort.landvaultbackend.documents.internal.render.PdfCanvas.INK;
import static com.techcomfort.landvaultbackend.documents.internal.render.PdfCanvas.MUTED;
import static com.techcomfort.landvaultbackend.documents.internal.render.PdfCanvas.PANEL;

/**
 * Draws a HomeTrue payment receipt as a one-page A4 PDF from its stored copy.
 * The same content always draws the same page; nothing here reads the
 * database or the clock.
 */
@Component
public class ReceiptRenderer {

    private static final float MARGIN = 48;
    private static final float LABEL_WIDTH = 150;

    public byte[] pdf(ReceiptContent receipt) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            describe(document, receipt);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                draw(new PdfCanvas(document, content), page.getMediaBox(), receipt);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not draw receipt " + receipt.number(), e);
        }
    }

    private static void draw(PdfCanvas c, PDRectangle box, ReceiptContent r) throws IOException {
        float left = MARGIN;
        float right = box.getWidth() - MARGIN;
        float width = right - left;
        float y = box.getHeight() - MARGIN - 14;

        // Header: who issued it, what it is, its number.
        c.text(left, y, c.bold, 22, BRAND, "HomeTrue");
        c.rightText(right, y, c.bold, 14, INK, "PAYMENT RECEIPT");
        y -= 16;
        c.text(left, y, c.regular, 9, MUTED, "hometrue.com.ng");
        c.rightText(right, y, c.regular, 10, INK, "No. " + r.number());
        y -= 14;
        c.rightText(right, y, c.regular, 9, MUTED, "Issued " + DocumentFormats.dateTime(r.issuedAt()));
        y -= 14;
        c.rule(left, right, y);
        y -= 28;

        // Who paid.
        c.text(left, y, c.regular, 9, MUTED, "RECEIVED FROM");
        y -= 18;
        c.text(left, y, c.bold, 14, INK, r.buyerName());
        y -= 26;

        // How much, in figures and in words.
        List<String> words = PdfCanvas.wrap(c.regular, 10, width - 32,
                AmountInWords.of(r.amount(), r.currency()));
        float panelHeight = 58 + words.size() * 14;
        c.box(left, y - panelHeight + 12, width, panelHeight, PANEL);
        y -= 6;
        c.text(left + 16, y, c.regular, 9, MUTED, "AMOUNT RECEIVED");
        y -= 28;
        c.text(left + 16, y, c.bold, 24, BRAND, DocumentFormats.money(r.amount(), r.currency()));
        y -= 18;
        for (String line : words) {
            c.text(left + 16, y, c.regular, 10, INK, line);
            y -= 14;
        }
        y -= 24;

        // What it was for.
        c.text(left, y, c.regular, 9, MUTED, "BEING");
        y -= 16;
        y = c.paragraph(left, y, width, c.regular, 11, 15, INK, purpose(r));
        y -= 14;

        // The details, as a two-column list.
        for (String[] row : rows(r)) {
            c.text(left, y, c.regular, 10, MUTED, row[0]);
            float next = c.paragraph(left + LABEL_WIDTH, y, width - LABEL_WIDTH, c.regular, 10, 14, INK, row[1]);
            y = next - 6;
        }
        y -= 6;
        c.rule(left, right, y);
        y -= 18;
        y = c.paragraph(left, y, width, c.regular, 9, 13, MUTED, """
                HomeTrue received this payment through Paystack and holds it until the developer's finance team \
                verifies the sale. Your plot is allocated once they do; you will be told by email.""");

        // Verification, anchored to the bottom of the page.
        float qrSize = 104;
        float qrY = MARGIN + 52;
        QrCodes.draw(c.content(), r.verifyUrl(), left, qrY, qrSize);
        float textX = left + qrSize + 18;
        float textY = qrY + qrSize - 18;
        c.text(textX, textY, c.bold, 11, INK, "Check this receipt is genuine");
        textY -= 16;
        textY = c.paragraph(textX, textY, right - textX, c.regular, 9, 13, MUTED,
                "Scan the code, or open " + r.verifyUrl());
        textY -= 4;
        c.text(textX, textY, c.regular, 9, MUTED, "Verification code");
        c.text(textX + 82, textY, c.bold, 9, INK,
                VerificationCodes.display(r.verificationCode()));

        float footY = MARGIN + 24;
        c.rule(left, right, footY + 14);
        c.paragraph(left, footY, width, c.regular, 8, 11, MUTED, """
                Issued electronically by HomeTrue; valid without a signature. Pay only through HomeTrue — we never \
                ask you to pay into a bank account printed on a letter, receipt or flyer.""");
    }

    private static String purpose(ReceiptContent r) {
        StringBuilder plot = new StringBuilder(r.plotLabel());
        List<String> about = new ArrayList<>();
        if (r.plotSizeSqm() != null) {
            about.add(DocumentFormats.size(r.plotSizeSqm()));
        }
        if (r.corner()) {
            about.add("corner piece");
        }
        if (!about.isEmpty()) {
            plot.append(" (").append(String.join(", ", about)).append(')');
        }
        return "Full payment for " + plot + " at " + r.estateName()
                + (r.estateLocation() == null ? "" : ", " + r.estateLocation())
                + ", sold by " + seller(r) + ".";
    }

    private static String seller(ReceiptContent r) {
        return r.sellerRcNumber() == null || r.sellerRcNumber().isBlank() ? r.sellerName()
                : r.sellerName() + " (RC " + r.sellerRcNumber() + ")";
    }

    private static List<String[]> rows(ReceiptContent r) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Receipt number", r.number()});
        rows.add(new String[]{"Paid on", DocumentFormats.dateTime(r.paidAt())});
        rows.add(new String[]{"Payment method", DocumentFormats.paymentMethod(r.channel(), r.last4())});
        rows.add(new String[]{"Payment reference", r.paymentReference()});
        rows.add(new String[]{"Received by", "HomeTrue, for the developer"});
        rows.add(new String[]{"Developer", seller(r)});
        rows.add(new String[]{"Estate", r.estateName()
                + (r.estateLocation() == null ? "" : ", " + r.estateLocation())});
        rows.add(new String[]{"Plot", r.plotLabel()});
        return rows;
    }

    private static void describe(PDDocument document, ReceiptContent r) {
        PDDocumentInformation info = document.getDocumentInformation();
        info.setTitle("HomeTrue receipt " + r.number());
        info.setAuthor("HomeTrue");
        info.setCreator("HomeTrue");
        Calendar issued = new GregorianCalendar(TimeZone.getTimeZone(DocumentFormats.NIGERIA));
        issued.setTimeInMillis(r.issuedAt().toEpochMilli());
        info.setCreationDate(issued);
    }
}
