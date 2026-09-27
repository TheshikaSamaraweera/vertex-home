package com.democode.mlmsittu.identity.internal.web;

import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.identity.internal.web.dto.UserSummary;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import com.democode.mlmsittu.shared.storage.api.ServedDocument;
import com.democode.mlmsittu.shared.storage.api.StoredDocument;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Profile photographs — everybody's, staff and customers alike.
 *
 * <p>Stored in the same vault as every other upload, so a photograph gets the magic-byte check,
 * the re-encode that strips metadata — including the GPS coordinates a phone writes into a picture
 * — and the size limit for free.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("isAuthenticated()")
public class ProfilePhotoController {

    private final DocumentVault vault;
    private final AppUserRepository users;
    private final CurrentUser currentUser;

    public ProfilePhotoController(
            DocumentVault vault, AppUserRepository users, CurrentUser currentUser) {
        this.vault = vault;
        this.users = users;
        this.currentUser = currentUser;
    }

    /**
     * Uploads or replaces your own photograph.
     *
     * <p>Your own, always — the id comes from the session rather than the request, so there is no
     * shape of this call that sets somebody else's picture. An administrator who needs to remove
     * an inappropriate one does it through the user record, not by posting here.
     */
    @PostMapping("/me/photo")
    @Transactional
    public UserSummary uploadMyPhoto(@RequestParam("file") MultipartFile file) {
        UUID userId = currentUser.requireId();

        StoredDocument stored;
        try {
            stored = vault.store(file.getBytes(), file.getContentType(), "profile", userId);
        } catch (IOException unreadable) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "UPLOAD_UNREADABLE", "That file could not be read.");
        }

        AppUser user = users.findById(userId).orElseThrow(this::noSuchUser);
        // The old document is left in the vault rather than deleted. Storage is cheap, and a
        // delete here would be the one write in this application that destroys evidence — the
        // access log for that document would point at something that no longer exists.
        user.setProfilePhotoId(stored.id());
        return UserSummary.from(users.saveAndFlush(user));
    }

    @DeleteMapping("/me/photo")
    @Transactional
    public UserSummary removeMyPhoto() {
        AppUser user = users.findById(currentUser.requireId()).orElseThrow(this::noSuchUser);
        user.setProfilePhotoId(null);
        return UserSummary.from(users.saveAndFlush(user));
    }

    /**
     * Serves a photograph to anybody signed in.
     *
     * <p>No token, unlike a NIC scan — a profile picture is shown beside a name in a list, and a
     * single-use token per face per page load would be absurd. The kind check is what keeps the
     * two apart: this refuses anything that is not a profile photograph, so an identity document
     * can never be fetched here even by somebody holding its id.
     *
     * <p>Signed in, though. These are photographs of real people and they do not belong to the
     * open internet.
     */
    @GetMapping("/photo/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable UUID id) {
        StoredDocument document =
                vault.find(id)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "PHOTO_NOT_FOUND", "No such photograph."));

        ServedDocument served = vault.readPublic(id, "profile");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                // The vault never rewrites a stored object, so an id always names the same bytes.
                // Private, because it is a photograph of somebody: a shared cache must not hold it.
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                .body(served.content());
    }

    private NotFoundException noSuchUser() {
        return new NotFoundException("USER_NOT_FOUND", "No such account.");
    }
}
