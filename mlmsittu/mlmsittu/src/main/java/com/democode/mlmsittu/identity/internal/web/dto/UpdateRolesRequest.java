package com.democode.mlmsittu.identity.internal.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/**
 * Replaces a user's entire role set. Absolute rather than incremental: an "add one role" API makes
 * the resulting privileges depend on request ordering, which is a poor property for the operation
 * that decides who can approve payments.
 */
public record UpdateRolesRequest(
        @NotEmpty(message = "AT_LEAST_ONE_ROLE_REQUIRED") Set<String> roleCodes) {}
