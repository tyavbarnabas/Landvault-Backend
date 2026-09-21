/**
 * Owns the sellable catalogue: estates, their blocks and plots, the price
 * tiers plots are sold from, and each estate's title and due-diligence
 * checks.
 * <p>
 * The module four others are blocked on — {@code marketplace} projects
 * published estates, {@code sales} reserves and allocates plots,
 * {@code finance} charges against a price tier, and {@code documents}
 * reference a plot. None of them can start until these rows exist.
 * <p>
 * This is also where PostGIS earns its place: estates and plots both carry a
 * real {@code geometry(Polygon,4326)} footprint, which is what makes genuine
 * {@code ST_Intersects} conflict detection possible rather than a bounding-box
 * heuristic. See AGENTS.md's spatial conventions — they are decisions, not
 * preferences, and getting them wrong yields plausible wrong numbers rather
 * than errors.
 * <p>
 * Nothing is exposed yet: this slice is schema and entities only, so there is
 * deliberately no {@code InventoryApi} and no public DTO. Everything lives
 * under {@code inventory.internal} and is invisible to other modules —
 * repositories, services and endpoints arrive in later slices.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Inventory"
)
package com.techcomfort.landvaultbackend.inventory;
