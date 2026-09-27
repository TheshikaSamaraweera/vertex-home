package com.democode.mlmsittu.identity.internal.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A person who can authenticate. Administrators today; distributors join in Phase 4 against the
 * same table, with their business registration held separately on {@code distributor}.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "mobile", length = 24)
    private String mobile;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** Argon2id encoding. Never logged, never returned by any endpoint. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "status", nullable = false, length = 24)
    private String status = UserStatus.UNVERIFIED.dbValue();

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "mobile_verified", nullable = false)
    private boolean mobileVerified;

    /** Base32 TOTP shared secret. Null until the user enrols. */
    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    /**
     * Set when an administrator issues a temporary password.
     *
     * <p>Sign-in succeeds and then goes nowhere but the change-password screen, so a password
     * somebody else chose — and wrote on a slip, and may have read out across a counter — never
     * becomes the password this account keeps.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    /** The picture shown beside this person's name. Null until they upload one. */
    @Column(name = "profile_photo_id")
    private java.util.UUID profilePhotoId;

    public java.util.UUID getProfilePhotoId() {
        return profilePhotoId;
    }

    public void setProfilePhotoId(java.util.UUID profilePhotoId) {
        this.profilePhotoId = profilePhotoId;
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Eager because every authenticated request needs the authorities, and the set is at most six
     * rows. This association stays inside the identity module — architecture §1.3 forbids JPA
     * associations that cross a module boundary.
     */
    @ManyToMany(fetch = FetchType.EAGER, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<AppRole> roles = new LinkedHashSet<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UserStatus statusValue() {
        return UserStatus.fromDbValue(status);
    }

    public void setStatusValue(UserStatus value) {
        this.status = value.dbValue();
    }

    /** True when any granted role demands TOTP before a session may be issued. */
    public boolean requiresMfa() {
        return roles.stream().anyMatch(AppRole::isRequiresMfa);
    }
}
