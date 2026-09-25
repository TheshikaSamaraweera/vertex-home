package com.democode.mlmsittu.portal.internal.web;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.portal.api.PortalView;
import com.democode.mlmsittu.portal.internal.PackCatalogueService;
import com.democode.mlmsittu.portal.internal.PortalService;
import com.democode.mlmsittu.shared.api.PagedResponse;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import com.democode.mlmsittu.shared.storage.api.ServedDocument;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The distributor's own view of themselves.
 *
 * <h2>Why these are separate endpoints from the admin ones</h2>
 *
 * The admin screens answer "show me this person"; these answer "show me myself". Sharing an
 * endpoint between the two would mean a single handler deciding, from the caller's roles, how much
 * of the response to blank out — and the first field somebody forgets to blank is a leak that looks
 * like a feature. Separate routes make the audience part of the address.
 *
 * <p>Every method here derives the subject from the session. <b>None of them takes an id</b>, so
 * there is no parameter to tamper with.
 */
@RestController
@RequestMapping("/api/v1/portal")
@PreAuthorize("hasRole('DISTRIBUTOR')")
public class PortalController {

    /** The picture kinds a customer may read: pack and item photos, nothing else. */
    private static final List<String> PICTURE_KINDS = List.of("item_set", "item");

    private final PortalService portal;
    private final PackCatalogueService packs;
    private final DocumentVault vault;
    private final CurrentUser currentUser;

    public PortalController(
            PortalService portal,
            PackCatalogueService packs,
            DocumentVault vault,
            CurrentUser currentUser) {
        this.portal = portal;
        this.packs = packs;
        this.vault = vault;
        this.currentUser = currentUser;
    }

    /**
     * Every active item pack, with its contents — or, once the customer's registration has chosen
     * one, that pack in full and the others locked. Open before registration is approved; see
     * {@link PackCatalogueService}.
     */
    @GetMapping("/item-packs")
    public PackCatalogueService.Catalogue itemPacks() {
        return packs.forCustomer(currentUser.requireId());
    }

    /**
     * A pack or item photo.
     *
     * <p>The staff endpoints for these pictures are staff-only, so the portal serves them itself.
     * Only the two picture kinds are readable here — {@link DocumentVault#readPublic} checks the
     * stored kind, so no id, guessed or leaked, can fetch a NIC scan or a bank slip through this.
     */
    @GetMapping("/pictures/{id}")
    public ResponseEntity<byte[]> picture(@PathVariable UUID id) {
        ApiException notFound = null;
        for (String kind : PICTURE_KINDS) {
            try {
                ServedDocument served = vault.readPublic(id, kind);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(served.contentType()))
                        .cacheControl(
                                CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                        .body(served.content());
            } catch (ApiException wrongKind) {
                notFound = wrongKind;
            }
        }
        throw notFound;
    }

    /**
     * Everything at once: who they are, where their application stands, and — only once approved —
     * their Business ID, stages, parent and direct referrals.
     *
     * <p>Always answers, whatever their state. An applicant who has been refused still needs to be
     * told that, and by whom, and why.
     */
    @GetMapping("/me")
    public PortalView me() {
        return portal.viewFor(currentUser.requireId());
    }

    /**
     * The direct referrals, on their own.
     *
     * <p>Duplicates a field of {@code /me} because the referrals screen refreshes far more often
     * than the rest — a distributor watching for a new sign-up should not re-fetch their whole
     * profile to do it. Refused outright until the account is active.
     */
    @GetMapping("/referrals")
    public PagedResponse<DistributorNode> referrals() {
        return PagedResponse.of(portal.directReferrals(currentUser.requireId()));
    }
}
