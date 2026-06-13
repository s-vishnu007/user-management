package com.example.cp.sso;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/sso")
public class SsoController {

    private final SsoService service;

    public SsoController(SsoService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('sso.read') or @orgAccess.isOwnerOrAdmin(#orgId)")
    public List<SsoDto> list(@PathVariable UUID orgId) {
        return service.listForOrg(orgId).stream().map(SsoDto::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sso.write') or @orgAccess.isOwnerOrAdmin(#orgId)")
    public SsoDto create(@PathVariable UUID orgId, @Valid @RequestBody CreateRequest body) {
        SsoProvider saved = service.create(orgId, body.type(), body.config());
        return SsoDto.from(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('sso.write') or @orgAccess.isOwnerOrAdmin(#orgId)")
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        // Scope by (id, orgId): proving the caller manages the PATH org is not enough — the resource
        // must also belong to that org, or an admin of org A could delete org B's provider by id (IDOR).
        service.delete(id, orgId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('sso.write') or @orgAccess.isOwnerOrAdmin(#orgId)")
    public SsoService.TestResult test(@PathVariable UUID orgId, @PathVariable UUID id) {
        // Scope by (id, orgId) for the same cross-tenant reason as delete().
        return service.test(id, orgId);
    }

    public record CreateRequest(@NotNull SsoProvider.Type type, @NotNull Map<String, Object> config) {}

    public record SsoDto(UUID id, UUID orgId, SsoProvider.Type type, String config, boolean enabled, OffsetDateTime createdAt) {
        static SsoDto from(SsoProvider p) {
            return new SsoDto(p.getId(), p.getOrgId(), p.getType(), p.getConfigJson(), p.isEnabled(), p.getCreatedAt());
        }
    }
}
