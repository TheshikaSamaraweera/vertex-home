package com.democode.mlmsittu.catalogue.internal.web;

import com.democode.mlmsittu.catalogue.internal.service.ItemSetService;
import com.democode.mlmsittu.catalogue.internal.web.dto.ItemSetDtos.CreateItemSetRequest;
import com.democode.mlmsittu.catalogue.internal.web.dto.ItemSetDtos.ItemSetResponse;
import com.democode.mlmsittu.catalogue.internal.web.dto.ItemSetDtos.UpdateItemSetRequest;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Item set composition.
 *
 * <p>Availability lives on the same path but in the inventory module — see
 * {@code SetAvailabilityController}. Composition is a catalogue question; what can be assembled
 * from it is a stock question.
 */
@RestController
@RequestMapping("/api/v1/item-sets")
@PreAuthorize("hasRole('STAFF')")
public class ItemSetController {

    private final ItemSetService sets;

    public ItemSetController(ItemSetService sets) {
        this.sets = sets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ItemSetResponse create(@Valid @RequestBody CreateItemSetRequest body) {
        List<ItemSetService.ComponentRequest> components =
                body.components().stream()
                        .map(c -> new ItemSetService.ComponentRequest(c.itemId(), c.quantity()))
                        .toList();
        return ItemSetResponse.from(
                sets.create(
                        body.code(),
                        body.name(),
                        body.description(),
                        body.setPrice(),
                        components));
    }

    @GetMapping
    public PagedResponse<ItemSetResponse> list(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return PagedResponse.of(
                sets.list(includeInactive).stream().map(ItemSetResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ItemSetResponse get(@PathVariable UUID id) {
        return ItemSetResponse.from(sets.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ItemSetResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateItemSetRequest body) {
        List<ItemSetService.ComponentRequest> components =
                body.components().stream()
                        .map(c -> new ItemSetService.ComponentRequest(c.itemId(), c.quantity()))
                        .toList();
        return ItemSetResponse.from(
                sets.update(id, body.name(), body.description(), body.setPrice(), components));
    }

    /**
     * Deletes a set, if it has never been sold.
     *
     * <p>Refused with {@code ITEM_SET_IN_USE} once an order line names it — an invoice that says
     * "Starter Pack" has to keep meaning something. Deactivating remains the answer for a set that
     * has history.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSet(@PathVariable UUID id) {
        sets.delete(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ItemSetResponse deactivate(@PathVariable UUID id) {
        return ItemSetResponse.from(sets.setActive(id, false));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ItemSetResponse activate(@PathVariable UUID id) {
        return ItemSetResponse.from(sets.setActive(id, true));
    }
}
