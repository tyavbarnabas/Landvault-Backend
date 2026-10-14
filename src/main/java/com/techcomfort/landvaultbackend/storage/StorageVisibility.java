package com.techcomfort.landvaultbackend.storage;

/**
 * Which bucket a file lives in. The two never mix: a public file is served to
 * anyone through the CDN; a private one only through a link that expires in
 * minutes, issued by the module that decides who may see it.
 */
public enum StorageVisibility {
    PUBLIC,
    PRIVATE
}
