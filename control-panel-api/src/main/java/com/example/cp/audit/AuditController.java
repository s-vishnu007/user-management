package com.example.cp.audit;

import com.example.cp.common.ApiException;
import com.example.cp.common.PageRequestParams;
import com.example.cp.common.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final AuditLogRepository repo;

    public AuditController(AuditLogRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('audit.read')")
    public PagedResponse<AuditLogDto> globalSearch(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable p = PageRequestParams.of(page, size, null); // ORDER BY occurred_at DESC fixed in the native query
        Page<AuditLog> result = repo.search(action, actor, targetType, targetId, from, to, p);
        return PagedResponse.of(result.map(AuditLogDto::from).getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @GetMapping("/orgs/{orgId}/audit")
    @PreAuthorize("hasAuthority('audit.read') or @orgAccess.isOwnerOrAdmin(#orgId)")
    public PagedResponse<AuditLogDto> orgSearch(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (orgId == null) throw ApiException.badRequest("orgId required");
        Pageable p = PageRequestParams.of(page, size, null); // ORDER BY occurred_at DESC fixed in the native query
        Page<AuditLog> result = repo.searchForOrg(orgId, action, actor, targetType, targetId, from, to, p);
        return PagedResponse.of(result.map(AuditLogDto::from).getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record AuditLogDto(
            UUID id,
            UUID actorUserId,
            UUID actorOrgId,
            String action,
            String targetType,
            String targetId,
            String payloadJson,
            String ipAddress,
            OffsetDateTime occurredAt,
            AuditOutcome outcome
    ) {
        static AuditLogDto from(AuditLog a) {
            return new AuditLogDto(
                    a.getId(), a.getActorUserId(), a.getActorOrgId(),
                    a.getAction(), a.getTargetType(), a.getTargetId(),
                    a.getPayloadJson(), a.getIpAddress(), a.getOccurredAt(), a.getOutcome());
        }
    }
}
