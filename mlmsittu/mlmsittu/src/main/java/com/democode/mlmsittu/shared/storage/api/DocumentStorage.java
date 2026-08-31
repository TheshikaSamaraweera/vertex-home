package com.democode.mlmsittu.shared.storage.api;

/**
 * Private object storage for KYC documents and bank slips.
 *
 * <p>Architecture §7.2 specifies S3-compatible storage with a bucket policy denying all public
 * access. Docker is not available on this machine, so MinIO is not either — the implementation
 * behind this interface writes to the local filesystem instead, outside any directory the web
 * server serves. Swapping to S3 is one class, and no caller changes.
 *
 * <p>The invariant either implementation must hold: <b>nothing here is ever reachable by URL.</b>
 * Bytes leave only through {@code DocumentService}, which authorises the request and logs it first.
 */
public interface DocumentStorage {

    /**
     * @param content the bytes to store — already validated and re-encoded by the caller
     * @param kind {@code nic}, {@code bank_slip} or {@code other}; used to organise keys
     */
    StoredObjectRef put(byte[] content, String contentType, String kind);

    byte[] get(String objectKey);

    /** Used by the retention sweep and by erasure requests (architecture §7.1). */
    void delete(String objectKey);

    boolean exists(String objectKey);
}
