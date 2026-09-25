package com.democode.mlmsittu.onboarding.internal.announce;

import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.api.PagedResponse;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import com.democode.mlmsittu.shared.storage.api.ServedDocument;
import com.democode.mlmsittu.shared.storage.api.StoredDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Writing announcements, reading them, and serving their pictures. */
@RestController
@RequestMapping("/api/v1")
public class AnnouncementController {

    private final AnnouncementService announcements;
    private final DocumentVault vault;
    private final CurrentUser currentUser;

    public AnnouncementController(
            AnnouncementService announcements, DocumentVault vault, CurrentUser currentUser) {
        this.announcements = announcements;
        this.vault = vault;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ what customers see

    /**
     * The live announcements, for the portal home page.
     *
     * <p>Signed in, but no role: this is a notice for customers, and staff opening it is harmless.
     * Drafts are excluded by the query rather than by a role check, so an unpublished notice is
     * invisible to everybody including the person who wrote it.
     */
    @GetMapping("/announcements")
    @PreAuthorize("isAuthenticated()")
    public PagedResponse<AnnouncementService.Announcement> live() {
        // Filtered to the caller's audience, with their own seen marks.
        return PagedResponse.of(announcements.listLiveFor(currentUser.requireId()));
    }

    /** @param kind {@code view} or {@code click} */
    public record EngagementRequest(@NotBlank(message = "REQUIRED") String kind) {}

    /** A customer saw a post, or pressed its button. Counted once per person each. */
    @PostMapping("/announcements/{id}/engagement")
    @PreAuthorize("isAuthenticated()")
    public void engagement(@PathVariable UUID id, @Valid @RequestBody EngagementRequest body) {
        announcements.recordEngagement(id, currentUser.requireId(), body.kind());
    }

    // ------------------------------------------------------------------ administration

    @GetMapping("/admin/announcements")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<AnnouncementService.Announcement> all() {
        return PagedResponse.of(announcements.listAll());
    }

    @GetMapping("/admin/announcements/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AnnouncementService.Announcement one(@PathVariable UUID id) {
        return announcements.get(id);
    }

    public record AnnouncementRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 200) String title,
            @Size(max = 300) String subtitle,
            @NotBlank(message = "REQUIRED") String body,
            UUID imageId,
            Instant expiresAt,
            /** news, offer, new_arrival or event; null for news */
            @Size(max = 16) String category,
            /** shown in the banner at the top of the customer's dashboard */
            boolean featured,
            /** everyone or members; null for members */
            @Size(max = 16) String audience,
            @Size(max = 40) String ctaLabel,
            /** a portal page by name — see AnnouncementService.CTA_TARGETS */
            @Size(max = 24) String ctaTarget) {

        AnnouncementService.Fields fields() {
            return new AnnouncementService.Fields(
                    title, subtitle, body, imageId, expiresAt, category, featured, audience,
                    ctaLabel, ctaTarget);
        }
    }

    @PostMapping("/admin/announcements")
    @PreAuthorize("hasRole('ADMIN')")
    public AnnouncementService.Announcement create(@Valid @RequestBody AnnouncementRequest body) {
        UUID id = announcements.create(body.fields(), currentUser.requireId());
        return announcements.get(id);
    }

    @PutMapping("/admin/announcements/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AnnouncementService.Announcement update(
            @PathVariable UUID id, @Valid @RequestBody AnnouncementRequest body) {
        announcements.update(id, body.fields());
        return announcements.get(id);
    }

    /** Sends it to every active customer. One notification each, once. */
    @PostMapping("/admin/announcements/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public AnnouncementService.Announcement publish(@PathVariable UUID id) {
        announcements.publish(id);
        return announcements.get(id);
    }

    /** Stops showing it, without deleting what was said. */
    @PostMapping("/admin/announcements/{id}/withdraw")
    @PreAuthorize("hasRole('ADMIN')")
    public AnnouncementService.Announcement withdraw(@PathVariable UUID id) {
        announcements.withdraw(id);
        return announcements.get(id);
    }

    @DeleteMapping("/admin/announcements/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void delete(@PathVariable UUID id) {
        announcements.delete(id);
    }

    // ------------------------------------------------------------------ pictures

    /**
     * Uploads a picture for an announcement.
     *
     * <p>Through the same vault as every other upload, so it gets the same magic-byte check, the
     * same re-encode that strips metadata, and the same size limit for free. The kind is fixed to
     * {@code announcement} here rather than taken from the request — otherwise this endpoint would
     * be a way to store a document under a kind it is not, and the serving rule below depends on
     * the kind being true.
     */
    @PostMapping("/admin/announcements/image")
    @PreAuthorize("hasRole('ADMIN')")
    public StoredDocument uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            return vault.store(
                    file.getBytes(),
                    file.getContentType(),
                    "announcement",
                    currentUser.requireId());
        } catch (IOException unreadable) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "UPLOAD_UNREADABLE", "That file could not be read.");
        }
    }

    /**
     * Serves a picture, with no token.
     *
     * <p>Every other document in the vault is reached through a single-use token and every access
     * is logged, because every other document is somebody's NIC or bank slip. An announcement
     * picture is the opposite kind of thing — it is meant to be seen by everybody — and putting it
     * behind the token machinery would mean issuing one per image per page load.
     *
     * <p><b>The kind check is what keeps the two apart.</b> This endpoint refuses anything that is
     * not an announcement image, so a NIC scan can never be fetched here even with its id: it does
     * not carry that kind, and {@link #uploadImage} is the only thing that writes it.
     */
    @GetMapping("/announcements/image/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        StoredDocument document =
                vault.find(id)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "IMAGE_NOT_FOUND",
                                                "No such image."));

        ServedDocument served = vault.readPublic(id, "announcement");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                // Immutable: the vault never rewrites a stored object, so an id always names the
                // same bytes. A year means the browser asks once and the portal home page does not
                // re-fetch the same picture on every visit.
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                .body(served.content());
    }
}
