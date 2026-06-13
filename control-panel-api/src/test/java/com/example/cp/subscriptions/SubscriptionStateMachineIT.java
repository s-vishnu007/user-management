package com.example.cp.subscriptions;

import com.example.cp.common.ApiException;
import com.example.cp.orgs.Organization;
import com.example.cp.plans.Plan;
import com.example.cp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit P2: the subscription state machine must enforce an allowed-transition matrix — CANCELLED is
 * terminal, so {@code cancel -> suspend -> reactivate} can no longer resurrect a cancelled
 * subscription. Illegal transitions are rejected with 409.
 */
class SubscriptionStateMachineIT extends AbstractIntegrationTest {

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subRepo;

    @Test
    void cancelled_isTerminal_cannotBeSuspendedOrReactivated() {
        Organization org = seedOrg("SM Org " + rnd());
        Plan plan = seedNewPlan("smplan-" + rnd(), 365);
        var sub = seedSubscription(org.getId(), plan.getId());

        subscriptionService.cancel(sub.getId(), "done");
        assertThat(subRepo.findById(sub.getId()).orElseThrow().getStatus())
                .isEqualTo(Subscription.Status.CANCELLED);

        // CANCELLED -> SUSPENDED is illegal (409).
        assertThatThrownBy(() -> subscriptionService.suspend(sub.getId(), "x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // CANCELLED -> ACTIVE (reactivate) is illegal (409). Previously this resurrected it.
        assertThatThrownBy(() -> subscriptionService.reactivate(sub.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Still terminal.
        assertThat(subRepo.findById(sub.getId()).orElseThrow().getStatus())
                .isEqualTo(Subscription.Status.CANCELLED);
    }

    @Test
    void suspend_thenReactivate_isAllowed() {
        Organization org = seedOrg("SM2 Org " + rnd());
        Plan plan = seedNewPlan("sm2plan-" + rnd(), 365);
        var sub = seedSubscription(org.getId(), plan.getId());

        subscriptionService.suspend(sub.getId(), "temp");
        assertThat(subRepo.findById(sub.getId()).orElseThrow().getStatus())
                .isEqualTo(Subscription.Status.SUSPENDED);

        subscriptionService.reactivate(sub.getId());
        assertThat(subRepo.findById(sub.getId()).orElseThrow().getStatus())
                .isEqualTo(Subscription.Status.ACTIVE);
    }

    @Test
    void duplicateOverrideKey_upsertsRatherThanDuplicates() {
        Organization org = seedOrg("OV Org " + rnd());
        Plan plan = seedNewPlan("ovplan-" + rnd(), 365);
        var sub = seedSubscription(org.getId(), plan.getId());

        subscriptionService.addOverride(sub.getId(),
                new SubscriptionService.OverrideRequest("FEATURE", "max_widgets", 10));
        subscriptionService.addOverride(sub.getId(),
                new SubscriptionService.OverrideRequest("FEATURE", "max_widgets", 25));

        var overrides = subscriptionService.listOverrides(sub.getId()).stream()
                .filter(o -> o.getType() == SubscriptionOverride.Type.FEATURE && o.getKey().equals("max_widgets"))
                .toList();
        // Exactly one row for (sub, FEATURE, max_widgets), carrying the latest value.
        assertThat(overrides).hasSize(1);
        assertThat(overrides.get(0).getValueJson()).contains("25");
    }
}
