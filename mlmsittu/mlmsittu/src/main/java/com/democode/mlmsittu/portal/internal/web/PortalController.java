package com.democode.mlmsittu.portal.internal.web;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.portal.api.PortalView;
import com.democode.mlmsittu.portal.internal.PortalService;
import com.democode.mlmsittu.shared.api.PagedResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final PortalService portal;
    private final CurrentUser currentUser;

    public PortalController(PortalService portal, CurrentUser currentUser) {
        this.portal = portal;
        this.currentUser = currentUser;
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
