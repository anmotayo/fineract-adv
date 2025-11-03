package org.apache.fineract.portfolio.savings;

import java.util.Arrays;

public enum WithHoldTaxPostingType {
    INVALID(0, "withHoldTaxPostingType.invalid"), //
    MATURITY(1, "withHoldTaxPostingType.maturity"), //
    INTEREST_POSTING(2, "withHoldTaxPostingType.interestPosting");

    private final Integer value;
    private final String code;

    WithHoldTaxPostingType(final Integer value, final String code){
        this.value = value;
        this.code = code;
    }

    public Integer getValue() {
        return this.value;
    }

    public String getCode() {
        return this.code;
    }

    public static WithHoldTaxPostingType fromInt(final Integer v) {
        return switch (v) {
            case 1 -> MATURITY;
            case 2 -> INTEREST_POSTING;
            default -> INVALID;
        };
    }

    public static Object[] integerValues() {
        return Arrays.stream(values()).filter(value -> !INVALID.equals(value)).map(value -> value.value).toList().toArray();
    }

    public boolean isMaturity() {
        return this.equals(MATURITY);
    }

    public boolean isInterestPosting() {
        return this.equals(INTEREST_POSTING);
    }
}
