package com.democode.mlmsittu.shared.storage.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Private documents — stored, authorised, and logged (architecture §7.2).
 *
 * <p>Nothing here is reachable by URL. To see a document a caller asks for an access token; the
 * request is authorised and <b>logged before any bytes move</b>, and the token then expires in
 * seconds and works exactly once.
 *
 * <h2>Why this sits in the shared kernel</h2>
 *
 * It arrived in Phase 4 as {@code onboarding.internal.document.DocumentService}, because KYC was the
 * only thing that stored files. Phase 5 adds bank payment slips, which need the same three
 * properties — magic-byte validation, private storage, logged single-use reads — and live in
 * {@code commerce}. Copying the class would have meant two access logs and two chances to forget
 * one; reaching across module boundaries is what the ArchUnit rules exist to stop. So it moved
 * here, where both modules may use it and neither owns it.
 *
 * <p>Authorisation stays with the caller. This interface decides that a read is <em>recorded</em>;
 * whether a particular person may see a particular document is a question only the owning module
 * can answer, and each does so at its own endpoint.
 */
public interface DocumentVault {

    /**
     * Validates, sanitises and stores an upload.
     *
     * @param kind {@code nic}, {@code bank_slip} or {@code other} — organises keys and makes the
     *     stored row self-describing
     */
    StoredDocument store(byte[] uploaded, String declaredContentType, String kind, UUID actorId);

    /**
     * Authorises a view and returns a one-shot token.
     *
     * <p>The access is logged here, at the moment permission is granted, rather than when bytes are
     * later fetched — a download that fails or is abandoned still leaves the trace an investigation
     * would want.
     *
     * @param subjectUserId whose data this is, when that is known; null for documents that belong
     *     to no single person
     */
    String issueAccessToken(UUID documentId, UUID actorId, UUID subjectUserId, String ip);

    /** Exchanges a token for bytes. Single use. */
    ServedDocument redeem(String token, String ip);

    long tokenTtlSeconds();

    Optional<StoredDocument> find(UUID documentId);
}
