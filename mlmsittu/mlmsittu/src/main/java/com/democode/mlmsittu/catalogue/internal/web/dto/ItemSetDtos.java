package com.democode.mlmsittu.catalogue.internal.web.dto;

import com.democode.mlmsittu.catalogue.internal.domain.ItemSet;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class ItemSetDtos {

    private ItemSetDtos() {}

    public record ComponentRequest(
            @NotNull(message = "REQUIRED") UUID itemId,
            @Min(value = 1, message = "MUST_BE_AT_LEAST_ONE") int quantity) {}

    public record CreateItemSetRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 64) String code,
            @NotBlank(message = "REQUIRED") @Size(max = 255) String name,
            @Size(max = 1000) String description,
            @NotNull(message = "REQUIRED")
                    @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal setPrice,
            @NotEmpty(message = "AT_LEAST_ONE_COMPONENT_REQUIRED") @Valid
                    List<ComponentRequest> components) {}

    public record UpdateItemSetRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 255) String name,
            @Size(max = 1000) String description,
            @NotNull(message = "REQUIRED")
                    @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal setPrice,
            @NotEmpty(message = "AT_LEAST_ONE_COMPONENT_REQUIRED") @Valid
                    List<ComponentRequest> components) {}

    public record ComponentResponse(UUID itemId, int quantity) {}

    public record ItemSetResponse(
            UUID id,
            String code,
            String name,
            String description,
            BigDecimal setPrice,
            boolean active,
            List<ComponentResponse> components) {

        public static ItemSetResponse from(ItemSet set) {
            return new ItemSetResponse(
                    set.getId(),
                    set.getCode(),
                    set.getName(),
                    set.getDescription(),
                    set.getSetPrice(),
                    set.isActive(),
                    set.getLines().stream()
                            .map(line -> new ComponentResponse(line.getItemId(), line.getQuantity()))
                            .toList());
        }
    }
}
