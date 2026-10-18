package com.techcomfort.landvaultbackend.documents.internal.render;

import java.time.Instant;
import java.util.List;

/**
 * Everything an allocation letter prints, already decided: the renderer reads
 * nothing else. A sample (DS-5) carries placeholders and a watermark and no
 * verification code; an issued letter carries its number and code.
 *
 * @param company       the developer as the letterhead prints it
 * @param logo          PNG or JPEG bytes; null for none
 * @param signature     null when the signatory has none yet (a sample shows a blank line)
 * @param seal          null for none — optional since CAMA 2020
 * @param buyerLines    the addressee block: name first, then address and phone
 * @param salutation    "Dear Ada Obi,"
 * @param opening       the template's opening paragraph, fields already filled in
 * @param clauses       standard, then the estate's, then HomeTrue's — numbered together
 * @param closing       the template's closing paragraph, filled in; null for none
 * @param signOff       the line above "Yours faithfully"
 * @param watermark     lines stamped across every page; empty for an issued letter
 * @param number        the letter's number; null on a sample
 * @param verifyUrl     where its QR code points; null on a sample, and then no QR is drawn
 */
public record AllocationLetterContent(
        Company company,
        byte[] logo,
        byte[] signature,
        byte[] seal,
        Instant date,
        List<String> buyerLines,
        String salutation,
        String estateName,
        String estateLocation,
        String opening,
        List<String> clauses,
        String closing,
        String signOff,
        String signatoryName,
        String signatoryTitle,
        List<String> watermark,
        String number,
        String verifyUrl,
        String verificationCode
) {

    public record Company(String name, String rcNumber, String address, String phone, String email, String website) {
    }

    public boolean sample() {
        return verifyUrl == null;
    }
}
