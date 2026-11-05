package com.fitnesscoaching.platform.modules.measurement.application.port.in;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/** Measurement-owned presentation boundary for retained unit identities. */
public interface MeasurementUnitReferenceQuery {
    Map<Short, UnitReference> resolveAll(Collection<Short> unitIds);

    Map<String, UnitDetails> resolveByCodes(Collection<String> unitCodes);

    record UnitReference(short id, String code, String symbol, String dimension) {}

    record UnitDetails(
            short id,
            String code,
            String symbol,
            String dimension,
            String baseUnitCode,
            BigDecimal multiplierToBase,
            BigDecimal offsetToBase
    ) {}
}
