/**
 * Files people upload (estate photos, and later KYC, tenant and title
 * documents), kept in S3-compatible object storage. The browser uploads
 * straight to storage with a short-lived link; this module issues the link,
 * checks what actually arrived, and serves it back. It knows nothing about
 * estates or buyers: each module keeps its own records and points at a stored
 * file. Public surface: {@link com.techcomfort.landvaultbackend.storage.StorageApi}
 * and the records beside it. See AGENTS.md, "File storage".
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Storage"
)
package com.techcomfort.landvaultbackend.storage;
