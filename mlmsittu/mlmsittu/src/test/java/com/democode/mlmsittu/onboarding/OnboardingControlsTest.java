package com.democode.mlmsittu.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.hierarchy.internal.DistributorService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.onboarding.internal.nic.NicProtection;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationService;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationService.SubmissionRequest;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import com.democode.mlmsittu.shared.storage.internal.UploadSanitiser;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** The six Gate 4 conditions, plus NIC protection. */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Onboarding controls")
class OnboardingControlsTest {

    @Autowired private RegistrationService registrations;
    @Autowired private DistributorService distributors;
    @Autowired private DocumentVault documents;
    @Autowired private UploadSanitiser sanitiser;
    @Autowired private NicProtection nic;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    // ==============================================================================
    // Uploads
    // ==============================================================================

    @Test
    @DisplayName("Gate 4 · an executable renamed to .jpg is rejected on its bytes")
    void renamedExecutableIsRejected() {
        // "MZ" — a Windows PE header. The filename and declared type both claim JPEG.
        byte[] executable = new byte[] {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> sanitiser.sanitise(executable, "image/jpeg"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    @DisplayName("Gate 4 · metadata does not survive storage")
    void metadataIsStrippedByReEncoding() throws Exception {
        byte[] withTrailingPayload = jpegWithAppendedBytes();

        var clean = sanitiser.sanitise(withTrailingPayload, "image/jpeg");

        assertThat(clean.contentType()).isEqualTo("image/jpeg");
        assertThat(new String(clean.content(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("anything appended to the original must not survive a decode/encode round trip")
                .doesNotContain("SECRET-GPS-PAYLOAD");

        // Still a usable image afterwards — stripping must not corrupt the document.
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(clean.content()))).isNotNull();
    }

    @Test
    @DisplayName("files over 10 MB are refused before decoding")
    void oversizeUploadIsRejected() {
        byte[] tooBig = new byte[UploadSanitiser.MAX_BYTES + 1];
        tooBig[0] = (byte) 0xFF;
        tooBig[1] = (byte) 0xD8;
        tooBig[2] = (byte) 0xFF;

        assertThatThrownBy(() -> sanitiser.sanitise(tooBig, "image/jpeg"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("FILE_TOO_LARGE"));
    }

    @Test
    @DisplayName("Gate 4 · a document token works once and then never again")
    void accessTokenIsSingleUse() throws Exception {
        UUID reviewer = newUser("reviewer");
        UUID applicant = newUser("applicant");

        var stored = documents.store(smallJpeg(), "image/jpeg", "nic", applicant);
        String token = documents.issueAccessToken(stored.id(), reviewer, applicant, "127.0.0.1");

        assertThat(documents.redeem(token, "127.0.0.1").content()).isNotEmpty();

        assertThatThrownBy(() -> documents.redeem(token, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("DOCUMENT_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("every document access is logged, authorisation and delivery separately")
    void documentAccessIsLogged() throws Exception {
        UUID reviewer = newUser("reviewer");
        UUID applicant = newUser("applicant");

        var stored = documents.store(smallJpeg(), "image/jpeg", "nic", applicant);
        String token = documents.issueAccessToken(stored.id(), reviewer, applicant, "10.0.0.9");
        documents.redeem(token, "10.0.0.9");

        var actions =
                jdbc.queryForList(
                        "SELECT action FROM document_access_log WHERE document_id = ? ORDER BY id",
                        String.class,
                        stored.id());

        assertThat(actions).containsExactly("upload", "view_authorised", "view_served");
    }

    // ==============================================================================
    // NIC protection
    // ==============================================================================

    @Test
    @DisplayName("P4-07 · the NIC is unreadable at rest but still recoverable and matchable")
    void nicIsHashedAndEncrypted() {
        String number = "199012345678";

        byte[] hash = nic.hash(number);
        byte[] encrypted = nic.encrypt(number);

        assertThat(new String(hash, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(number);
        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(number);

        // Deterministic, so it can back a uniqueness index...
        assertThat(nic.hash(number)).isEqualTo(hash);
        assertThat(nic.hash("  199012345678  ")).as("normalised first").isEqualTo(hash);
        assertThat(nic.hash("199012345679")).isNotEqualTo(hash);

        // ...while encryption is randomised, so two records of the same NIC are not obviously
        // the same to anyone reading the table.
        assertThat(nic.encrypt(number)).isNotEqualTo(encrypted);
        assertThat(nic.decrypt(encrypted)).isEqualTo(number);
        assertThat(nic.last4(number)).isEqualTo("5678");
    }

    @Test
    @DisplayName("a tampered ciphertext fails rather than decrypting to something plausible")
    void tamperedCiphertextIsRejected() {
        byte[] encrypted = nic.encrypt("199012345678");
        encrypted[encrypted.length - 1] ^= 0x01;

        assertThatThrownBy(() -> nic.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    // ==============================================================================
    // Review queue
    // ==============================================================================

    @Test
    @DisplayName("Gate 4 · two reviewers cannot both act on one record")
    void claimLockingIsExclusive() {
        var scenario = submittedRegistration();
        UUID first = newUser("reviewer-a");
        UUID second = newUser("reviewer-b");

        registrations.claim(scenario.registrationId(), first);

        assertThatThrownBy(() -> registrations.claim(scenario.registrationId(), second))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("REGISTRATION_ALREADY_CLAIMED"));

        // And the unclaimed reviewer cannot decide it either — claiming is not merely advisory.
        assertThatThrownBy(() -> registrations.approve(scenario.registrationId(), second))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("NOT_YOUR_CLAIM"));

        registrations.release(scenario.registrationId(), first);
        assertThat(registrations.claim(scenario.registrationId(), second).claimedBy())
                .as("released records become available again")
                .isEqualTo(second);
    }

    @Test
    @DisplayName("Gate 4 · the submitter cannot review their own registration")
    void selfReviewIsBlocked() {
        var scenario = submittedRegistration();

        assertThatThrownBy(() -> registrations.claim(scenario.registrationId(), scenario.applicantId()))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("SELF_REVIEW_FORBIDDEN"));

        UUID other = newUser("reviewer");
        registrations.claim(scenario.registrationId(), other);
        assertThat(registrations.approve(scenario.registrationId(), other).businessId()).isNotBlank();
    }

    @Test
    @DisplayName("P4-13 · approval allocates Business ID, path and status together")
    void approvalIsAtomic() {
        var scenario = submittedRegistration();
        UUID reviewer = newUser("reviewer");

        registrations.claim(scenario.registrationId(), reviewer);
        var result = registrations.approve(scenario.registrationId(), reviewer);

        var row =
                jdbc.queryForMap(
                        """
                        SELECT business_id, path::text AS path, status, approved_at
                        FROM distributor WHERE id = ?
                        """,
                        result.distributorId());

        assertThat(row.get("business_id")).isEqualTo(result.businessId());
        assertThat(row.get("path")).isNotNull();
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(row.get("approved_at")).isNotNull();
        assertThat(registrations.get(scenario.registrationId()).status()).isEqualTo("approved");
    }

    // ==============================================================================
    // NIC re-registration (Gate 4, flow F-06)
    // ==============================================================================

    @Test
    @DisplayName("Gate 4 · the same NIC is refused while live, and allowed after deletion")
    void nicReRegistrationCycle() {
        String sharedNic = "1990" + System.nanoTime() % 100_000_000L;
        var first = submittedRegistration(sharedNic);

        // 2. A second registration with the same NIC is refused.
        UUID secondApplicant = newUser("applicant-2");
        assertThatThrownBy(() -> submitFor(secondApplicant, sharedNic, first.referrerBusinessId()))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("NIC_ALREADY_REGISTERED"));

        // 3. Delete the first account — soft, so the history survives.
        jdbc.update(
                "UPDATE identity_document SET deleted_at = now() WHERE user_id = ?",
                first.applicantId());

        // 4. The same NIC may now register again.
        UUID registrationId = submitFor(secondApplicant, sharedNic, first.referrerBusinessId());
        assertThat(registrationId).isNotNull();

        // 5. The old record is still there, marked deleted — nothing was erased.
        Integer historical =
                jdbc.queryForObject(
                        "SELECT count(*) FROM identity_document WHERE user_id = ? AND deleted_at IS NOT NULL",
                        Integer.class,
                        first.applicantId());
        assertThat(historical).isEqualTo(1);

        Integer live =
                jdbc.queryForObject(
                        "SELECT count(*) FROM identity_document WHERE nic_hash = ? AND deleted_at IS NULL",
                        Integer.class,
                        (Object) nic.hash(sharedNic));
        assertThat(live).as("exactly one live record per NIC").isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures

    private record Scenario(UUID applicantId, UUID registrationId, String referrerBusinessId) {}

    private Scenario submittedRegistration() {
        return submittedRegistration("1990" + System.nanoTime() % 100_000_000L);
    }

    private Scenario submittedRegistration(String nicNumber) {
        UUID referrerUser = newUser("referrer");
        UUID referrerDistributor = distributors.createPending(referrerUser, null);
        String referrerBusinessId = distributors.attachToReferrer(referrerDistributor, null);

        UUID applicant = newUser("applicant");
        UUID registrationId = submitFor(applicant, nicNumber, referrerBusinessId);
        return new Scenario(applicant, registrationId, referrerBusinessId);
    }

    @Test
    @DisplayName("a registration with no referrer becomes a root, and its ID seats the first five")
    void aRegistrationWithNoReferrerBecomesARoot() {
        // Somebody has to be first. Until this existed the network could not be started at all
        // without editing the database: registration demanded an active referrer, and on an empty
        // system there was none to name.
        UUID applicant = newUser("root-applicant");
        UUID registrationId = submitFor(applicant, uniqueNic(), null);
        UUID reviewer = newUser("root-reviewer");
        registrations.claim(registrationId, reviewer);

        String businessId = registrations.approve(registrationId, reviewer).businessId();

        assertThat(businessId)
                .as("a root identifier: no seat digits, so nothing sits above this person")
                .matches("^[1-9][06-9]*$");

        // And it is usable as a referrer straight away — which is the entire point of allowing it.
        UUID child = newUser("first-recruit");
        UUID childRegistration = submitFor(child, uniqueNic(), businessId);
        UUID childReviewer = newUser("child-reviewer");
        registrations.claim(childRegistration, childReviewer);

        assertThat(registrations.approve(childRegistration, childReviewer).businessId())
                .as("seat 1 under the root")
                .isEqualTo(businessId + "1");
    }

    @Test
    @DisplayName("every root gets a different identifier")
    void rootsDoNotCollide() {
        String first = newRootBusinessId();
        String second = newRootBusinessId();
        assertThat(first).isNotEqualTo(second);
        assertThat(second).matches("^[1-9][06-9]*$");
    }

    private String newRootBusinessId() {
        UUID applicant = newUser("root");
        UUID registrationId = submitFor(applicant, uniqueNic(), null);
        UUID reviewer = newUser("reviewer");
        registrations.claim(registrationId, reviewer);
        return registrations.approve(registrationId, reviewer).businessId();
    }

    /** NIC numbers are unique across the table, and the test database is not reset between runs. */
    private static String uniqueNic() {
        return String.valueOf(200000000000L + NIC_SEQUENCE.incrementAndGet());
    }

    private static final java.util.concurrent.atomic.AtomicLong NIC_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(
                    new java.security.SecureRandom().nextInt(100_000_000));

    private UUID submitFor(UUID applicant, String nicNumber, String referrerBusinessId) {
        var nicDoc = documents.store(smallJpeg(), "image/jpeg", "nic", applicant);
        var slipDoc = documents.store(smallJpeg(), "image/jpeg", "bank_slip", applicant);

        return registrations.submit(
                applicant,
                new SubmissionRequest(
                        nicNumber,
                        nicDoc.id(),
                        slipDoc.id(),
                        referrerBusinessId,
                        "12 Galle Road, Colombo 03",
                        "Commercial Bank",
                        "Colombo",
                        "1234567890",
                        null));
    }

    private UUID newUser(String prefix) {
        AppUser user = new AppUser();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Onboarding fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }

    private static byte[] smallJpeg() {
        try {
            BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(Color.DARK_GRAY);
            graphics.fillRect(0, 0, 24, 24);
            graphics.dispose();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A valid JPEG with extra bytes appended — standing in for metadata a camera would embed. */
    private static byte[] jpegWithAppendedBytes() {
        byte[] jpeg = smallJpeg();
        byte[] payload = "SECRET-GPS-PAYLOAD".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] combined = new byte[jpeg.length + payload.length];
        System.arraycopy(jpeg, 0, combined, 0, jpeg.length);
        System.arraycopy(payload, 0, combined, jpeg.length, payload.length);
        return combined;
    }
}
