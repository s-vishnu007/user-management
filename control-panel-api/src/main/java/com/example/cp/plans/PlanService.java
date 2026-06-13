package com.example.cp.plans;

import com.example.cp.common.ApiException;
import com.example.cp.common.AuditContext;
import com.example.cp.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class PlanService {

    private final PlanRepository planRepo;
    private final PlanPermissionRepository permRepo;
    private final PlanFeatureRepository featureRepo;
    private final ObjectMapper objectMapper;

    public PlanService(PlanRepository planRepo,
                       PlanPermissionRepository permRepo,
                       PlanFeatureRepository featureRepo,
                       ObjectMapper objectMapper) {
        this.planRepo = planRepo;
        this.permRepo = permRepo;
        this.featureRepo = featureRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Plan createPlan(String code, String name, String description, String tier,
                           Integer defaultTtlDays, Boolean active,
                           Collection<String> permissions, Map<String, Object> features) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("Plan code is required");
        }
        if (planRepo.existsByCode(code)) {
            throw ApiException.conflict("A plan with that code already exists");
        }
        Plan p = Plan.builder()
                .id(Ids.newId())
                .code(code)
                .name(name)
                .description(description)
                .tier(tier == null ? code : tier)
                .active(active == null ? true : active)
                .defaultTtlDays(defaultTtlDays == null ? 365 : defaultTtlDays)
                .createdAt(OffsetDateTime.now())
                .build();
        Plan saved = planRepo.save(p);
        if (permissions != null) {
            replacePermissions(saved.getId(), permissions);
        }
        if (features != null) {
            replaceFeatures(saved.getId(), features);
        }
        AuditContext.set("plan.created");
        AuditContext.setTarget("plan", saved.getId().toString());
        return saved;
    }

    @Transactional
    public Plan updatePlan(UUID id, String name, String description, String tier,
                           Integer defaultTtlDays, Boolean active) {
        Plan p = planRepo.findById(id).orElseThrow(() -> ApiException.notFound("Plan not found"));
        if (name != null) p.setName(name);
        if (description != null) p.setDescription(description);
        if (tier != null) p.setTier(tier);
        if (defaultTtlDays != null) p.setDefaultTtlDays(defaultTtlDays);
        if (active != null) p.setActive(active);
        Plan saved = planRepo.save(p);
        AuditContext.set("plan.updated");
        AuditContext.setTarget("plan", id.toString());
        return saved;
    }

    @Transactional
    public void replacePermissions(UUID planId, Collection<String> permissionCodes) {
        // Defense in depth behind the controller's @NotNull: a null list must never be silently
        // treated as "delete everything" (the P0-2 data-loss bug). An empty list is a legitimate
        // "clear all permissions" request and is allowed through.
        if (permissionCodes == null) {
            throw ApiException.badRequest("permissionCodes is required (send an empty array to clear all permissions)");
        }
        planRepo.findById(planId).orElseThrow(() -> ApiException.notFound("Plan not found"));
        permRepo.deleteByIdPlanId(planId);
        permRepo.flush();
        Set<String> uniq = new TreeSet<>(permissionCodes);
        List<PlanPermission> rows = new ArrayList<>(uniq.size());
        for (String code : uniq) {
            if (code == null || code.isBlank()) continue;
            rows.add(PlanPermission.builder()
                    .id(PlanPermission.Pk.builder().planId(planId).permissionCode(code.trim()).build())
                    .build());
        }
        permRepo.saveAll(rows);
        AuditContext.set("plan.permissions.replaced");
        AuditContext.setTarget("plan", planId.toString());
    }

    @Transactional
    public void replaceFeatures(UUID planId, Map<String, Object> features) {
        planRepo.findById(planId).orElseThrow(() -> ApiException.notFound("Plan not found"));
        featureRepo.deleteByIdPlanId(planId);
        featureRepo.flush();
        if (features == null) return;
        List<PlanFeature> rows = new ArrayList<>(features.size());
        for (Map.Entry<String, Object> e : features.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            try {
                String json = objectMapper.writeValueAsString(e.getValue());
                rows.add(PlanFeature.builder()
                        .id(PlanFeature.Pk.builder().planId(planId).featureKey(e.getKey()).build())
                        .valueJson(json)
                        .build());
            } catch (JsonProcessingException ex) {
                throw ApiException.badRequest("Invalid feature value for " + e.getKey());
            }
        }
        featureRepo.saveAll(rows);
        AuditContext.set("plan.features.replaced");
        AuditContext.setTarget("plan", planId.toString());
    }

    @Transactional(readOnly = true)
    public List<Plan> listPlans(boolean onlyActive) {
        return onlyActive ? planRepo.findAllByActiveTrue() : planRepo.findAll();
    }

    @Transactional(readOnly = true)
    public List<Plan> listPlans(boolean onlyActive, Pageable pageable) {
        return (onlyActive
                ? planRepo.findAllByActiveTrue(pageable)
                : planRepo.findAll(pageable)).getContent();
    }

    @Transactional(readOnly = true)
    public Plan get(UUID id) {
        return planRepo.findById(id).orElseThrow(() -> ApiException.notFound("Plan not found"));
    }

    @Transactional(readOnly = true)
    public Plan getByCode(String code) {
        return planRepo.findByCode(code).orElseThrow(() -> ApiException.notFound("Plan not found: " + code));
    }

    @Transactional(readOnly = true)
    public List<String> getPermissions(UUID planId) {
        return permRepo.findByIdPlanId(planId).stream()
                .map(pp -> pp.getId().getPermissionCode())
                .sorted()
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getFeatures(UUID planId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PlanFeature f : featureRepo.findByIdPlanId(planId)) {
            String json = f.getValueJson();
            try {
                Object v = json == null ? null : objectMapper.readValue(json, Object.class);
                out.put(f.getId().getFeatureKey(), v);
            } catch (Exception ex) {
                out.put(f.getId().getFeatureKey(), json);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PlanDto getPlanWithDetails(UUID id) {
        Plan p = get(id);
        return new PlanDto(
                p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getTier(),
                p.isActive(), p.getDefaultTtlDays(), p.getCreatedAt(),
                getPermissions(p.getId()), getFeatures(p.getId())
        );
    }

    @Transactional(readOnly = true)
    public List<PlanDto> listWithDetails(boolean onlyActive) {
        return toDtos(listPlans(onlyActive));
    }

    @Transactional(readOnly = true)
    public List<PlanDto> listWithDetails(boolean onlyActive, Pageable pageable) {
        return toDtos(listPlans(onlyActive, pageable));
    }

    private List<PlanDto> toDtos(List<Plan> plans) {
        List<PlanDto> out = new ArrayList<>(plans.size());
        for (Plan p : plans) {
            out.add(new PlanDto(
                    p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getTier(),
                    p.isActive(), p.getDefaultTtlDays(), p.getCreatedAt(),
                    getPermissions(p.getId()), getFeatures(p.getId())
            ));
        }
        return out;
    }
}
