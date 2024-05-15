package com.badlogic.gdx.pay.ios.apple;

import com.badlogic.gdx.pay.FreeTrialPeriod;

import java.util.HashMap;
import java.util.Map;

import apple.storekit.enums.SKProductPeriodUnit;

enum SKProductPeriodUnitToPeriodUnitConverter {
    ;

    private static final Map<Long, FreeTrialPeriod.PeriodUnit> appleToGdxUnitMap = new HashMap<Long, FreeTrialPeriod.PeriodUnit>();

    static {
        appleToGdxUnitMap.put(SKProductPeriodUnit.Day, FreeTrialPeriod.PeriodUnit.DAY);
        appleToGdxUnitMap.put(SKProductPeriodUnit.Week, FreeTrialPeriod.PeriodUnit.WEEK);
        appleToGdxUnitMap.put(SKProductPeriodUnit.Month, FreeTrialPeriod.PeriodUnit.MONTH);
        appleToGdxUnitMap.put(SKProductPeriodUnit.Year, FreeTrialPeriod.PeriodUnit.YEAR);
    }

    public static FreeTrialPeriod.PeriodUnit convertToPeriodUnit(long unit) {
        return appleToGdxUnitMap.get(unit);
    }

}
