package com.techcomfort.landvaultbackend.storage;

/**
 * Storage refusals. Public so a calling module's controllers return them in the
 * standard error shape (storage's own handler covers every controller).
 * Never carries a storage credential or a signed link.
 */
public abstract class StorageException extends RuntimeException {

    protected StorageException(String message) {
        super(message);
    }

    public static class FileTypeNotAllowed extends StorageException {
        public FileTypeNotAllowed(String allowed) {
            super("That type of file isn't accepted here. Upload " + allowed + ".");
        }
    }

    public static class FileTooLarge extends StorageException {
        public FileTooLarge(long maxBytes) {
            super("That file is too large. The limit is " + megabytes(maxBytes) + ".");
        }

        private static String megabytes(long bytes) {
            return (bytes % (1024 * 1024) == 0 ? String.valueOf(bytes / (1024 * 1024))
                    : String.format("%.1f", bytes / (1024.0 * 1024))) + " MB";
        }
    }

    /** No such file, or another person's — deliberately the same answer. */
    public static class FileNotFound extends StorageException {
        public FileNotFound() {
            super("File not found.");
        }
    }

    public static class UploadNotReceived extends StorageException {
        public UploadNotReceived() {
            super("The file hasn't arrived in storage yet. Upload it, then confirm again.");
        }
    }

    public static class UploadExpired extends StorageException {
        public UploadExpired() {
            super("This upload has expired. Start the upload again.");
        }
    }

    /** What arrived wasn't what was promised; it has been deleted. */
    public static class UploadRejected extends StorageException {
        private final String code;

        public UploadRejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class FileNotReady extends StorageException {
        public FileNotReady() {
            super("This file isn't available.");
        }
    }

    /** Storage couldn't be reached, or failed on its side. Safe to try again. */
    public static class StorageUnavailable extends StorageException {
        public StorageUnavailable() {
            super("File storage isn't responding right now. Please try again in a moment.");
        }
    }
}
