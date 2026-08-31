package com.democode.mlmsittu.shared.storage.api;

import java.util.UUID;

/** A row of {@code stored_document} — what was kept, not the bytes themselves. */
public record StoredDocument(UUID id, String objectKey, String contentType, long byteSize) {}
