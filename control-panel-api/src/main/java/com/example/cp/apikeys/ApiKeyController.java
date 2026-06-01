package com.example.cp.apikeys;

import com.example.cp.common.AuditContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/api-keys")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('apikey.write') or @orgAccess.isOwnerOrAdmin(#orgId)")
    public CreateResponse create(@PathVariable UUID orgId, @Valid @RequestBody CreateRequest body) {
        ApiKeyService.CreateResult result = service.create(orgId, body.name(), body.scopes());
        AuditContext.set("apikey.created");
        AuditContext.setTarget("api_key", result.apiKey().getId().toString());
        return CreateResponse.from(result);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('apikey.read') or @orgAccess.isMember(#orgId)")
    public List<ApiKeyDto> list(@PathVariable UUID orgId) {
        return service.listForOrg(orgId).stream().map(ApiKeyDto::from).toList();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('apikey.write') or @orgAccess.isOwnerOrAdmin(#orgId)")
    public ResponseEntity<Void> revoke(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.revoke(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(@Size(max = 255) String name, Set<String> scopes) {}

    public record CreateResponse(UUID id, String name, String key, String keyPrefix, Set<String> scopes, OffsetDateTime createdAt) {
        static CreateResponse from(ApiKeyService.CreateResult r) {
            return new CreateResponse(r.apiKey().getId(), r.apiKey().getName(), r.plaintextKey(),
                    r.apiKey().getKeyPrefix(), null, r.apiKey().getCreatedAt());
        }
    }

    public record ApiKeyDto(UUID id, UUID orgId, String name, String keyPrefix, String scopes, OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {
        static ApiKeyDto from(ApiKey k) {
            return new ApiKeyDto(k.getId(), k.getOrgId(), k.getName(), k.getKeyPrefix(),
                    k.getScopesJson(), k.getCreatedAt(), k.getLastUsedAt(), k.getRevokedAt());
        }
    }
}
