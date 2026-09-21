package com.techcomfort.landvaultbackend.inventory.internal.exceptions;

/**
 * Control-flow exceptions for the estate-creation endpoints, nested rather
 * than one file each — the same convention as {@code AuthException} and
 * {@code TenancyException}.
 */
public abstract class InventoryException extends RuntimeException {

    /** No estate with that id belongs to the caller's tenant. Deliberately not distinguished from "doesn't exist". */
    public static class EstateNotFound extends InventoryException {
    }

    /** A block, tier or plot referenced a row that isn't part of this estate. */
    public static class RelatedRecordNotFound extends InventoryException {

        private final String detail;

        public RelatedRecordNotFound(String detail) {
            this.detail = detail;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }

    /** The generated slug, a block name, or a tier size already exists on this estate/tenant. */
    public static class DuplicateRecord extends InventoryException {

        private final String detail;

        public DuplicateRecord(String detail) {
            this.detail = detail;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }

    /**
     * A boundary that isn't a usable polygon — wrong GeoJSON type, unclosed
     * ring, too few positions, or coordinates outside Nigeria (most often the
     * {@code [lat, lng]}/{@code [lng, lat]} swap).
     */
    public static class InvalidGeometry extends InventoryException {

        private final String field;
        private final String detail;

        public InvalidGeometry(String field, String detail) {
            this.field = field;
            this.detail = detail;
        }

        public String field() {
            return field;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }

    /** A plot boundary that doesn't sit inside its estate's boundary. */
    public static class PlotOutsideEstate extends InventoryException {

        private final String detail;

        public PlotOutsideEstate(String detail) {
            this.detail = detail;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }

    /**
     * A request that contradicts the tier/plot rules — a {@code LAND_SIZE}
     * tier with no size, a branch that isn't the caller's, a title recorded
     * twice. Surfaced as a clean 400 rather than letting a database
     * constraint produce the error.
     */
    public static class InvalidRequest extends InventoryException {

        private final String detail;

        public InvalidRequest(String detail) {
            this.detail = detail;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }
}
