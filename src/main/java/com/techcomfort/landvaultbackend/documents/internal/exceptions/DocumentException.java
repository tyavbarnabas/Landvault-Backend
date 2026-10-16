package com.techcomfort.landvaultbackend.documents.internal.exceptions;

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
}
