package com.democode.mlmsittu.shared.storage.api;

/** Bytes released against a redeemed token, with the type they must be served as. */
public record ServedDocument(byte[] content, String contentType) {}
