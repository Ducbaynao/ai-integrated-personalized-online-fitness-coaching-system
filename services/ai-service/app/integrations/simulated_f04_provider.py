from datetime import timezone
from decimal import Decimal

from app.schemas.f04_schemas import (
    ContinuityEvaluationResult,
    F04ProgressSummaryRequest,
    F04ProgressSummaryResult,
    MeasurementProgressSummaryResult,
    MetricEvaluationResult,
    UsageTokensResult,
    WorkoutProgressSummaryResult,
)


class SimulatedF04ProgressProvider:
    """Deterministic, verified simulation provider for F04 progress summarization.

    Grounded strictly in the facts provided in F04ProgressSummaryRequest.context.
    Adheres strictly to the following domain invariants:
    - Never hallucinates data or sources not in context.
    - Preserves unknown/null, zero, and restricted states.
    - Distinguishes completion rate from schedule adherence.
    - Never infers muscle gain or fat loss from body weight changes alone.
    - Flags safety alerts when prolonged inactivity (>= 100 days) is detected.
    - Strictly produces INFORMATION results; never generates plan mutations or proposals.
    """

    async def summarize_progress(self, request: F04ProgressSummaryRequest) -> F04ProgressSummaryResult:
        ctx = request.context
        limitations: list[str] = []
        missing_data: list[str] = []
        facts_referenced: list[str] = []

        summary_parts: list[str] = []
        workout_eval: WorkoutProgressSummaryResult | None = None
        meas_eval: MeasurementProgressSummaryResult | None = None

        # 1. Historical limits check
        if ctx.historicalLimits.hasHistoricalLimits:
            expl = ctx.historicalLimits.explanation or "Một số dữ liệu lịch sử đã bị thay đổi hoặc xóa sau thời điểm tham chiếu."
            limitations.append(f"Giới hạn phục dựng lịch sử tại mốc asOf: {expl}")

        # 2. Training continuity & safety signals
        has_safety_alert = False
        continuity_msg = ""
        c_state = ctx.continuity.state

        if c_state == "PROLONGED_INACTIVITY" or "RETURN_AFTER_100_DAYS_V1" in ctx.continuity.safetyFlags:
            has_safety_alert = True
            days = ctx.continuity.daysSinceLastWorkout if ctx.continuity.daysSinceLastWorkout is not None else 100
            continuity_msg = f"Khoảng cách từ buổi tập gần nhất đến hiện tại là {days} ngày (vượt ngưỡng an toàn 100 ngày)."
            summary_parts.append(
                f"CẢNH BÁO AN TOÀN: Học viên có khoảng gián đoạn tập luyện kéo dài {days} ngày."
            )
            limitations.append(
                "Kích hoạt cờ an toàn RETURN_AFTER_100_DAYS_V1: Không được sử dụng thành tích tạ cũ làm mốc tăng tiến. Cần tuần thích nghi lại (re-onboarding)."
            )
            facts_referenced.append("continuity.state:PROLONGED_INACTIVITY")
            facts_referenced.append(f"continuity.daysSinceLastWorkout:{days}")
            if ctx.continuity.safetyFlags:
                for flag in ctx.continuity.safetyFlags:
                    facts_referenced.append(f"continuity.safetyFlags:{flag}")
        elif c_state == "CONFIRMED_NEW":
            continuity_msg = "Học viên mới bắt đầu có xác nhận onboarding ban đầu; chưa có lịch sử tập luyện trước đó."
            summary_parts.append("Học viên mới bắt đầu; đang trong giai đoạn làm quen với chương trình tập.")
            facts_referenced.append("continuity.state:CONFIRMED_NEW")
        elif c_state == "RECENT_ACTIVITY_OBSERVED":
            days = ctx.continuity.daysSinceLastWorkout
            days_str = f" cách đây {days} ngày" if days is not None else ""
            continuity_msg = f"Học viên duy trì hoạt động tập luyện gần đây; buổi tập gần nhất diễn ra{days_str}."
            facts_referenced.append("continuity.state:RECENT_ACTIVITY_OBSERVED")
            if days is not None:
                facts_referenced.append(f"continuity.daysSinceLastWorkout:{days}")
        elif c_state == "RESTRICTED_HISTORY":
            continuity_msg = "Lịch sử tập luyện của học viên bị giới hạn phạm vi chia sẻ; chưa đủ dữ liệu để đánh giá tính liên tục."
            limitations.append("Lịch sử tập luyện dài hạn bị giới hạn bởi quyền chia sẻ dữ liệu.")
            facts_referenced.append("continuity.state:RESTRICTED_HISTORY")
        else:  # UNKNOWN_HISTORY
            continuity_msg = "Chưa có đủ dữ liệu lịch sử để đánh giá tính liên tục tập luyện."
            facts_referenced.append("continuity.state:UNKNOWN_HISTORY")

        continuity_eval_result = ContinuityEvaluationResult(
            state=c_state,
            message=continuity_msg,
            hasSafetyAlert=has_safety_alert,
        )

        # 3. Workout Section evaluation
        if ctx.workout.availability == "RESTRICTED":
            missing_data.append("workout")
            limitations.append("Dữ liệu tập luyện bị hạn chế theo quyền truy cập của vai trò hiện tại.")
            summary_parts.append("Dữ liệu tập luyện của học viên bị giới hạn quyền truy cập.")
        elif ctx.workout.availability == "NO_DATA":
            missing_data.append("workout")
            summary_parts.append("Không có buổi tập nào được ghi nhận trong chu kỳ.")
            workout_eval = WorkoutProgressSummaryResult(
                completionEvaluation="Không có buổi tập nào hoàn thành trong kỳ.",
                adherenceEvaluation="Không có lịch tập kế hoạch đến hạn trong kỳ.",
                volumeEvaluation="Khối lượng tập trong kỳ là 0 kg.",
            )
        elif ctx.workout.availability == "AVAILABLE" and ctx.workout.adherenceSummary:
            adh = ctx.workout.adherenceSummary
            vol = ctx.workout.volumeSummary

            # Completion & adherence
            if adh.scheduledDueCount == 0:
                comp_text = "Không có buổi tập kế hoạch nào đến hạn trong kỳ."
                adh_text = "Không áp dụng tỷ lệ tuân thủ do không có buổi tập đến hạn."
            else:
                raw_pct = f"{float(adh.rawCompletionRate * 100):.1f}%" if adh.rawCompletionRate is not None else "N/A"
                on_time_pct = f"{float(adh.onTimeAdherenceRate * 100):.1f}%" if adh.onTimeAdherenceRate is not None else "N/A"
                comp_text = f"Hoàn thành {adh.completedDueCount}/{adh.scheduledDueCount} buổi tập kế hoạch ({raw_pct}), bỏ lỡ {adh.missedCount} buổi."
                adh_text = f"{adh.completedOnTimeCount} buổi đúng lịch, {adh.completedOffScheduleCount} buổi lệch lịch; tỷ lệ tuân thủ đúng lịch đạt {on_time_pct}."

            vol_text = "Không có số liệu khối lượng tập."
            if vol:
                vol_kg = f"{float(vol.knownVolumeKg):,.1f} kg" if vol.knownVolumeKg is not None else "0 kg"
                rpe_str = f", RPE trung bình {float(vol.averageSessionRpe):.1f}" if vol.averageSessionRpe is not None else ""
                vol_text = f"Tổng khối lượng tập đạt {vol_kg} với {vol.workingSetsCompleted} hiệp chính hoàn thành{rpe_str}."
                if vol.hasIncompleteVolumeCoverage:
                    limitations.append("Một số bài tập thiếu dữ liệu mức tạ hoặc đơn vị đo nên chưa tính được toàn bộ thể tích.")

            workout_eval = WorkoutProgressSummaryResult(
                completionEvaluation=comp_text,
                adherenceEvaluation=adh_text,
                volumeEvaluation=vol_text,
            )
            summary_parts.append(f"Về tập luyện: {comp_text} {adh_text}")
            facts_referenced.append(f"workout.adherence.scheduledDueCount:{adh.scheduledDueCount}")
            facts_referenced.append(f"workout.adherence.completedDueCount:{adh.completedDueCount}")
            if adh.rawCompletionRate is not None:
                facts_referenced.append(f"workout.adherence.rawCompletionRate:{float(adh.rawCompletionRate):.4f}")
            if adh.onTimeAdherenceRate is not None:
                facts_referenced.append(f"workout.adherence.onTimeAdherenceRate:{float(adh.onTimeAdherenceRate):.4f}")

        # 4. Measurement Section evaluation
        if ctx.measurement.availability == "RESTRICTED":
            missing_data.append("measurement")
            limitations.append("Dữ liệu đo lường thể chất (Body Metrics) bị hạn chế theo quyền chia sẻ dữ liệu của học viên.")
            summary_parts.append("Dữ liệu đo lường thể chất của học viên bị giới hạn quyền truy cập.")
        elif ctx.measurement.availability == "NO_DATA":
            missing_data.append("measurement")
        elif ctx.measurement.availability == "AVAILABLE" and ctx.measurement.metricDeltas:
            metric_evals: list[MetricEvaluationResult] = []
            has_weight = False
            has_body_fat = False

            for delta in ctx.measurement.metricDeltas:
                code = delta.metricCode
                name = delta.metricDisplayName or code
                unit = delta.unitCode or ""

                if "WEIGHT" in code.upper():
                    has_weight = True
                if "FAT" in code.upper():
                    has_body_fat = True

                if delta.trendDirection == "INSUFFICIENT_DATA":
                    msg = f"Chỉ số {name} chưa đủ quan sát để xác định xu hướng."
                else:
                    direction_str = "tăng" if delta.trendDirection == "INCREASED" else "giảm" if delta.trendDirection == "DECREASED" else "ổn định"
                    delta_str = f" {abs(float(delta.deltaValue)):.2f} {unit}" if delta.deltaValue is not None else ""
                    pct_str = f" ({float(delta.percentageChange):+.2f}%)" if delta.percentageChange is not None else ""
                    base_str = f" từ {float(delta.baselineValue):.2f} {unit}" if delta.baselineValue is not None else ""
                    latest_str = f" xuống {float(delta.latestValue):.2f} {unit}" if delta.trendDirection == "DECREASED" and delta.latestValue is not None else f" lên {float(delta.latestValue):.2f} {unit}" if delta.trendDirection == "INCREASED" and delta.latestValue is not None else ""
                    msg = f"{name} có xu hướng {direction_str}{delta_str}{pct_str}{base_str}{latest_str} qua {delta.numericObservationsCount} lần đo."

                metric_evals.append(MetricEvaluationResult(
                    metricCode=code,
                    trendDirection=delta.trendDirection,
                    message=msg,
                ))
                facts_referenced.append(f"measurement.{code}.trend:{delta.trendDirection}")
                if delta.deltaValue is not None:
                    facts_referenced.append(f"measurement.{code}.delta:{float(delta.deltaValue):.2f}")

            meas_eval = MeasurementProgressSummaryResult(metricsEvaluations=metric_evals)
            meas_summary = " ".join([m.message for m in metric_evals])
            summary_parts.append(f"Về số đo thể chất: {meas_summary}")

            if has_weight and not has_body_fat:
                limitations.append("Biến thiên cân nặng không phản ánh thành phần mỡ hoặc cơ thể do thiếu chỉ số Body Fat %.")

        # 5. Determine overall resultType and reasonCode
        is_both_no_data = (ctx.workout.availability == "NO_DATA") and (ctx.measurement.availability == "NO_DATA")
        if is_both_no_data and c_state in ("UNKNOWN_HISTORY", "CONFIRMED_NEW"):
            result_type = "INSUFFICIENT_DATA"
            reason_code = "INSUFFICIENT_DATA"
            final_summary = "Không có đủ dữ liệu tập luyện hoặc đo lường được ghi nhận trong chu kỳ được chọn để xác lập xu hướng tiến độ."
        else:
            result_type = "INFORMATION"
            reason_code = "NONE"
            final_summary = " ".join(summary_parts) if summary_parts else "Tiến độ được ghi nhận trong chu kỳ."

        return F04ProgressSummaryResult(
            runId=request.requestId,
            correlationId=request.correlationId,
            runStatus="SUCCEEDED",
            resultType=result_type,
            reasonCode=reason_code,
            summary=final_summary,
            workoutProgressSummary=workout_eval,
            measurementProgressSummary=meas_eval,
            continuityEvaluation=continuity_eval_result,
            limitations=list(dict.fromkeys(limitations)),
            missingData=list(dict.fromkeys(missing_data)),
            factsReferenced=list(dict.fromkeys(facts_referenced)),
            asOf=ctx.effectiveAsOf,
            sourceWatermark=ctx.compositeWatermark,
            usage=UsageTokensResult(
                promptTokens=280,
                completionTokens=150,
                totalTokens=430,
            ),
        )
