package com.fitnesscoaching.platform.modules.measurement.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MeasurementProgressQuery {

    MeasurementProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            Set<String> metricCodes,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    );

    default MeasurementProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            String metricCode,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        return query(actorId, studentId, metricCode != null ? Set.of(metricCode) : null, fromDate, toDate, timezone, asOf);
    }

    default MeasurementProgressSnapshot query(
            UUID actorId,
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf
    ) {
        return query(actorId, studentId, (Set<String>) null, fromDate, toDate, timezone, asOf);
    }

    default Optional<Instant> getEarliestObservationTime(UUID actorId, UUID studentId) {
        return Optional.empty();
    }

    record MeasurementProgressSnapshot(
            UUID studentId,
            LocalDate fromDate,
            LocalDate toDate,
            ZoneId timezone,
            Instant asOf,
            MeasurementSummary summary,
            List<MeasurementObservationFact> observations,
            String sourceWatermark
    ) {}

    record MeasurementSummary(
            int totalObservationsCount,
            int distinctMetricsCount,
            int distinctDaysCount,
            boolean isTimeRestricted,
            Instant effectiveHistoryFrom,
            Instant effectiveHistoryUntil,
            boolean hasHistoricalReconstructionLimits,
            String availabilityReason
    ) {}

    record MeasurementObservationFact(
            UUID id,
            UUID studentId,
            Integer metricDefinitionId,
            String metricCode,
            String metricDisplayName,
            Instant measuredAt,
            LocalDate measuredLocalDate,
            BigDecimal numericValue,
            String textValue,
            Boolean booleanValue,
            Short unitId,
            String unitCode,
            String unitSymbol,
            UUID measurementBatchId,
            Short sourceId,
            String sourceCode,
            String sourceName,
            String sourceKind,
            Short methodId,
            String methodCode,
            String methodName,
            UUID enteredBy,
            UUID integrationConnectionId,
            String externalRecordId,
            BigDecimal confidenceScore,
            BigDecimal qualityScore,
            String validationStatus,
            UUID supersededById,
            Instant createdAt,
            String historicalReconstructionStatus
    ) {}
}
