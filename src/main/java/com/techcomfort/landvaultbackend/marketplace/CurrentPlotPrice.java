package com.techcomfort.landvaultbackend.marketplace;

import com.techcomfort.landvaultbackend.common.Currency;

import java.math.BigDecimal;

/**
 * What the developer charges for a plot today — its tier's price and any
 * corner premium, worked out by {@code PlotPricing} and rounded to the kobo
 * as a reservation is — read through the public projection.
 */
public record CurrentPlotPrice(BigDecimal price, Currency currency) {
}
