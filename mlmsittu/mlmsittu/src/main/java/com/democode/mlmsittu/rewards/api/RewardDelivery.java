package com.democode.mlmsittu.rewards.api;

import java.util.List;
import java.util.UUID;

/**
 * What a customer may do about their own issued pack: say how they will receive it.
 *
 * <p>Once. The first choice is final from the customer's side — a warehouse starts preparing
 * against it — and after that only an administrator can change it, with their password.
 */
public interface RewardDelivery {

    /** A warehouse a customer may collect from. */
    record PickupPoint(UUID id, String code, String name, String address) {}

    /**
     * @param method {@code pickup} or {@code delivery}
     * @param pickupLocationId required for pickup
     * @param deliveryAddress required for delivery
     * @param deliveryContact required for delivery — the number the driver calls
     */
    record ReceiveMethodChoice(
            String method, UUID pickupLocationId, String deliveryAddress, String deliveryContact) {}

    List<PickupPoint> pickupPoints();

    /** The customer's own choice. Refused if their pack is not issued yet or already has one. */
    void chooseReceiveMethod(UUID distributorId, ReceiveMethodChoice choice);
}
