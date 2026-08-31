package com.democode.mlmsittu.identity.internal.domain;

/**
 * Account lifecycle, mirroring the {@code chk_app_user_status} constraint.
 *
 * <p>This is the <em>account</em> state machine. The distributor state machine of flow F-04
 * (submitted / under_review / approved / active) is a separate concern and arrives in Phase 4 on
 * the {@code distributor} table — an account can be active while its business registration is
 * still pending review.
 */
public enum UserStatus {

    /** Created but email and mobile not both confirmed. Login is refused. */
    UNVERIFIED("unverified"),

    /** May log in. */
    ACTIVE("active"),

    /** Blocked by an administrator. Login is refused; the record is untouched. */
    SUSPENDED("suspended"),

    /** Soft-deleted. Financial records are retained for statutory audit (architecture §2.3). */
    DELETED("deleted");

    private final String dbValue;

    UserStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static UserStatus fromDbValue(String value) {
        for (UserStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown app_user.status value");
    }
}
