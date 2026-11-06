/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.advancly.fineract.portfolio.savings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.charge.domain.Charge;

@Entity
@Table(name = "m_adv_charge_interest_rule")
public class AdvanclyChargeInterestRule extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_id", nullable = false)
    private Charge charge;

    @Column(name = "interest_basis_mode_enum", nullable = false)
    private Integer interestBasisMode;

    @Column(name = "custom_period_reapply_policy_enum")
    private Integer customPeriodReapplyPolicy;

    protected AdvanclyChargeInterestRule() {
        //
    }

    private AdvanclyChargeInterestRule(final Charge charge, final InterestBasisMode interestBasisMode,
            final CustomPeriodReapplyPolicy customPeriodReapplyPolicy) {
        this.charge = charge;
        update(interestBasisMode, customPeriodReapplyPolicy);
    }

    public static AdvanclyChargeInterestRule createNew(final Charge charge, final InterestBasisMode interestBasisMode,
            final CustomPeriodReapplyPolicy customPeriodReapplyPolicy) {
        return new AdvanclyChargeInterestRule(charge, interestBasisMode, customPeriodReapplyPolicy);
    }

    public void update(final InterestBasisMode interestBasisMode, final CustomPeriodReapplyPolicy customPeriodReapplyPolicy) {
        this.interestBasisMode = Objects.requireNonNull(interestBasisMode, "interestBasisMode").getValue();
        this.customPeriodReapplyPolicy = customPeriodReapplyPolicy == null ? null : customPeriodReapplyPolicy.getValue();
    }

    public Charge charge() {
        return this.charge;
    }

    public Long chargeId() {
        return this.charge == null ? null : this.charge.getId();
    }

    public InterestBasisMode interestBasisMode() {
        return InterestBasisMode.fromInt(this.interestBasisMode);
    }

    public CustomPeriodReapplyPolicy customPeriodReapplyPolicy() {
        return CustomPeriodReapplyPolicy.fromInt(this.customPeriodReapplyPolicy);
    }

    public boolean isCumulative() {
        final InterestBasisMode mode = interestBasisMode();
        return mode != null && mode.isCumulative();
    }

    public boolean isCustomPeriod() {
        final InterestBasisMode mode = interestBasisMode();
        return mode != null && mode.isCustomPeriod();
    }
}
