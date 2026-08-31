package com.democode.mlmsittu.catalogue.internal.web.dto;

import com.democode.mlmsittu.catalogue.internal.domain.Category;
import com.democode.mlmsittu.catalogue.internal.domain.Item;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for the catalogue endpoints, kept together for readability. */
public final class CatalogueDtos {

    private CatalogueDtos() {}

    public record CreateItemRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 64) String sku,
            @NotBlank(message = "REQUIRED") @Size(max = 255) String name,
            @Size(max = 1000) String description,
            UUID categoryId,
            @NotNull(message = "REQUIRED")
                    @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal unitCost,
            /**
             * What an order charges. No longer collected on the item form — the retail price is
             * the number the business quotes, so this follows it. Left on the API because an
             * integration may still want to set it explicitly.
             */
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal sellingPrice,

            /** Quoted prices. Super admin only; everyone else may read them. Null means unset. */
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal retailPrice,
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal wholesalePrice,

            /** Where the item is stocked. Null uses the default location. */
            UUID locationId,

            /** Opening balance. Null or zero still establishes an empty position at the location. */
            @Min(value = 0, message = "MUST_NOT_BE_NEGATIVE") Integer openingQuantity,

            @Min(value = 0, message = "MUST_NOT_BE_NEGATIVE") int reorderLevel) {}

    public record UpdateItemRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 255) String name,
            @Size(max = 1000) String description,
            UUID categoryId,
            @NotNull(message = "REQUIRED")
                    @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal unitCost,
            /**
             * What an order charges. No longer collected on the item form — the retail price is
             * the number the business quotes, so this follows it. Left on the API because an
             * integration may still want to set it explicitly.
             */
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal sellingPrice,
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal retailPrice,
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    @Digits(integer = 12, fraction = 2, message = "MAX_TWO_DECIMALS")
                    BigDecimal wholesalePrice,
            @Min(value = 0, message = "MUST_NOT_BE_NEGATIVE") int reorderLevel) {}

    public record ItemResponse(
            UUID id,
            String sku,
            String name,
            String description,
            UUID categoryId,
            BigDecimal unitCost,
            BigDecimal sellingPrice,
            BigDecimal retailPrice,
            BigDecimal wholesalePrice,
            int reorderLevel,
            boolean active,
            Instant createdAt,
            Instant updatedAt) {

        public static ItemResponse from(Item item) {
            return new ItemResponse(
                    item.getId(),
                    item.getSku(),
                    item.getName(),
                    item.getDescription(),
                    item.getCategoryId(),
                    item.getUnitCost(),
                    item.getSellingPrice(),
                    item.getRetailPrice(),
                    item.getWholesalePrice(),
                    item.getReorderLevel(),
                    item.isActive(),
                    item.getCreatedAt(),
                    item.getUpdatedAt());
        }
    }

    public record CreateCategoryRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 64) String code,
            @NotBlank(message = "REQUIRED") @Size(max = 255) String name) {}

    public record CategoryResponse(UUID id, String code, String name, boolean active) {

        public static CategoryResponse from(Category category) {
            return new CategoryResponse(
                    category.getId(), category.getCode(), category.getName(), category.isActive());
        }
    }
}
