package com.techcomfort.landvaultbackend.documents.internal.render;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationLetterRendererTest {

    private final AllocationLetterRenderer renderer = new AllocationLetterRenderer();

    @Test
    void aSampleCarriesEveryClauseInOrderTheWatermarkAndAnUnscannableQrPlaceholder() throws Exception {
        byte[] pdf = renderer.pdf(sample(List.of(
                "That the land is used for the construction of a 4 Bedroom Terrace Duplex.",
                "That an infrastructure fee of ₦3,500,000.00 is payable on allocation.",
                "That all payments are made only through HomeTrue.")));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("DOUBLE KING ESTATE LIMITED", "RC 1234567",
                    "LETTER OF ALLOCATION FOR DOUBLE KING DELUXE", "[BUYER'S FULL NAME]",
                    "1. That the land is used", "2. That an infrastructure fee of ₦3,500,000.00",
                    "3. That all payments are made only through HomeTrue.", "Our site office is open Monday to Saturday",
                    "Congratulations!", "With reference to your application dated", "OMAH UCHENNA", "Managing Director",
                    "SAMPLE —", "Page 1 of 1", "Pay only through HomeTrue")
                    .contains("Check this letter is genuine", "QR code", "issued letter", "[verification code]");
            assertThat(text.indexOf("1. That")).isLessThan(text.indexOf("2. That"));
        }
        save("allocation-letter-sample.pdf", pdf);
        // Nothing on a sample can be scanned: it must never pass for a genuine letter.
        BufferedImage page = ImageIO.read(new ByteArrayInputStream(PdfImages.firstPageAsJpeg(pdf)));
        assertThatThrownBy(() -> new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(page))))).isInstanceOf(NotFoundException.class);
        save("allocation-letter-sample.jpeg", PdfImages.firstPageAsJpeg(pdf));
    }

    @Test
    void anIssuedLetterCarriesAScannableQrCodeAndItsVerificationCode() throws Exception {
        AllocationLetterContent sample = sample(List.of("That the land is used for housing only."));
        AllocationLetterContent issued = new AllocationLetterContent(sample.company(), sample.logo(), sample.signature(),
                sample.seal(), sample.date(), List.of("Ada Obi", "12 Airport Road, Abuja"), "Dear Ada Obi,",
                sample.estateName(), sample.estateLocation(), sample.opening(), sample.clauses(), null,
                sample.signOff(), sample.signatoryName(), sample.signatoryTitle(), List.of(), "HT-ALC-2026-000001",
                "https://hometrue.com.ng/verify/3F9A1C77B02E55D1", "3F9A1C77B02E55D1");
        byte[] pdf = renderer.pdf(issued);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("Check this letter is genuine",
                    "3F9A-1C77-B02E-55D1", "Ref: HT-ALC-2026-000001").doesNotContain("QR code", "SAMPLE");
        }
        BufferedImage page = ImageIO.read(new ByteArrayInputStream(PdfImages.firstPageAsJpeg(pdf)));
        assertThat(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(page)))).getText())
                .isEqualTo("https://hometrue.com.ng/verify/3F9A1C77B02E55D1");
        save("allocation-letter-issued.jpeg", PdfImages.firstPageAsJpeg(pdf));
    }

    @Test
    void longConditionsRunOntoFurtherPagesAndTheImageKeepsEveryPage() throws IOException {
        List<String> clauses = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            clauses.add("That condition number " + (i + 1) + " is observed by the allottee at all times, as the "
                    + "estate's management directs, and that any breach is remedied within thirty days of notice.");
        }
        byte[] pdf = renderer.pdf(sample(clauses));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            assertThat(pages).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("30. That condition number 30", "Page " + pages + " of " + pages, "(continued)");
        }
        save("allocation-letter-long.pdf", pdf);
        byte[] jpeg = PdfImages.allPagesAsJpeg(pdf);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(image.getHeight()).as("every page, stacked").isGreaterThan(image.getWidth() * 2);
        save("allocation-letter-long.jpeg", jpeg);
    }

    @Test
    void aCharacterTheFontLacksDoesNotBreakTheLetter() throws IOException {
        byte[] pdf = renderer.pdf(sample(List.of("That residents keep the estate green 🌳.")));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("keep the estate green ?");
        }
    }

    private static AllocationLetterContent sample(List<String> clauses) throws IOException {
        return new AllocationLetterContent(
                new AllocationLetterContent.Company("Double King Estate Limited", "1234567",
                        "Suite B4 HCR Plaza, Plot 521 Sylvester Ugo Crescent, Jabi, Abuja", "+2348031234567",
                        "info@doubleking.example", "doubleking.example"),
                png(220, 120, Color.decode("#8B6B3E")),
                signature(),
                png(160, 160, Color.decode("#B23A48")),
                Instant.parse("2026-10-17T10:00:00Z"),
                List.of("[Buyer's full name]", "[Buyer's address]", "[Buyer's phone number]"),
                "Dear [Buyer's name],",
                "Double King Deluxe",
                "Jikwoyi, Federal Capital Territory (Abuja)",
                "With reference to your application dated [date of application] for a plot of land at Double King "
                        + "Deluxe, we are glad to convey to you the allocation of Plot [number] of about [size] sqm.",
                clauses,
                "Our site office is open Monday to Saturday for any questions about your plot.",
                "Congratulations!",
                "Omah Uchenna",
                "Managing Director",
                List.of("SAMPLE — NOT VALID", "Preview only. Not an allocation."),
                null, null, null);
    }

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillOval(4, 4, width - 8, height - 8);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] signature() throws IOException {
        BufferedImage image = new BufferedImage(300, 90, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x1E, 0x3A, 0x8A));
        g.setStroke(new BasicStroke(4));
        g.drawPolyline(new int[]{10, 60, 90, 140, 170, 230, 290}, new int[]{70, 20, 75, 25, 60, 30, 55}, 7);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** To look at by hand: {@code -Dletters.out=/some/dir}. Nothing is written otherwise. */
    private static void save(String name, byte[] bytes) throws IOException {
        String dir = System.getProperty("letters.out");
        if (dir != null) {
            Files.write(Path.of(dir, name), bytes);
        }
    }
}
