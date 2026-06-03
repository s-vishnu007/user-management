package com.example.cp.licenses;

import com.example.cp.common.TrustedProxyResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * License lease / heartbeat (phone-home) endpoint.
 *
 * <p><b>Authorization.</b> The heartbeat is intended to be called by the licensed app itself using
 * an org-scoped API key carrying the {@code usage.ingest} scope: the {@code @tenantAccess}
 * {@code canIngestUsageForJti} check resolves {@code jti -> subscription -> org} and requires the
 * caller's bound org to match (no cross-tenant heartbeat). For operator/console use a human org
 * member with read access to the license is also accepted ({@code canReadLicenseByJti}). Both paths
 * resolve the license's owning org via {@code TenantAccessChecker}, so a caller can never beat a
 * license belonging to another tenant.
 */
@RestController
@RequestMapping("/api/v1")
public class LicenseHeartbeatController {

    private final ActivationService activationService;
    private final TrustedProxyResolver proxyResolver;

    public LicenseHeartbeatController(ActivationService activationService,
                                      TrustedProxyResolver proxyResolver) {
        this.activationService = activationService;
        this.proxyResolver = proxyResolver;
    }

    @PostMapping("/licenses/{jti}/heartbeat")
    @PreAuthorize("(hasAuthority('usage.ingest') and @tenantAccess.canIngestUsageForJti(#jti)) "
            + "or @tenantAccess.canReadLicenseByJti(#jti)")
    public ResponseEntity<HeartbeatResponse> heartbeat(@PathVariable String jti,
                                                       @Valid @RequestBody HeartbeatRequest body,
                                                       HttpServletRequest request) {
        String clientIp = proxyResolver.resolveClientIp(request);
        ActivationService.HeartbeatResult result =
                activationService.heartbeat(jti, body.nodeId(), clientIp);
        boolean overLimit = result.seatLimit() != null
                && result.seatLimit() > 0
                && result.activeSeats() > result.seatLimit();
        return ResponseEntity.ok(new HeartbeatResponse(
                result.jti(), result.nodeId(), result.lastSeenAt(),
                result.activeSeats(), result.seatLimit(), overLimit,
                result.licenseType(), result.expiresAt()));
    }

    /** Lists current node activations for a license (active-seat view). usage.read or license read access. */
    @GetMapping("/licenses/{jti}/activations")
    @PreAuthorize("@tenantAccess.canReadLicenseByJti(#jti)")
    public ActivationsResponse activations(@PathVariable String jti) {
        long active = activationService.activeSeatCount(jti);
        List<ActivationDto> all = activationService.listActivations(jti).stream()
                .map(ActivationDto::from)
                .toList();
        return new ActivationsResponse(jti, active, all);
    }

    public record HeartbeatRequest(@NotBlank(message = "nodeId is required") String nodeId) {}

    public record HeartbeatResponse(
            String jti,
            String nodeId,
            OffsetDateTime lastSeenAt,
            long activeSeats,
            Integer seatLimit,
            boolean overLimit,
            String licenseType,
            OffsetDateTime expiresAt
    ) {}

    public record ActivationsResponse(String jti, long activeSeats, List<ActivationDto> activations) {}

    public record ActivationDto(
            String nodeId,
            OffsetDateTime firstSeenAt,
            OffsetDateTime lastSeenAt,
            String lastSeenIp
    ) {
        static ActivationDto from(LicenseActivation a) {
            return new ActivationDto(a.getNodeId(), a.getFirstSeenAt(), a.getLastSeenAt(), a.getLastSeenIp());
        }
    }
}
