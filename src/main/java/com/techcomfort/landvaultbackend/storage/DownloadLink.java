package com.techcomfort.landvaultbackend.storage;

import java.time.Instant;

/** A short-lived link to one private file. Never stored, never logged. */
public record DownloadLink(String url, Instant expiresAt) {
}
