package com.techcomfort.landvaultbackend.marketplace.internal.enums;

import com.techcomfort.landvaultbackend.marketplace.internal.exceptions.WishlistException;

/** What a saved listing is. {@code RESALE} is the frontend's other type, refused until resale exists. */
public enum WishlistListingType {

    PRIMARY("primary"),
    RESALE("resale");

    private final String value;

    WishlistListingType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** A type the wishlist accepts today, from its path value. */
    public static WishlistListingType supported(String value) {
        for (WishlistListingType type : values()) {
            if (type.value.equals(value)) {
                if (type != PRIMARY) {
                    throw new WishlistException.TypeNotSupported(value);
                }
                return type;
            }
        }
        throw new WishlistException.TypeUnknown(value);
    }
}
