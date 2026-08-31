package com.democode.mlmsittu.shared.storage.api;

/**
 * What was actually written.
 *
 * @param sha256 digest of the stored bytes, not of the upload. The two differ because images are
 *     re-encoded to strip metadata, and recording the digest of bytes nobody kept would be useless
 *     for later integrity checks.
 */
public record StoredObjectRef(String objectKey, long byteSize, String sha256) {}
