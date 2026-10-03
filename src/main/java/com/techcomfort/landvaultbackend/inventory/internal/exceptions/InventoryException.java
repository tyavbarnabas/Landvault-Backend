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

    /**
     * {@code POST .../boundary} on an estate that already has one. Adding a
     * boundary is for an estate created without one; changing an existing
     * boundary is a different, riskier operation and is not offered.
     */
    public static class BoundaryAlreadySet extends InventoryException {

        @Override
        public String getMessage() {
            return "This estate already has a boundary. Changing an existing boundary isn't supported.";
        }
    }

    /**
     * An edit to a plot that is reserved or sold. Its boundary, tier and
     * size are what a buyer agreed to; only available plots are editable.
     */
    public static class PlotNotEditable extends InventoryException {

        private final String detail;

        public PlotNotEditable(String plotLabel, String status) {
            this.detail = plotLabel + " is " + status + ". Only available plots can be edited — "
                    + "a buyer's agreed boundary, price and size never change under them.";
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }

    /**
     * A plot import refused because the file has errors. Carries the whole
     * report, which is what the caller needs to fix the file — one answer
     * for every problem, not the first one found.
     */
    public static class ImportRejected extends InventoryException {

        private final com.techcomfort.landvaultbackend.inventory.dto.PlotImportReportDto report;

        public ImportRejected(com.techcomfort.landvaultbackend.inventory.dto.PlotImportReportDto report) {
            this.report = report;
        }

        public com.techcomfort.landvaultbackend.inventory.dto.PlotImportReportDto report() {
            return report;
        }

        @Override
        public String getMessage() {
            return "The file has " + report.errors().size() + " error(s); nothing was imported.";
        }
    }

    /**
     * Withdrawing a plot that has been reserved, bought or disputed (IE-11).
     * Its records refer to it; withholding is the way to take it off sale.
     */
    public static class PlotHasHistory extends InventoryException {

        private final String detail;

        public PlotHasHistory(String plotLabel) {
            this.detail = plotLabel + " has been reserved, bought or part of a boundary conflict, so it can't be "
                    + "withdrawn — its records refer to it. Withhold it instead to take it off the market.";
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
     * Publication refused because the estate doesn't meet the marketplace
     * conditions (PB-2). {@code code} names the first failing condition;
     * the message names every one that failed, so the developer can act on
     * all of them at once (PB-3). Never names a conflict's counterparty.
     */
    public static class PublicationRefused extends InventoryException {

        private final String code;
        private final String detail;

        public PublicationRefused(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        public String code() {
            return code;
        }

        @Override
        public String getMessage() {
            return detail;
        }
    }

    /**
     * An edit to a field that cannot change — a tier's type or currency. A
     * different value there is a different tier, not an edit to this one.
     * Refused explicitly rather than ignored: a silently dropped field reads
     * as success.
     */
    public static class ImmutableField extends InventoryException {

        private final String code;
        private final String detail;

        public ImmutableField(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        public String code() {
            return code;
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
