package com.democode.mlmsittu.documents.internal.web;

import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import com.democode.mlmsittu.shared.storage.api.ServedDocument;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The two document operations that belong to no single feature: putting bytes in, and taking bytes
 * out against a token.
 *
 * <p>Both were on {@code OnboardingController} while KYC was the only thing storing files. Phase 5
 * adds bank slips, and a payment slip going through a route named after onboarding would be
 * confusing to everyone who read it afterwards. <b>Deciding who may see a document stays with the
 * owning module</b> — onboarding authorises NIC scans for reviewers, commerce authorises slips for
 * finance — and each issues its token at its own endpoint. Only redemption is shared, because by
 * then the token itself is the credential and the authorisation has already happened.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    /**
     * Kinds a caller is allowed to declare. Free text here would let an uploader file a bank slip
     * under {@code nic} and land it in the KYC review queue's storage prefix.
     */
    private static final Set<String> ACCEPTED_KINDS = Set.of("nic", "bank_slip", "other");

    private final DocumentVault vault;
    private final CurrentUser currentUser;

    public DocumentController(DocumentVault vault, CurrentUser currentUser) {
        this.vault = vault;
        this.currentUser = currentUser;
    }

    public record UploadResponse(UUID documentId, String contentType, long byteSize) {}

    /**
     * Accepts a file. Validated by magic bytes and re-encoded to strip metadata before anything is
     * written — see {@code UploadSanitiser}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(
            @RequestParam("file") MultipartFile file, @RequestParam("kind") String kind)
            throws IOException {

        String safeKind = ACCEPTED_KINDS.contains(kind) ? kind : "other";
        var stored =
                vault.store(file.getBytes(), file.getContentType(), safeKind, currentUser.requireId());
        return new UploadResponse(stored.id(), stored.contentType(), stored.byteSize());
    }

    /**
     * Exchanges a token for the file. Single use, 60 seconds.
     *
     * <p>Served as an attachment with {@code nosniff}: a stored PDF is never rendered in a page
     * context, so anything embedded in one has nowhere to execute.
     */
    @GetMapping("/{token}")
    public ResponseEntity<byte[]> download(
            @PathVariable String token, HttpServletRequest request) {

        ServedDocument served = vault.redeem(token, request.getRemoteAddr());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, served.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(served.content());
    }
}
