package com.techcomfort.landvaultbackend.documents.internal.exceptions;

import java.util.Map;

public abstract class DocumentException extends RuntimeException {

    protected DocumentException(String message) {
        super(message);
    }

    /** No such document, or it isn't the caller's — never told apart. */
    public static class DocumentNotFound extends DocumentException {
        public DocumentNotFound() {
            super("Document not found.");
        }
    }

    /** No HomeTrue document has this code. Says nothing more, whatever the reason. */
    public static class NotAHomeTrueDocument extends DocumentException {
        public NotAHomeTrueDocument() {
            super("This is not a HomeTrue document.");
        }
    }

    /** No portfolio entry of the caller's has this id. */
    public static class PlotNotFound extends DocumentException {
        public PlotNotFound() {
            super("Plot not found in your portfolio.");
        }
    }

    /** A 404 with its own code: no such branch, letterhead image or file — or not the caller's. */
    public static class NotFound extends DocumentException {
        private final String code;

        public NotFound(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The request can't be honoured as sent (400). */
    public static class RequestRejected extends DocumentException {
        private final String code;

        public RequestRejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The caller has no company: only a developer's staff set up letterheads. */
    public static class CompanyScopeRequired extends DocumentException {
        public CompanyScopeRequired() {
            super("Only a developer's staff can set up letterheads.");
        }
    }

    /** Letterheads, signatures and seals are set company-wide, never from inside one branch. */
    public static class CompanyWideOnly extends DocumentException {
        public CompanyWideOnly() {
            super("Letterheads, signatures and seals are set by company-wide staff. Switch to the whole company to change them.");
        }
    }

    /** One or more clauses break the clause rules; keyed {@code clauses[i]}. */
    public static class ClausesRefused extends DocumentException {
        private final Map<String, String> problems;

        public ClausesRefused(Map<String, String> problems) {
            super("Some clauses can't be used as written.");
            this.problems = problems;
        }

        public Map<String, String> problems() {
            return problems;
        }
    }

    /** A letter template breaks the template rules; keyed by field. */
    public static class TemplateRefused extends DocumentException {
        private final Map<String, String> problems;

        public TemplateRefused(Map<String, String> problems) {
            super("Some of the letter template can't be used as written.");
            this.problems = problems;
        }

        public Map<String, String> problems() {
            return problems;
        }
    }

    /** The request clashes with the current state (409): already decided, or replaced since it was read. */
    public static class Conflict extends DocumentException {
        private final String code;

        public Conflict(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
