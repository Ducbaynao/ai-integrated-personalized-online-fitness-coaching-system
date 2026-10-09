package com.fitnesscoaching.platform.modules.measurement.application.port.in;

import java.util.Collection;
import java.util.Map;

/** Measurement-owned presentation boundary for retained unit identities. */
public interface MeasurementUnitReferenceQuery {
    Map<Short, UnitReference> resolveAll(Collection<Short> unitIds);

    record UnitReference(short id, String code, String symbol, String dimension) {}
}
