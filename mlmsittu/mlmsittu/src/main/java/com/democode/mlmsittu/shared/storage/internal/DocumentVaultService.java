package com.democode.mlmsittu.shared.storage.internal;

import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.shared.storage.api.DocumentStorage;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import com.democode.mlmsittu.shared.storage.api.ServedDocument;
import com.democode.mlmsittu.shared.storage.api.StoredDocument;
import com.democode.mlmsittu.shared.storage.api.StoredObjectRef;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores private documents and controls every read of one (P4-08, P4-09, P5-04).
 *
 * <p>Documents are never reachable by URL. To see one, a caller asks for an access token; the
 * request is authorised and <b>logged before any bytes move</b>, and the token then expires in 60
 * seconds and works exactly once. That is the local equivalent of the presigned URL in
 * architecture §7.2 — same properties, no S3.
 *
 * <p>Logging before serving is the ordering that matters. Log afterwards and a failed or aborted
 * download leaves no trace, which is precisely the access a PDPA investigation would want to see.
 */
@Service
public class DocumentVaultService implements DocumentVault {

    /** Architecture §7.2 specifies 60 seconds. */
    private static final Duration TOKEN_TTL = Duration.ofSeconds(60);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DocumentStorage storage;
    private final UploadSanitiser sanitiser;
    private final JdbcTemplate jdbc;

    /**
     * Single-use access grants, in memory alongside sessions and MFA challenges. Moves to Redis in
     * Phase 7 with the rest; until then a restart invalidates outstanding tokens, which for a
     * 60-second credential is not a meaningful loss.
     */
    private final Map<String, AccessGrant> grants = new ConcurrentHashMap<>();

    private record AccessGrant(UUID documentId, UUID actorId, UUID subjectUserId, Instant expiresAt) {}

    public DocumentVaultService(
            DocumentStorage storage, UploadSanitiser sanitiser, JdbcTemplate jdbc) {
        this.storage = storage;
        this.sanitiser = sanitiser;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ writing

    @Override
    @Transactional
    public StoredDocument store(
            byte[] uploaded, String declaredContentType, String kind, UUID actorId) {

        UploadSanitiser.SanitisedUpload clean = sanitiser.sanitise(uploaded, declaredContentType);
        StoredObjectRef stored = storage.put(clean.content(), clean.contentType(), kind);

        UUID id =
                jdbc.queryForObject(
                        """
                        INSERT INTO stored_document
                            (object_key, kind, content_type, byte_size, sha256, uploaded_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                        UUID.class,
                        stored.objectKey(),
                        kind,
                        clean.contentType(),
                        stored.byteSize(),
                        stored.sha256(),
                        actorId);

        recordAccess(id, actorId, null, "upload", null);
        return new StoredDocument(id, stored.objectKey(), clean.contentType(), stored.byteSize());
    }

    // ------------------------------------------------------------------ reading

    @Override
    @Transactional
    public String issueAccessToken(UUID documentId, UUID actorId, UUID subjectUserId, String ip) {
        find(documentId).orElseThrow(this::notFound);

        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        purgeExpired();
        grants.put(
                token,
                new AccessGrant(documentId, actorId, subjectUserId, Instant.now().plus(TOKEN_TTL)));

        recordAccess(documentId, actorId, subjectUserId, "view_authorised", ip);
        return token;
    }

    @Override
    public long tokenTtlSeconds() {
        return TOKEN_TTL.toSeconds();
    }

    /**
     * Exchanges a token for bytes. Single use: the grant is removed before the read, so a replayed
     * token fails even if the first attempt is still in flight.
     */
    @Override
    @Transactional
    public ServedDocument redeem(String token, String ip) {
        AccessGrant grant = grants.remove(token);

        if (grant == null || Instant.now().isAfter(grant.expiresAt())) {
            // Expired, already used, or never existed. The caller cannot tell which, and should
            // not be able to — distinguishing them turns this into an oracle.
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "DOCUMENT_ACCESS_DENIED",
                    "That document link has expired or has already been used.");
        }

        StoredDocument document = find(grant.documentId()).orElseThrow(this::notFound);
        recordAccess(document.id(), grant.actorId(), grant.subjectUserId(), "view_served", ip);

        return new ServedDocument(storage.get(document.objectKey()), document.contentType());
    }

    // ------------------------------------------------------------------ helpers

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredDocument> find(UUID documentId) {
        return jdbc
                .query(
                        """
                        SELECT id, object_key, content_type, byte_size
                        FROM stored_document WHERE id = ?
                        """,
                        (rs, rowNum) ->
                                new StoredDocument(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("object_key"),
                                        rs.getString("content_type"),
                                        rs.getLong("byte_size")),
                        documentId)
                .stream()
                .findFirst();
    }

    /** Every touch of a private document — actor, subject, action, time, IP (architecture §7.1). */
    private void recordAccess(
            UUID documentId, UUID actorId, UUID subjectUserId, String action, String ip) {
        jdbc.update(
                """
                INSERT INTO document_access_log
                    (document_id, actor_id, subject_user_id, action, ip)
                VALUES (?, ?, ?, ?, ?::inet)
                """,
                documentId,
                actorId,
                subjectUserId,
                action,
                ip);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        grants.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }

    private NotFoundException notFound() {
        return new NotFoundException("DOCUMENT_NOT_FOUND", "No document with that id.");
    }
}
