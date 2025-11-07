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
package org.apache.fineract.portfolio.savings.domain;

import static org.apache.fineract.portfolio.savings.DepositsApiConstants.withHoldTaxPostingTypeIdParamName;

import jakarta.persistence.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.portfolio.savings.WithHoldTaxPostingType;
import org.apache.fineract.portfolio.savings.service.SavingsEnumerations;

@Entity
@Table(name = "m_deposit_product_term_and_preclosure")
public class DepositProductTermAndPreClosure extends AbstractPersistableCustom<Long> {

    @Embedded
    private DepositPreClosureDetail preClosureDetail;

    @Embedded
    protected DepositTermDetail depositTermDetail;

    @OneToOne
    @JoinColumn(name = "savings_product_id", nullable = false)
    private FixedDepositProduct product;

    @Embedded
    private DepositProductAmountDetails depositProductAmountDetails;

    /**
     * A value from the {@link WithHoldTaxPostingType} enumeration.
     */
    @Column(name = "withhold_tax_posting_type_enum", nullable = true)
    private Integer withHoldTaxPostingType;

    protected DepositProductTermAndPreClosure() {

    }

    public static DepositProductTermAndPreClosure createNew(DepositPreClosureDetail preClosureDetail, DepositTermDetail depositTermDetail,
            DepositProductAmountDetails depositProductMinMaxAmountDetails, SavingsProduct product,
            WithHoldTaxPostingType withHoldTaxPostingType) {

        return new DepositProductTermAndPreClosure(preClosureDetail, depositTermDetail, depositProductMinMaxAmountDetails, product,
                withHoldTaxPostingType);
    }

    private DepositProductTermAndPreClosure(DepositPreClosureDetail preClosureDetail, DepositTermDetail depositTermDetail,
            DepositProductAmountDetails depositProductMinMaxAmountDetails, SavingsProduct product,
            WithHoldTaxPostingType withHoldTaxPostingType) {
        this.preClosureDetail = preClosureDetail;
        this.depositTermDetail = depositTermDetail;
        this.depositProductAmountDetails = depositProductMinMaxAmountDetails;
        this.product = (FixedDepositProduct) product;
        this.withHoldTaxPostingType = (withHoldTaxPostingType == null) ? null : withHoldTaxPostingType.getValue();
    }

    public Map<String, Object> update(final JsonCommand command, final DataValidatorBuilder baseDataValidator) {
        final Map<String, Object> actualChanges = new LinkedHashMap<>(10);
        if (this.preClosureDetail != null) {
            actualChanges.putAll(this.preClosureDetail.update(command, baseDataValidator));
        }

        if (this.depositTermDetail != null) {
            actualChanges.putAll(this.depositTermDetail.update(command, baseDataValidator));
        }

        if (this.depositProductAmountDetails != null) {
            actualChanges.putAll(this.depositProductAmountDetails.update(command));
        }

        if (command.isChangeInIntegerParameterNamed(withHoldTaxPostingTypeIdParamName, this.withHoldTaxPostingType)) {
            final Integer newValue = command.integerValueOfParameterNamed(withHoldTaxPostingTypeIdParamName);
            actualChanges.put(withHoldTaxPostingTypeIdParamName, SavingsEnumerations.withHoldTaxPostingType(newValue));
            this.withHoldTaxPostingType = newValue;
        }
        return actualChanges;
    }

    public DepositPreClosureDetail depositPreClosureDetail() {
        return this.preClosureDetail;
    }

    public DepositTermDetail depositTermDetail() {
        return this.depositTermDetail;
    }

    public DepositProductAmountDetails depositProductAmountDetails() {
        return this.depositProductAmountDetails;
    }

    public void updateProductReference(final SavingsProduct product) {
        this.product = (FixedDepositProduct) product;
    }

    public Integer withHoldTaxPostingType() {
        return this.withHoldTaxPostingType;
    }
}
