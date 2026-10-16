package com.techcomfort.landvaultbackend.documents.internal.render;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.techcomfort.landvaultbackend.common.Currency;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptRendererTest {

    private static final ReceiptContent RECEIPT = new ReceiptContent("HT-RCT-2026-000123",
            Instant.parse("2026-10-16T18:40:00Z"), "Ada Obi-Nwosu", "Double King Estate Limited", "1758026",
            "Double King Deluxe Estate", "Jikwoyi, Abuja, Federal Capital Territory (Abuja)", "Block TD, Plot 66",
            new BigDecimal("250.00"), true, new BigDecimal("6000000.00"), Currency.NGN, "bank_transfer", "X890",
            "LV-PAY-ABC123", Instant.parse("2026-10-16T18:39:00Z"), "3F9A1C77B02E55D1",
            "https://hometrue.com.ng/verify/3F9A1C77B02E55D1");

    @Test
    void aReceiptIsOneA4PageCarryingEverythingItPromises() throws Exception {
        byte[] pdf = new ReceiptRenderer().pdf(RECEIPT);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isOne();
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("HomeTrue receipt HT-RCT-2026-000123");
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("PAYMENT RECEIPT", "HT-RCT-2026-000123", "Ada Obi-Nwosu", "₦6,000,000.00",
                    "Six Million Naira Only", "Block TD, Plot 66 (250 sqm, corner piece)",
                    "Double King Estate Limited (RC 1758026)", "Bank transfer, account ending X890 (via Paystack)",
                    "LV-PAY-ABC123", "16 October 2026, 7:40 pm (WAT)", "3F9A-1C77-B02E-55D1",
                    "https://hometrue.com.ng/verify/3F9A1C77B02E55D1");
            assertThat(text).as("no bank account to pay into, ever").doesNotContainPattern("\\b\\d{10}\\b");
        }
    }

    @Test
    void theJpegIsTheSamePageAsAnImage() throws Exception {
        byte[] jpeg = PdfImages.firstPageAsJpeg(new ReceiptRenderer().pdf(RECEIPT));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(image.getWidth()).isBetween(1239, 1241);
        assertThat(image.getHeight()).isBetween(1753, 1755);
    }

    @Test
    void theQrCodeScansToTheVerifyPage() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                PdfImages.firstPageAsJpeg(new ReceiptRenderer().pdf(RECEIPT))));

        String scanned = new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
        assertThat(scanned).isEqualTo("https://hometrue.com.ng/verify/3F9A1C77B02E55D1");
    }

    @Test
    void sameContentDrawsTheSameText() throws Exception {
        try (PDDocument a = Loader.loadPDF(new ReceiptRenderer().pdf(RECEIPT));
             PDDocument b = Loader.loadPDF(new ReceiptRenderer().pdf(RECEIPT))) {
            assertThat(new PDFTextStripper().getText(a)).isEqualTo(new PDFTextStripper().getText(b));
        }
    }
}
