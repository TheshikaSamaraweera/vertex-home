package com.democode.mlmsittu.catalogue.internal.web;

import com.democode.mlmsittu.catalogue.internal.service.ItemSetService;
import com.democode.mlmsittu.catalogue.internal.web.dto.ItemSetDtos.CreateItemSetRequest;
import com.democode.mlmsittu.catalogue.internal.web.dto.ItemSetDtos.ItemSetResponse;
import com.democode.mlmsittu.catalogue.internal.web.dto.ItemSetDtos.UpdateItemSetRequest;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.api.PagedResponse;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import com.democode.mlmsittu.shared.storage.api.ServedDocument;
import com.democode.mlmsittu.shared.storage.api.StoredDocument;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

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

    /** The vault's kind for set pictures. Served without a token, so checked on every read. */
    private static final String IMAGE_KIND = "item_set";

    private final ItemSetService sets;
    private final DocumentVault vault;
    private final CurrentUser currentUser;

    public ItemSetController(ItemSetService sets, DocumentVault vault, CurrentUser currentUser) {
        this.sets = sets;
        this.vault = vault;
        this.currentUser = currentUser;
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
                        body.imageId(),
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
                sets.update(
                        id,
                        body.name(),
                        body.description(),
                        body.setPrice(),
                        body.imageId(),
                        components));
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

    // ------------------------------------------------------------------ pictures

    /**
     * Stores a set picture and returns its id, which the create or update request then carries.
     *
     * <p>Uploaded before the set is saved, so a slow upload does not hold the form, and a set is
     * never left pointing at a half-written file.
     */
    @PostMapping("/image")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public StoredDocument uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            return vault.store(
                    file.getBytes(), file.getContentType(), IMAGE_KIND, currentUser.requireId());
        } catch (IOException unreadable) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "UPLOAD_UNREADABLE", "That file could not be read.");
        }
    }

    /**
     * A set picture, for any signed-in member of staff.
     *
     * <p>No access token and no access log, like announcement pictures: a photo of a furniture
     * pack is nobody's personal data. {@link DocumentVault#readPublic} refuses any document that
     * is not of kind {@code item_set}, so this can never serve a NIC scan by id.
     */
    @GetMapping("/image/{id}")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        ServedDocument served = vault.readPublic(id, IMAGE_KIND);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(served.contentType()))
                // Immutable: the vault never rewrites a stored object, so an id always names the
                // same bytes.
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                .body(served.content());
    }
}
