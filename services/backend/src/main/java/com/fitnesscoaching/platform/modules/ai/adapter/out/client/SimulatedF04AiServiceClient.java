package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.ContinuityEvaluationDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.MeasurementProgressSummaryDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.MetricEvaluationDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.UsageDto;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.WorkoutProgressSummaryDto;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F04AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic simulated implementation of F04AiServiceClient for unit tests and local development.
 */
public class SimulatedF04AiServiceClient implements F04AiServiceClient {

    @Override
    public F04ProgressSummaryResponse summarizeProgress(F04InternalContextEnvelope envelope) {
        var ctx = envelope.context();
        Set<String> limitations = new LinkedHashSet<>();
        Set<String> missingData = new LinkedHashSet<>();
        Set<String> factsReferenced = new LinkedHashSet<>();
        List<String> summaryParts = new ArrayList<>();

        WorkoutProgressSummaryDto workoutEval = null;
        MeasurementProgressSummaryDto measEval = null;

        // 1. Historical limits
        if (ctx.historicalLimits().hasHistoricalLimits()) {
            String expl = ctx.historicalLimits().explanation() != null
                    ? ctx.historicalLimits().explanation()
                    : "Một số dữ liệu lịch sử đã bị thay đổi hoặc xóa sau thời điểm tham chiếu.";
            limitations.add("Giới hạn phục dựng lịch sử tại mốc asOf: " + expl);
        }

        // 2. Training continuity & safety
        boolean hasSafetyAlert = false;
        String continuityMsg;
        String cState = ctx.continuity().state();

        if ("PROLONGED_INACTIVITY".equalsIgnoreCase(cState)
                || (ctx.continuity().safetyFlags() != null && ctx.continuity().safetyFlags().contains("RETURN_AFTER_100_DAYS_V1"))) {
            hasSafetyAlert = true;
            int days = ctx.continuity().daysSinceLastWorkout() != null ? ctx.continuity().daysSinceLastWorkout() : 100;
            continuityMsg = "Khoảng cách từ buổi tập gần nhất đến hiện tại là " + days + " ngày (vượt ngưỡng an toàn 100 ngày).";
            summaryParts.add("CẢNH BÁO AN TOÀN: Học viên có khoảng gián đoạn tập luyện kéo dài " + days + " ngày.");
            limitations.add("Kích hoạt cờ an toàn RETURN_AFTER_100_DAYS_V1: Không được sử dụng thành tích tạ cũ làm mốc tăng tiến. Cần tuần thích nghi lại (re-onboarding).");
            factsReferenced.add("continuity.state:PROLONGED_INACTIVITY");
            factsReferenced.add("continuity.daysSinceLastWorkout:" + days);
            if (ctx.continuity().safetyFlags() != null) {
                for (String flag : ctx.continuity().safetyFlags()) {
                    factsReferenced.add("continuity.safetyFlags:" + flag);
                }
            }
        } else if ("CONFIRMED_NEW".equalsIgnoreCase(cState)) {
            continuityMsg = "Học viên mới bắt đầu có xác nhận onboarding ban đầu; chưa có lịch sử tập luyện trước đó.";
            summaryParts.add("Học viên mới bắt đầu; đang trong giai đoạn làm quen với chương trình tập.");
            factsReferenced.add("continuity.state:CONFIRMED_NEW");
        } else if ("RECENT_ACTIVITY_OBSERVED".equalsIgnoreCase(cState)) {
            Integer days = ctx.continuity().daysSinceLastWorkout();
            String daysStr = days != null ? " cách đây " + days + " ngày" : "";
            continuityMsg = "Học viên duy trì hoạt động tập luyện gần đây; buổi tập gần nhất diễn ra" + daysStr + ".";
            factsReferenced.add("continuity.state:RECENT_ACTIVITY_OBSERVED");
            if (days != null) {
                factsReferenced.add("continuity.daysSinceLastWorkout:" + days);
            }
        } else if ("RESTRICTED_HISTORY".equalsIgnoreCase(cState)) {
            continuityMsg = "Lịch sử tập luyện của học viên bị giới hạn phạm vi chia sẻ; chưa đủ dữ liệu để đánh giá tính liên tục.";
            limitations.add("Lịch sử tập luyện dài hạn bị giới hạn bởi quyền chia sẻ dữ liệu.");
            factsReferenced.add("continuity.state:RESTRICTED_HISTORY");
        } else {
            continuityMsg = "Chưa có đủ dữ liệu lịch sử để đánh giá tính liên tục tập luyện.";
            factsReferenced.add("continuity.state:UNKNOWN_HISTORY");
        }

        ContinuityEvaluationDto continuityDto = new ContinuityEvaluationDto(
                cState,
                continuityMsg,
                hasSafetyAlert
        );

        // 3. Workout Section
        var w = ctx.workout();
        if ("RESTRICTED".equalsIgnoreCase(w.availability())) {
            missingData.add("workout");
            limitations.add("Dữ liệu tập luyện bị hạn chế theo quyền truy cập của vai trò hiện tại.");
            summaryParts.add("Dữ liệu tập luyện của học viên bị giới hạn quyền truy cập.");
        } else if ("NO_DATA".equalsIgnoreCase(w.availability())) {
            missingData.add("workout");
            summaryParts.add("Không có buổi tập nào được ghi nhận trong chu kỳ.");
            workoutEval = new WorkoutProgressSummaryDto(
                    "Không có buổi tập nào hoàn thành trong kỳ.",
                    "Không có lịch tập kế hoạch đến hạn trong kỳ.",
                    "Khối lượng tập trong kỳ là 0 kg."
            );
        } else if ("AVAILABLE".equalsIgnoreCase(w.availability()) && w.adherenceSummary() != null) {
            var adh = w.adherenceSummary();
            var vol = w.volumeSummary();

            String compText;
            String adhText;
            if (adh.scheduledDueCount() == 0) {
                compText = "Không có buổi tập kế hoạch nào đến hạn trong kỳ.";
                adhText = "Không áp dụng tỷ lệ tuân thủ do không có buổi tập đến hạn.";
            } else {
                String rawPct = adh.rawCompletionRate() != null ? String.format("%.1f%%", adh.rawCompletionRate().doubleValue() * 100) : "N/A";
                String onTimePct = adh.onTimeAdherenceRate() != null ? String.format("%.1f%%", adh.onTimeAdherenceRate().doubleValue() * 100) : "N/A";
                compText = "Hoàn thành " + adh.completedDueCount() + "/" + adh.scheduledDueCount() + " buổi tập kế hoạch (" + rawPct + "), bỏ lỡ " + adh.missedCount() + " buổi.";
                adhText = adh.completedOnTimeCount() + " buổi đúng lịch, " + adh.completedOffScheduleCount() + " buổi lệch lịch; tỷ lệ tuân thủ đúng lịch đạt " + onTimePct + ".";
            }

            String volText = "Không có số liệu khối lượng tập.";
            if (vol != null) {
                String volKg = vol.knownVolumeKg() != null ? String.format("%,.1f kg", vol.knownVolumeKg().doubleValue()) : "0 kg";
                String rpeStr = vol.averageSessionRpe() != null ? String.format(", RPE trung bình %.1f", vol.averageSessionRpe().doubleValue()) : "";
                volText = "Tổng khối lượng tập đạt " + volKg + " với " + vol.workingSetsCompleted() + " hiệp chính hoàn thành" + rpeStr + ".";
                if (vol.hasIncompleteVolumeCoverage()) {
                    limitations.add("Một số bài tập thiếu dữ liệu mức tạ hoặc đơn vị đo nên chưa tính được toàn bộ thể tích.");
                }
            }

            workoutEval = new WorkoutProgressSummaryDto(compText, adhText, volText);
            summaryParts.add("Về tập luyện: " + compText + " " + adhText);
            factsReferenced.add("workout.adherence.scheduledDueCount:" + adh.scheduledDueCount());
            factsReferenced.add("workout.adherence.completedDueCount:" + adh.completedDueCount());
            if (adh.rawCompletionRate() != null) {
                factsReferenced.add("workout.adherence.rawCompletionRate:" + String.format("%.4f", adh.rawCompletionRate().doubleValue()));
            }
            if (adh.onTimeAdherenceRate() != null) {
                factsReferenced.add("workout.adherence.onTimeAdherenceRate:" + String.format("%.4f", adh.onTimeAdherenceRate().doubleValue()));
            }
        }

        // 4. Measurement Section
        var m = ctx.measurement();
        if ("RESTRICTED".equalsIgnoreCase(m.availability())) {
            missingData.add("measurement");
            limitations.add("Dữ liệu đo lường thể chất (Body Metrics) bị hạn chế theo quyền chia sẻ dữ liệu của học viên.");
            summaryParts.add("Dữ liệu đo lường thể chất của học viên bị giới hạn quyền truy cập.");
        } else if ("NO_DATA".equalsIgnoreCase(m.availability())) {
            missingData.add("measurement");
        } else if ("AVAILABLE".equalsIgnoreCase(m.availability()) && m.metricDeltas() != null && !m.metricDeltas().isEmpty()) {
            List<MetricEvaluationDto> metricEvals = new ArrayList<>();
            boolean hasWeight = false;
            boolean hasBodyFat = false;

            for (var delta : m.metricDeltas()) {
                String code = delta.metricCode();
                String name = delta.metricDisplayName() != null ? delta.metricDisplayName() : code;
                String unit = delta.unitCode() != null ? delta.unitCode() : "";

                if (code.toUpperCase().contains("WEIGHT")) {
                    hasWeight = true;
                }
                if (code.toUpperCase().contains("FAT")) {
                    hasBodyFat = true;
                }

                String msg;
                if ("INSUFFICIENT_DATA".equalsIgnoreCase(delta.trendDirection())) {
                    msg = "Chỉ số " + name + " chưa đủ quan sát để xác định xu hướng.";
                } else {
                    String directionStr = "INCREASED".equalsIgnoreCase(delta.trendDirection()) ? "tăng"
                            : "DECREASED".equalsIgnoreCase(delta.trendDirection()) ? "giảm" : "ổn định";
                    String deltaStr = delta.deltaValue() != null ? String.format(" %.2f %s", Math.abs(delta.deltaValue().doubleValue()), unit) : "";
                    String pctStr = delta.percentageChange() != null ? String.format(" (%+.2f%%)", delta.percentageChange().doubleValue()) : "";
                    String baseStr = delta.baselineValue() != null ? String.format(" từ %.2f %s", delta.baselineValue().doubleValue(), unit) : "";
                    String latestStr = "";
                    if ("DECREASED".equalsIgnoreCase(delta.trendDirection()) && delta.latestValue() != null) {
                        latestStr = String.format(" xuống %.2f %s", delta.latestValue().doubleValue(), unit);
                    } else if ("INCREASED".equalsIgnoreCase(delta.trendDirection()) && delta.latestValue() != null) {
                        latestStr = String.format(" lên %.2f %s", delta.latestValue().doubleValue(), unit);
                    }
                    msg = name + " có xu hướng " + directionStr + deltaStr + pctStr + baseStr + latestStr + " qua " + delta.numericObservationsCount() + " lần đo.";
                }

                metricEvals.add(new MetricEvaluationDto(code, delta.trendDirection(), msg));
                factsReferenced.add("measurement." + code + ".trend:" + delta.trendDirection());
                if (delta.deltaValue() != null) {
                    factsReferenced.add("measurement." + code + ".delta:" + String.format("%.2f", delta.deltaValue().doubleValue()));
                }
            }

            measEval = new MeasurementProgressSummaryDto(metricEvals);
            StringBuilder measSummary = new StringBuilder();
            for (var me : metricEvals) {
                measSummary.append(me.message()).append(" ");
            }
            summaryParts.add("Về số đo thể chất: " + measSummary.toString().trim());

            if (hasWeight && !hasBodyFat) {
                limitations.add("Biến thiên cân nặng không phản ánh thành phần mỡ hoặc cơ thể do thiếu chỉ số Body Fat %.");
            }
        }

        // 5. Determine resultType
        boolean isBothNoData = "NO_DATA".equalsIgnoreCase(w.availability()) && "NO_DATA".equalsIgnoreCase(m.availability());
        String resultType;
        String reasonCode;
        String finalSummary;

        if (isBothNoData && ("UNKNOWN_HISTORY".equalsIgnoreCase(cState) || "CONFIRMED_NEW".equalsIgnoreCase(cState))) {
            resultType = "INSUFFICIENT_DATA";
            reasonCode = "INSUFFICIENT_DATA";
            finalSummary = "Không có đủ dữ liệu tập luyện hoặc đo lường được ghi nhận trong chu kỳ được chọn để xác lập xu hướng tiến độ.";
        } else {
            resultType = "INFORMATION";
            reasonCode = "NONE";
            finalSummary = !summaryParts.isEmpty() ? String.join(" ", summaryParts) : "Tiến độ được ghi nhận trong chu kỳ.";
        }

        return new F04ProgressSummaryResponse(
                envelope.requestId(),
                envelope.correlationId(),
                envelope.studentId(),
                envelope.actorId(),
                "SUCCEEDED",
                resultType,
                reasonCode,
                finalSummary,
                workoutEval,
                measEval,
                continuityDto,
                new ArrayList<>(limitations),
                new ArrayList<>(missingData),
                new ArrayList<>(factsReferenced),
                ctx.effectiveAsOf(),
                ctx.compositeWatermark(),
                new UsageDto(280, 150, 430),
                false,
                false,
                null
        );
    }
}
