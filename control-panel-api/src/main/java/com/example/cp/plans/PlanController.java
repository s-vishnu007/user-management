package com.example.cp.plans;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<PlanDto> list(@RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly) {
        return planService.listWithDetails(activeOnly);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public PlanDto get(@PathVariable UUID id) {
        return planService.getPlanWithDetails(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('plan.write')")
    public ResponseEntity<PlanDto> create(@Valid @RequestBody CreatePlanRequest body) {
        Plan p = planService.createPlan(
                body.code(), body.name(), body.description(), body.tier(),
                body.defaultTtlDays(), body.active(),
                body.permissions(), body.features()
        );
        return ResponseEntity.status(201).body(planService.getPlanWithDetails(p.getId()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('plan.write')")
    public PlanDto patch(@PathVariable UUID id, @Valid @RequestBody UpdatePlanRequest body) {
        planService.updatePlan(id, body.name(), body.description(), body.tier(),
                body.defaultTtlDays(), body.active());
        return planService.getPlanWithDetails(id);
    }

    @PostMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('plan.write')")
    public PlanDto replacePermissions(@PathVariable UUID id, @Valid @RequestBody ReplacePermissionsRequest body) {
        planService.replacePermissions(id, body.permissionCodes());
        return planService.getPlanWithDetails(id);
    }

    @PostMapping("/{id}/features")
    @PreAuthorize("hasAuthority('plan.write')")
    public PlanDto replaceFeatures(@PathVariable UUID id, @RequestBody ReplaceFeaturesRequest body) {
        planService.replaceFeatures(id, body.features());
        return planService.getPlanWithDetails(id);
    }

    public record CreatePlanRequest(
            @NotBlank @Size(max = 64) String code,
            @Size(max = 255) String name,
            String description,
            @Size(max = 32) String tier,
            Integer defaultTtlDays,
            Boolean active,
            List<String> permissions,
            Map<String, Object> features
    ) {}

    public record UpdatePlanRequest(
            @Size(max = 255) String name,
            String description,
            @Size(max = 32) String tier,
            Integer defaultTtlDays,
            Boolean active
    ) {}

    public record ReplacePermissionsRequest(@Valid List<String> permissionCodes) {}

    public record ReplaceFeaturesRequest(Map<String, Object> features) {}
}
