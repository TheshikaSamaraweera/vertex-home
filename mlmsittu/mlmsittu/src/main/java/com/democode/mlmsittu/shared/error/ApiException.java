package com.democode.mlmsittu.shared.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Base type for every error the API deliberately returns.
 *
 * <p>Each carries a machine-readable {@code code} that the frontend matches on. The frontend
 * must never match on message text — messages are for humans and may be translated.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Attach an extra field to the problem body, e.g. {@code itemId} or {@code shortfall}. */
    public ApiException with(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
