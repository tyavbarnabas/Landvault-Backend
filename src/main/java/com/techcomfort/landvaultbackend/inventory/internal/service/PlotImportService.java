package com.techcomfort.landvaultbackend.inventory.internal.service;

import com.techcomfort.landvaultbackend.audit.AuditApi;
import com.techcomfort.landvaultbackend.audit.AuditEntryRequest;
import com.techcomfort.landvaultbackend.common.TenantContext;
import com.techcomfort.landvaultbackend.common.TenantScope;
import com.techcomfort.landvaultbackend.common.geojson.GeoJsonPolygonDto;
import com.techcomfort.landvaultbackend.conflicts.ConflictDetectionApi;
import com.techcomfort.landvaultbackend.inventory.dto.BlockDto;
import com.techcomfort.landvaultbackend.inventory.dto.CreateBlockRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotRequest;
import com.techcomfort.landvaultbackend.inventory.dto.CreatePlotsRequest;
import com.techcomfort.landvaultbackend.inventory.dto.PlotImportIssueDto;
import com.techcomfort.landvaultbackend.inventory.dto.PlotImportReportDto;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Block;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Estate;
import com.techcomfort.landvaultbackend.inventory.internal.domain.Plot;
import com.techcomfort.landvaultbackend.inventory.internal.domain.PriceTier;
import com.techcomfort.landvaultbackend.inventory.internal.enums.TierType;
import com.techcomfort.landvaultbackend.inventory.internal.exceptions.InventoryException;
import com.techcomfort.landvaultbackend.inventory.internal.repository.BlockRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.EstateRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PlotRepository;
import com.techcomfort.landvaultbackend.inventory.internal.repository.PriceTierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

/**
 * Plot import from a surveyor's GeoJSON file (FU-1..FU-3). See AGENTS.md.
 * <p>
 * <strong>GeoJSON only.</strong> A surveyor's CAD or shapefile is converted
 * once in QGIS; parsing those formats here would be a large, edge-case-heavy
 * job for no gain. The trap that conversion hides is the coordinate system —
 * Nigerian surveys are usually in UTM metres on the Minna datum — so this
 * says so plainly when it sees one, rather than reporting "out of bounds".
 * <p>
 * The preview validates everything and writes nothing. The import validates
 * again (never trusting the preview — the estate may have changed in
 * between) and then creates every plot through
 * {@link PortalEstateService#createPlots}, the same path as adding plots one
 * request at a time, so every existing rule — tier sizes, containment, overlap
 * detection — applies without being restated here. All or nothing: half of a
 * 450-plot estate imported is worse than none.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlotImportService {

    /** The same ceiling as {@code CreatePlotsRequest}: the import goes through that path. */
    public static final int MAX_FEATURES = 500;

    private static final Set<String> IMPORTABLE_STATUSES = Set.of("available-dev", "available-inv");
    private static final String NOTE = "This checks the file against the estate as it is right now. It is not a "
            + "guarantee: the import checks everything again, and overlaps are recorded for review after it.";

    private final JsonMapper jsonMapper;
    private final EstateRepository estateRepository;
    private final PriceTierRepository priceTierRepository;
    private final BlockRepository blockRepository;
    private final PlotRepository plotRepository;
    private final GeoJsonPolygonParser geoJsonParser;
    private final GeometryCalculator geometry;
    private final PortalEstateService portalEstateService;
    private final ConflictDetectionApi conflictDetection;
    private final AuditApi auditApi;

    @Value("${landvault.conflicts.min-overlap-sqm:1.0}")
    private BigDecimal minOverlapSqm;

    /**
     * Which property in each feature carries what, and the status every
     * imported plot gets. {@code tierMapping} is an optional JSON object from
     * a file's own values to tier ids — {@code {"A": "<tier id>"}} — for files
     * whose tier column doesn't use the estate's labels or sizes.
     */
    public record Options(String plotNumberProperty, String blockProperty, String tierProperty,
                          String cornerProperty, String status, String tierMapping) {
    }

    // --- FU-2: preview ---

    @Transactional(readOnly = true)
    public PlotImportReportDto preview(UUID estateId, byte[] file, Options options) {
        Estate estate = requireOwnedEstate(estateId);
        return analyse(estate, file, options).report(false, 0, null);
    }

    // --- FU-1: import ---

    @Transactional
    public PlotImportReportDto importPlots(UUID estateId, byte[] file, String fileName, Options options) {
        TenantScope scope = currentScope();
        Estate estate = requireOwnedEstate(estateId);
        EstateWriteAccess.requireWritable(estate);
        Analysis analysis = analyse(estate, file, options);
        if (!analysis.errors.isEmpty()) {
            throw new InventoryException.ImportRejected(analysis.report(false, 0, null));
        }

        Map<String, UUID> blockIds = new HashMap<>(analysis.existingBlockIds);
        for (String name : analysis.blocksToCreate) {
            BlockDto created = portalEstateService.createBlock(estateId, new CreateBlockRequest(name, null));
            blockIds.put(key(name), created.id());
        }

        List<CreatePlotRequest> requests = analysis.rows.stream()
                .map(row -> new CreatePlotRequest(row.plotNumber,
                        row.blockName == null ? null : blockIds.get(key(row.blockName)),
                        row.tier.getId(), row.corner, options.status(),
                        null, null, null, null, null, row.footprintDto))
                .toList();
        portalEstateService.createPlots(estateId, new CreatePlotsRequest(requests));
        int overlaps = conflictDetection.detectForEstatePlots(estateId);

        auditApi.record(AuditEntryRequest.of(
                scope.userId(), "estate.plots_imported", "estate", estateId, estate.getTenantId(),
                requests.size() + " plot(s) imported from file '" + safe(fileName) + "'"
                        + (analysis.blocksToCreate.isEmpty() ? "" : "; blocks created: " + analysis.blocksToCreate)
                        + "; " + overlaps + " overlapping plot pair(s) in the estate afterwards."));
        log.info("Imported {} plot(s) into estate {} by {}", requests.size(), estateId, scope.userId());

        return analysis.report(true, requests.size(), overlaps);
    }

    // --- FU-3: template ---

    /**
     * A working file to edit, built for this estate: its real tier labels, and
     * two example plots inside its real boundary. Beats documentation about
     * GeoJSON structure — a developer opens it, sees the shape, and replaces
     * the rows.
     */
    /** The template's download name and contents, from one read. */
    public record TemplateFile(String fileName, String content) {
    }

    // One transactional call for both, deliberately: the tenant scope reaches
    // the database only when a transaction begins (TenantScopedDataSource), so
    // a separate non-transactional lookup for the file name ran with no scope,
    // RLS returned no estate, and the download 404'd. Caught by the IT.
    @Transactional(readOnly = true)
    public TemplateFile template(UUID estateId) {
        Estate estate = requireOwnedEstate(estateId);
        List<PriceTier> tiers = priceTierRepository.findByEstateIdOrderBySizeSqmAsc(estateId).stream()
                .filter(t -> t.getRetiredAt() == null)
                .toList();
        String tierLabel = tiers.isEmpty() || tiers.getFirst().getLabel() == null
                ? "Standard 250" : tiers.getFirst().getLabel();

        double lng;
        double lat;
        if (estate.getFootprint() != null) {
            Point inside = estate.getFootprint().getInteriorPoint();
            lng = inside.getX();
            lat = inside.getY();
        } else {
            lng = 7.400;
            lat = 9.100;
        }
        double side = 0.0002;

        ObjectNode root = jsonMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.put("name", estate.getName() + " plots");
        ArrayNode instructions = root.putArray("instructions");
        instructions.add("One Feature per plot. Geometry must be a Polygon (a single-part MultiPolygon is also accepted).");
        instructions.add("Coordinates are [longitude, latitude] in WGS 84 (EPSG:4326) - NOT UTM metres. "
                + "In QGIS: Export > Save Features As > GeoJSON, CRS EPSG:4326.");
        instructions.add("properties.plot_number is required, and unique within its block.");
        instructions.add("properties.block is optional; a block that doesn't exist yet is created.");
        instructions.add("properties.tier must match one of this estate's tier labels, or a land tier's size in sqm. "
                + "Tiers here: " + tiers.stream().map(t -> t.getLabel() == null ? "(unlabelled)" : t.getLabel()).toList());
        instructions.add("properties.corner is optional: true for a corner plot.");
        instructions.add(estate.getFootprint() == null
                ? "This estate has no boundary yet, so these example coordinates are placeholders. Every plot "
                + "must still sit inside the estate boundary once one is added."
                : "The two example plots sit inside this estate's boundary. Replace them with your own.");

        ArrayNode features = root.putArray("features");
        features.add(feature("1", "A", tierLabel, false, lng, lat, side));
        features.add(feature("2", "A", tierLabel, true, lng + side, lat, side));
        return new TemplateFile(estate.getSlug() + "-plots-template.geojson",
                jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    // --- analysis ---

    private Analysis analyse(Estate estate, byte[] file, Options options) {
        if (!IMPORTABLE_STATUSES.contains(options.status())) {
            throw new InventoryException.InvalidRequest(
                    "Imported plots can only be 'available-dev' or 'available-inv'.");
        }
        Analysis analysis = new Analysis();
        List<PriceTier> tiers = priceTierRepository.findByEstateIdOrderBySizeSqmAsc(estate.getId());
        Map<String, PriceTier> mapped = tierMapping(options.tierMapping(), tiers);
        for (Block block : blockRepository.findByEstateIdOrderByNameAsc(estate.getId())) {
            analysis.existingBlockIds.put(key(block.getName()), block.getId());
        }
        Map<UUID, String> blockNames = new HashMap<>();
        for (Block block : blockRepository.findByEstateIdOrderByNameAsc(estate.getId())) {
            blockNames.put(block.getId(), block.getName());
        }
        Set<String> takenPlotKeys = new HashSet<>();
        List<Plot> existing = plotRepository.findByEstateId(estate.getId());
        for (Plot plot : existing) {
            String blockName = plot.getBlockId() == null ? null : blockNames.get(plot.getBlockId());
            takenPlotKeys.add(plotKey(blockName == null ? null : key(blockName), plot.getPlotNumber()));
        }

        JsonNode root;
        try {
            root = jsonMapper.readTree(file);
        } catch (RuntimeException e) {
            analysis.fileError("NOT_GEOJSON", "The file isn't valid JSON. Export it from QGIS as GeoJSON.");
            return analysis;
        }
        if (root == null || !"FeatureCollection".equals(root.path("type").asString(""))
                || !root.path("features").isArray()) {
            analysis.fileError("NOT_A_FEATURE_COLLECTION",
                    "The file must be a GeoJSON FeatureCollection with a \"features\" array — one Feature per plot.");
            return analysis;
        }
        String crs = root.path("crs").path("properties").path("name").asString("");
        if (!crs.isBlank() && !crs.contains("4326") && !crs.contains("CRS84")) {
            analysis.fileError("PROJECTED_COORDINATES", "The file declares coordinate system '" + crs
                    + "'. Coordinates must be longitude/latitude in WGS 84 (EPSG:4326). Nigerian survey files are "
                    + "often in UTM metres (Minna datum, zones 31N-33N): in QGIS, export again with CRS EPSG:4326.");
            return analysis;
        }
        JsonNode features = root.path("features");
        analysis.featureCount = features.size();
        if (features.isEmpty()) {
            analysis.fileError("NO_FEATURES", "The file has no features.");
            return analysis;
        }
        if (features.size() > MAX_FEATURES) {
            analysis.fileError("TOO_MANY_FEATURES", "The file has " + features.size() + " features; the limit is "
                    + MAX_FEATURES + " per upload. Split it into several files.");
            return analysis;
        }

        Map<String, Integer> seenInFile = new HashMap<>();
        for (int i = 0; i < features.size(); i++) {
            int position = i + 1;
            JsonNode properties = features.get(i).path("properties");
            String plotNumber = text(properties.path(options.plotNumberProperty()));
            int errorsBefore = analysis.errors.size();

            if (plotNumber == null) {
                analysis.error(position, null, "MISSING_PLOT_NUMBER",
                        "No '" + options.plotNumberProperty() + "' property.");
            }
            String blockName = text(properties.path(options.blockProperty()));
            if (blockName != null && !analysis.existingBlockIds.containsKey(key(blockName))
                    && analysis.blocksToCreate.stream().noneMatch(b -> key(b).equals(key(blockName)))) {
                analysis.blocksToCreate.add(blockName);
            }
            if (plotNumber != null) {
                String plotKey = plotKey(blockName == null ? null : key(blockName), plotNumber);
                Integer earlier = seenInFile.putIfAbsent(plotKey, position);
                if (earlier != null) {
                    analysis.error(position, plotNumber, "DUPLICATE_PLOT_NUMBER",
                            label(blockName, plotNumber) + " also appears at feature " + earlier + ".");
                } else if (takenPlotKeys.contains(plotKey)) {
                    analysis.error(position, plotNumber, "PLOT_NUMBER_EXISTS",
                            label(blockName, plotNumber) + " already exists on this estate.");
                }
            }

            PriceTier tier = resolveTier(analysis, tiers, mapped, text(properties.path(options.tierProperty())),
                    options.tierProperty(), position, plotNumber);

            GeoJsonPolygonDto footprintDto = polygonDto(analysis, features.get(i).path("geometry"), position, plotNumber);
            Polygon footprint = null;
            if (footprintDto != null) {
                try {
                    footprint = geoJsonParser.parse(footprintDto, "geometry");
                } catch (InventoryException.InvalidGeometry e) {
                    analysis.error(position, plotNumber, "INVALID_GEOMETRY", e.getMessage());
                }
            }
            if (footprint != null && estate.getFootprint() != null && !footprint.within(estate.getFootprint())) {
                analysis.error(position, plotNumber, "OUTSIDE_ESTATE",
                        "This plot's boundary falls outside the estate's boundary.");
            }

            if (analysis.errors.size() == errorsBefore) {
                analysis.rows.add(new Row(position, plotNumber, blockName, tier,
                        isTrue(properties.path(options.cornerProperty())), footprintDto, footprint));
                analysis.plotsPerTier.merge(tier.getLabel() == null ? tier.getId().toString() : tier.getLabel(), 1, Integer::sum);
            }
        }

        if (estate.getFootprint() == null) {
            analysis.warning(null, null, "ESTATE_HAS_NO_BOUNDARY", "This estate has no boundary yet, so the "
                    + "plots can't be checked against it. They will be when one is added.");
        }
        findOverlaps(analysis, existing, blockNames);
        return analysis;
    }

    /**
     * Overlaps among the file's plots and against plots already on the
     * estate — warnings, because plot overlaps are same-company and are
     * recorded for review after import rather than blocking it. Candidates
     * come from an envelope index, and the area is measured by the database
     * (geography, square metres) only for pairs that genuinely intersect, so
     * neighbours sharing an edge are never flagged.
     */
    private void findOverlaps(Analysis analysis, List<Plot> existing, Map<UUID, String> blockNames) {
        STRtree index = new STRtree();
        record Shape(String label, String plotNumber, Polygon polygon, int position) {
        }
        List<Shape> shapes = new ArrayList<>();
        for (Row row : analysis.rows) {
            if (row.footprint != null) {
                shapes.add(new Shape(label(row.blockName, row.plotNumber), row.plotNumber, row.footprint, row.position));
            }
        }
        for (Plot plot : existing) {
            if (plot.getFootprint() != null) {
                String block = plot.getBlockId() == null ? null : blockNames.get(plot.getBlockId());
                shapes.add(new Shape("existing " + label(block, plot.getPlotNumber()), plot.getPlotNumber(),
                        plot.getFootprint(), 0));
            }
        }
        for (Shape shape : shapes) {
            index.insert(shape.polygon().getEnvelopeInternal(), shape);
        }
        for (int i = 0; i < shapes.size(); i++) {
            Shape a = shapes.get(i);
            if (a.position() == 0) {
                continue;
            }
            Envelope envelope = a.polygon().getEnvelopeInternal();
            for (Object candidate : index.query(envelope)) {
                Shape b = (Shape) candidate;
                int j = shapes.indexOf(b);
                if (j == i || (b.position() != 0 && j < i) || !a.polygon().intersects(b.polygon())) {
                    continue;
                }
                Geometry overlap = a.polygon().intersection(b.polygon());
                BigDecimal area = geometry.areaOf(overlap);
                if (area != null && area.compareTo(minOverlapSqm) > 0) {
                    analysis.warning(a.position(), a.plotNumber(), "PLOT_OVERLAP", a.label() + " overlaps " + b.label()
                            + " by about " + area.setScale(0, RoundingMode.HALF_UP).toPlainString() + " sqm.");
                }
            }
        }
    }

    /**
     * Parses the optional mapping. A bad mapping is refused outright (400)
     * rather than reported per feature: it's one mistake in the request, not
     * one per plot. Keys match the file's values ignoring case and spaces.
     */
    private Map<String, PriceTier> tierMapping(String json, List<PriceTier> tiers) {
        Map<String, PriceTier> mapped = new HashMap<>();
        if (json == null || json.isBlank()) {
            return mapped;
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(json);
        } catch (RuntimeException e) {
            throw new InventoryException.InvalidRequest(
                    "tierMapping must be a JSON object from file values to tier ids, e.g. {\"A\": \"<tier id>\"}.");
        }
        if (!root.isObject()) {
            throw new InventoryException.InvalidRequest(
                    "tierMapping must be a JSON object from file values to tier ids, e.g. {\"A\": \"<tier id>\"}.");
        }
        Map<String, PriceTier> byId = new HashMap<>();
        tiers.forEach(t -> byId.put(t.getId().toString(), t));
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            PriceTier tier = byId.get(entry.getValue().asString("").trim().toLowerCase(Locale.ROOT));
            if (tier == null) {
                throw new InventoryException.InvalidRequest("tierMapping: '" + entry.getKey()
                        + "' points at a tier that isn't on this estate.");
            }
            mapped.put(key(entry.getKey()), tier);
        }
        return mapped;
    }

    private PriceTier resolveTier(Analysis analysis, List<PriceTier> tiers, Map<String, PriceTier> mapped,
                                  String value, String property, int position, String plotNumber) {
        if (value == null) {
            analysis.error(position, plotNumber, "MISSING_TIER", "No '" + property + "' property.");
            return null;
        }
        PriceTier explicit = mapped.get(key(value));
        if (explicit != null) {
            if (explicit.getRetiredAt() != null) {
                analysis.error(position, plotNumber, "TIER_RETIRED",
                        "'" + value + "' maps to a retired tier, which accepts no new plots.");
                return null;
            }
            return explicit;
        }
        List<PriceTier> matches = tiers.stream()
                .filter(t -> t.getLabel() != null && t.getLabel().trim().equalsIgnoreCase(value))
                .toList();
        if (matches.isEmpty()) {
            BigDecimal size = decimal(value);
            if (size != null) {
                matches = tiers.stream()
                        .filter(t -> t.getTierType() == TierType.LAND_SIZE && t.getSizeSqm() != null
                                && t.getSizeSqm().compareTo(size) == 0)
                        .toList();
            }
        }
        if (matches.size() == 1) {
            if (matches.getFirst().getRetiredAt() != null) {
                analysis.error(position, plotNumber, "TIER_RETIRED",
                        "'" + value + "' is a retired tier and accepts no new plots.");
                return null;
            }
            return matches.getFirst();
        }
        analysis.error(position, plotNumber, matches.isEmpty() ? "UNKNOWN_TIER" : "AMBIGUOUS_TIER",
                matches.isEmpty()
                        ? "'" + value + "' doesn't match a tier on this estate. Tiers: "
                        + tiers.stream().map(t -> t.getLabel() == null ? "(unlabelled)" : t.getLabel()).toList()
                        + ". Create the tier first, or correct the file."
                        : "'" + value + "' matches more than one tier. Use the tier's label.");
        return null;
    }

    /** A Polygon, or a MultiPolygon with exactly one part — what QGIS often exports for a single shape. */
    private GeoJsonPolygonDto polygonDto(Analysis analysis, JsonNode geometryNode, int position, String plotNumber) {
        if (geometryNode.isMissingNode() || geometryNode.isNull()) {
            analysis.error(position, plotNumber, "MISSING_GEOMETRY", "This feature has no geometry.");
            return null;
        }
        String type = geometryNode.path("type").asString("");
        try {
            if ("Polygon".equals(type)) {
                return jsonMapper.treeToValue(geometryNode, GeoJsonPolygonDto.class);
            }
            if ("MultiPolygon".equals(type)) {
                JsonNode parts = geometryNode.path("coordinates");
                if (parts.size() != 1) {
                    analysis.error(position, plotNumber, "INVALID_GEOMETRY", "This plot is a MultiPolygon with "
                            + parts.size() + " separate parts. A plot must be one shape.");
                    return null;
                }
                ObjectNode single = jsonMapper.createObjectNode();
                single.put("type", "Polygon");
                single.set("coordinates", parts.get(0));
                return jsonMapper.treeToValue(single, GeoJsonPolygonDto.class);
            }
        } catch (RuntimeException e) {
            analysis.error(position, plotNumber, "INVALID_GEOMETRY", "The coordinates can't be read as positions.");
            return null;
        }
        analysis.error(position, plotNumber, "INVALID_GEOMETRY",
                "The geometry is a '" + type + "'. A plot must be a Polygon.");
        return null;
    }

    private ObjectNode feature(String plotNumber, String block, String tier, boolean corner,
                               double lng, double lat, double side) {
        ObjectNode feature = jsonMapper.createObjectNode();
        feature.put("type", "Feature");
        ObjectNode properties = feature.putObject("properties");
        properties.put("plot_number", plotNumber);
        properties.put("block", block);
        properties.put("tier", tier);
        properties.put("corner", corner);
        ObjectNode geometryNode = feature.putObject("geometry");
        geometryNode.put("type", "Polygon");
        ArrayNode ring = geometryNode.putArray("coordinates").addArray();
        double[][] corners = {{lng, lat}, {lng + side, lat}, {lng + side, lat + side}, {lng, lat + side}, {lng, lat}};
        for (double[] c : corners) {
            ring.addArray().add(round(c[0])).add(round(c[1]));
        }
        return feature;
    }

    // --- small helpers ---

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.isNumber() ? node.decimalValue().stripTrailingZeros().toPlainString() : node.asString("");
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean isTrue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String value = text(node);
        return value != null && Set.of("true", "yes", "y", "1").contains(value.toLowerCase(Locale.ROOT));
    }

    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String key(String blockName) {
        return blockName.trim().toLowerCase(Locale.ROOT);
    }

    private static String plotKey(String blockKey, String plotNumber) {
        return (blockKey == null ? "" : blockKey) + "/" + plotNumber;
    }

    private static String label(String blockName, String plotNumber) {
        return (blockName == null ? "" : "Block " + blockName + ", ") + "Plot " + plotNumber;
    }

    private static String safe(String fileName) {
        return fileName == null ? "(unnamed)" : fileName.replaceAll("[\\r\\n]", "_");
    }

    private Estate requireOwnedEstate(UUID estateId) {
        TenantScope scope = currentScope();
        if (scope.tenantId() == null) {
            throw new InventoryException.InvalidRequest(
                    "This endpoint belongs to a tenant's own portal; the caller has no tenant scope.");
        }
        return estateRepository.findByIdAndTenantId(estateId, scope.tenantId())
                .orElseThrow(InventoryException.EstateNotFound::new);
    }

    private static TenantScope currentScope() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated request — TenantContextFilter should have set one."));
    }

    private record Row(int position, String plotNumber, String blockName, PriceTier tier, boolean corner,
                       GeoJsonPolygonDto footprintDto, Polygon footprint) {
    }

    private static final class Analysis {
        int featureCount;
        final List<Row> rows = new ArrayList<>();
        final List<PlotImportIssueDto> errors = new ArrayList<>();
        final List<PlotImportIssueDto> warnings = new ArrayList<>();
        final List<String> blocksToCreate = new ArrayList<>();
        final Map<String, UUID> existingBlockIds = new HashMap<>();
        final Map<String, Integer> plotsPerTier = new LinkedHashMap<>();

        void error(Integer feature, String plotNumber, String code, String message) {
            errors.add(new PlotImportIssueDto(feature, plotNumber, code, message));
        }

        void warning(Integer feature, String plotNumber, String code, String message) {
            warnings.add(new PlotImportIssueDto(feature, plotNumber, code, message));
        }

        void fileError(String code, String message) {
            error(null, null, code, message);
        }

        PlotImportReportDto report(boolean imported, int created, Integer overlaps) {
            return new PlotImportReportDto(featureCount, rows.size(), errors.isEmpty(), imported, created,
                    List.copyOf(blocksToCreate), Map.copyOf(plotsPerTier), List.copyOf(errors),
                    List.copyOf(warnings), overlaps, NOTE);
        }
    }
}
