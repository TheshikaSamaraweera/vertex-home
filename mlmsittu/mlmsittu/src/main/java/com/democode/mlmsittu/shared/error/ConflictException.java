package com.democode.mlmsittu.shared.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }

    /**
     * Turns a specific constraint violation into a specific error, and rethrows anything else.
     *
     * <p>Written after a blanket {@code catch (DataIntegrityViolationException)} reported "a set
     * with that code already exists" for a NOT NULL violation on an entirely different column. The
     * message sent everyone hunting a duplicate that was never there. A handler that only claims
     * the violation it actually recognises costs one string comparison and saves that afternoon.
     *
     * @param constraintFragment text expected in the driver's message, normally the constraint or
     *     index name
     */
    public static RuntimeException ifConstraintIs(
            org.springframework.dao.DataIntegrityViolationException cause,
            String constraintFragment,
            String code,
            String message) {

        Throwable specific = cause.getMostSpecificCause();
        String text = specific.getMessage();
        if (text != null && text.contains(constraintFragment)) {
            return new ConflictException(code, message);
        }
        return cause;
    }
}
