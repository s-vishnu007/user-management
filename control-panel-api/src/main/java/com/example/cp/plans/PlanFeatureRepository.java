package com.example.cp.plans;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PlanFeatureRepository extends JpaRepository<PlanFeature, PlanFeature.Pk> {

    List<PlanFeature> findByIdPlanId(UUID planId);

    void deleteByIdPlanId(UUID planId);
}
