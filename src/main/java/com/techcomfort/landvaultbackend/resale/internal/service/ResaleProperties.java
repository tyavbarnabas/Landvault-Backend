package com.techcomfort.landvaultbackend.resale.internal.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code landvault.resale.*}: sell my plot. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "landvault.resale")
public class ResaleProperties {

    private String fromAddress;

    /** The portal's resale screen, linked from the developer's emails. */
    private String reviewUrl;
}
