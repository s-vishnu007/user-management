package com.example.cp.plans;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PlanPermissionRepository extends JpaRepository<PlanPermission, PlanPermission.Pk> {

    List<PlanPermission> findByIdPlanId(UUID planId);

    void deleteByIdPlanId(UUID planId);
}
