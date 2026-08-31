package com.democode.mlmsittu.inventory.api;

import com.democode.mlmsittu.shared.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A posting would drive stock below zero, or below what is already reserved.
 *
 * <p>Carries the numbers as structured properties, per architecture §9, so a caller can say
 * "short by 3" without parsing an English sentence.
 */
public class InsufficientStockException extends ApiException {

    public InsufficientStockException(UUID itemId, UUID locationId, int required, int available) {
        super(
                HttpStatus.CONFLICT,
                "INSUFFICIENT_STOCK",
                "Not enough stock at this location to complete the movement.");
        with("itemId", itemId);
        with("locationId", locationId);
        with("required", required);
        with("available", available);
        with("shortfall", required - available);
    }
}
