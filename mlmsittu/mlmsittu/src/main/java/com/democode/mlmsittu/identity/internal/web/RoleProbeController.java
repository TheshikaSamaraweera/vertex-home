package com.democode.mlmsittu.identity.internal.web;

import com.democode.mlmsittu.identity.internal.service.AuthenticatedUser;
import com.democode.mlmsittu.shared.api.PagedResponse;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint per role, so P1-04 can be verified before the real modules exist.
 *
 * <p><b>Scaffolding.</b> As Phases 2–6 land, each probe is replaced by the genuine endpoint that
 * role guards — {@code /probe/inventory} by the stock endpoints, {@code /probe/finance} by payment
 * verification, and so on. Delete this class once the last one has a real counterpart; leaving it
 * behind would mean shipping six endpoints that exist only to confirm authorisation works.
 */
@RestController
@RequestMapping("/api/v1/probe")
public class RoleProbeController {

    @GetMapping("/super-admin")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> superAdmin(@AuthenticationPrincipal AuthenticatedUser principal) {
        return granted("SUPER_ADMIN", principal);
    }

    @GetMapping("/kyc")
    @PreAuthorize("hasRole('KYC_REVIEWER')")
    public Map<String, Object> kyc(@AuthenticationPrincipal AuthenticatedUser principal) {
        return granted("KYC_REVIEWER", principal);
    }

    @GetMapping("/inventory")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public Map<String, Object> inventory(@AuthenticationPrincipal AuthenticatedUser principal) {
        return granted("INVENTORY_CLERK", principal);
    }

    @GetMapping("/procurement")
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public Map<String, Object> procurement(@AuthenticationPrincipal AuthenticatedUser principal) {
        return granted("PROCUREMENT_OFFICER", principal);
    }

    @GetMapping("/finance")
    @PreAuthorize("hasRole('FINANCE_OFFICER')")
    public Map<String, Object> finance(@AuthenticationPrincipal AuthenticatedUser principal) {
        return granted("FINANCE_OFFICER", principal);
    }

    @GetMapping("/support")
    @PreAuthorize("hasRole('SUPPORT_AGENT')")
    public Map<String, Object> support(@AuthenticationPrincipal AuthenticatedUser principal) {
        return granted("SUPPORT_AGENT", principal);
    }

    /**
     * Any authenticated role. Doubles as the P0-05 envelope check — confirm the body has both
     * {@code data} and {@code nextCursor}, and that {@code nextCursor} is null.
     */
    @GetMapping("/list")
    public PagedResponse<String> envelopeShape(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return PagedResponse.of(List.copyOf(principal.sortedRoleCodes()));
    }

    private Map<String, Object> granted(String role, AuthenticatedUser principal) {
        return Map.of(
                "granted", role,
                "user", principal.email(),
                "roles", principal.sortedRoleCodes());
    }
}
