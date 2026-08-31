package com.democode.mlmsittu.catalogue.internal.web;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.service.CategoryService;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService;
import com.democode.mlmsittu.catalogue.internal.service.ItemService;
import com.democode.mlmsittu.catalogue.internal.web.dto.CatalogueDtos.CategoryResponse;
import com.democode.mlmsittu.catalogue.internal.web.dto.CatalogueDtos.CreateCategoryRequest;
import com.democode.mlmsittu.catalogue.internal.web.dto.CatalogueDtos.CreateItemRequest;
import com.democode.mlmsittu.catalogue.internal.web.dto.CatalogueDtos.ItemResponse;
import com.democode.mlmsittu.catalogue.internal.web.dto.CatalogueDtos.UpdateItemRequest;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.api.Cursor;
import com.democode.mlmsittu.shared.api.PagedResponse;
import com.democode.mlmsittu.shared.error.ForbiddenException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalogue endpoints.
 *
 * <p>Writes are {@code inventory_clerk} per architecture §8.1 ("Items, stock, goods receipt"), and
 * an admin or super admin reaches them through the role hierarchy. Reads are open to any
 * authenticated role — procurement needs to pick items for an order and support needs to answer
 * questions about them.
 *
 * <h2>Two authorisations here, not one</h2>
 *
 * {@code @PreAuthorize} answers "may you call this endpoint". Retail and wholesale prices need a
 * second, narrower answer — "may you set <em>this field</em>" — because everyone who can edit an
 * item may change its name and nobody but a super admin may change what it is quoted at. An
 * annotation cannot express that, so it is checked in the handler.
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('STAFF')")
public class CatalogueController {

    /**
     * Big enough that the screens which have not adopted the cursor yet still show everything,
     * small enough to be a real page. The cap is what stops {@code limit=1000000} being a denial
     * of service dressed as a query parameter.
     */
    private static final int DEFAULT_PAGE = 500;

    private static final int MAX_PAGE = 500;

    private final ItemService itemService;
    private final ItemProvisioningService provisioning;
    private final CategoryService categoryService;
    private final CurrentUser currentUser;

    public CatalogueController(
            ItemService itemService,
            ItemProvisioningService provisioning,
            CategoryService categoryService,
            CurrentUser currentUser) {
        this.itemService = itemService;
        this.provisioning = provisioning;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ items

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ItemResponse createItem(@Valid @RequestBody CreateItemRequest body) {
        assertMayQuote(null, body.retailPrice(), body.wholesalePrice());

        return ItemResponse.from(
                provisioning.createWithOpeningStock(
                        body.sku(),
                        new ItemService.ItemDetails(
                                body.name(),
                                body.description(),
                                body.categoryId(),
                                body.unitCost(),
                                sellingPriceOf(body.sellingPrice(), body.retailPrice()),
                                body.retailPrice(),
                                body.wholesalePrice(),
                                body.reorderLevel()),
                        new ItemProvisioningService.OpeningStock(
                                body.locationId(),
                                body.openingQuantity() == null ? 0 : body.openingQuantity()),
                        currentUser.requireId()));
    }

    /** Deactivated items are hidden unless asked for explicitly (P2-02). */
    /**
     * The catalogue, one page at a time (P7-03).
     *
     * <p>{@code cursor} is opaque and comes from the previous response's {@code nextCursor};
     * absent means the first page. The envelope is the one frozen in P0-05 and did not change to
     * accommodate any of this.
     *
     * <p>Backwards compatible on purpose: a caller that ignores {@code nextCursor} still gets a
     * sensible first page rather than an error, which is what let the frontend adopt this one
     * screen at a time.
     */
    @GetMapping("/items")
    public PagedResponse<ItemResponse> listItems(
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        int size = Cursor.clampLimit(limit, DEFAULT_PAGE, MAX_PAGE);
        List<Item> fetched = itemService.page(includeInactive, Cursor.decodeOrNull(cursor), size);

        return PagedResponse.page(
                fetched.stream().map(ItemResponse::from).toList(),
                size,
                row -> new Cursor(row.name(), row.id()));
    }

    /** Fetchable by id whether active or not, so history and old orders still resolve. */
    @GetMapping("/items/{id}")
    public ItemResponse getItem(@PathVariable UUID id) {
        return ItemResponse.from(itemService.get(id));
    }

    @PutMapping("/items/{id}")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ItemResponse updateItem(
            @PathVariable UUID id, @Valid @RequestBody UpdateItemRequest body) {

        Item existing = itemService.get(id);
        assertMayQuote(existing, body.retailPrice(), body.wholesalePrice());

        return ItemResponse.from(
                itemService.update(
                        id,
                        new ItemService.ItemDetails(
                                body.name(),
                                body.description(),
                                body.categoryId(),
                                body.unitCost(),
                                sellingPriceOf(body.sellingPrice(), body.retailPrice()),
                                body.retailPrice(),
                                body.wholesalePrice(),
                                body.reorderLevel())));
    }

    /**
     * What an order will charge.
     *
     * <p>The item form no longer asks for a selling price — the client's point was that retail and
     * wholesale already say what things cost, and a third number to keep in step with them was one
     * too many. So the retail price <em>is</em> the selling price unless a caller states otherwise,
     * and {@code selling_price} stays in the schema as the single field the order flow reads.
     *
     * <p>Zero when neither is given. That is deliberate rather than a fallback: only a super admin
     * may set a retail price, so an item created by a clerk arrives unpriced, and selling it is
     * refused with {@code PRICE_REQUIRED} until somebody prices it. Guessing a number here would
     * turn "nobody has priced this" into a sale at the wrong figure.
     */
    private BigDecimal sellingPriceOf(BigDecimal explicit, BigDecimal retail) {
        if (explicit != null) {
            return explicit;
        }
        return retail != null ? retail : BigDecimal.ZERO;
    }

    /**
     * Refuses a change to the quoted prices by anyone but a super admin.
     *
     * <p>Compares against what is already stored rather than simply rejecting the presence of the
     * fields. A clerk editing an item's reorder level sends the whole object back, retail price
     * included — rejecting that would make the form unusable for everyone except a super admin.
     * What is refused is an actual <em>change</em>.
     *
     * @param existing null when creating, where any value at all counts as setting one
     */
    private void assertMayQuote(Item existing, BigDecimal retail, BigDecimal wholesale) {
        if (currentUser.hasRole("SUPER_ADMIN")) {
            return;
        }

        boolean retailChanged =
                !sameAmount(retail, existing == null ? null : existing.getRetailPrice());
        boolean wholesaleChanged =
                !sameAmount(wholesale, existing == null ? null : existing.getWholesalePrice());

        if (retailChanged || wholesaleChanged) {
            throw new ForbiddenException(
                    "PRICE_CHANGE_FORBIDDEN",
                    "Only a super admin can set the retail and wholesale prices.");
        }
    }

    /** {@code compareTo}, not {@code equals}: 690 and 690.00 are the same price. */
    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return left.compareTo(right) == 0;
    }

    @PostMapping("/items/{id}/deactivate")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ItemResponse deactivateItem(@PathVariable UUID id) {
        return ItemResponse.from(itemService.setActive(id, false));
    }

    @PostMapping("/items/{id}/activate")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ItemResponse activateItem(@PathVariable UUID id) {
        return ItemResponse.from(itemService.setActive(id, true));
    }

    // ------------------------------------------------------------------ categories

    /**
     * Admins only, at the client's request.
     *
     * <p>Categories are the shape every report groups by, so a clerk inventing one mid-shift
     * fragments the numbers everybody else reads. Creating them is now a decision, not a
     * convenience.
     */
    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse createCategory(@Valid @RequestBody CreateCategoryRequest body) {
        return CategoryResponse.from(categoryService.create(body.code(), body.name()));
    }

    @GetMapping("/categories")
    public PagedResponse<CategoryResponse> listCategories() {
        return PagedResponse.of(
                categoryService.list().stream().map(CategoryResponse::from).toList());
    }
}
