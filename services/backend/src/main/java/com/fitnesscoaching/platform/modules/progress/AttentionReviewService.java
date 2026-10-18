package com.fitnesscoaching.platform.modules.progress;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.HumanReviewPeriodQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutMissedEvidenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.HexFormat;

/** Deterministic M7 rules. PostgreSQL owns task/signal state; source modules supply evidence through ports. */
@Service
public class AttentionReviewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentCoachingContextQuery contexts;
    private final HumanReviewPeriodQuery periods;
    private final WorkoutMissedEvidenceQuery workouts;
    private final CoachingAuthorityQuery authority;
    private final StudentCapabilityQuery students;
    private final TrainerCoachingEligibilityQuery trainers;
    private final UserAccountStatusQuery accounts;

    public AttentionReviewService(JdbcTemplate jdbc, ObjectMapper json,
            CurrentCoachingContextQuery contexts, HumanReviewPeriodQuery periods,
            WorkoutMissedEvidenceQuery workouts, CoachingAuthorityQuery authority,
            StudentCapabilityQuery students, TrainerCoachingEligibilityQuery trainers,
            UserAccountStatusQuery accounts) {
        this.jdbc=jdbc; this.json=json; this.contexts=contexts; this.periods=periods;
        this.workouts=workouts; this.authority=authority; this.students=students;
        this.trainers=trainers; this.accounts=accounts;
    }

    public record Page<T>(List<T> items, int page, int size, boolean hasMore) {}
    public record TimezoneConfirmation(String timezone, Instant confirmedAt, long version) {}
    public record Review(UUID id, UUID studentId, UUID trainerId, UUID coachingPeriodId,
                         LocalDate cycleStartDate, LocalDate dueDate, String timezone,
                         String status, long version, String summary, List<String> evidenceRefs,
                         List<String> missingData, Instant completedAt, Instant studentAcknowledgedAt) {}
    public record Signal(UUID id, UUID studentId, UUID trainerId, UUID coachingPeriodId,
                         String rule, String severity, String status, long version,
                         UUID occurrenceId, Long occurrenceVersion, Instant plannedStartAt,
                         Instant plannedEndAt, UUID reviewTaskId, Instant detectedAt) {}
    public record SignalEvent(String eventType, String previousSeverity, String resultingSeverity,
                              String previousStatus, String resultingStatus, long resultingVersion,
                              String reason, Instant occurredAt) {}

    @Transactional
    public TimezoneConfirmation confirmTimezone(UUID actor, String zoneId, UUID key) {
        requireStudent(actor);
        if (key == null) throw fail("VALIDATION_FAILED",400);
        ZoneId zone = zone(zoneId);
        String hash = hash("TIMEZONE:"+zone.getId());
        TimezoneConfirmation prior = receipt(actor,key,hash,TimezoneConfirmation.class);
        if (prior != null) return prior;
        jdbc.queryForList("SELECT user_id FROM fitness.student_profiles WHERE user_id=? FOR UPDATE",actor);
        String previous=jdbc.query("SELECT timezone FROM fitness.student_review_timezones WHERE student_id=? FOR UPDATE",
                (rs,n)->rs.getString(1),actor).stream().findFirst().orElse(null);
        jdbc.update("""
                INSERT INTO fitness.student_review_timezones(student_id,timezone) VALUES (?,?)
                ON CONFLICT (student_id) DO UPDATE SET timezone=EXCLUDED.timezone,
                  confirmed_at=clock_timestamp(),version=student_review_timezones.version+1
                """,actor,zone.getId());
        TimezoneConfirmation result=timezone(actor);
        jdbc.update("""
                INSERT INTO fitness.student_review_timezone_events(student_id,actor_id,command_key,
                  previous_timezone,confirmed_timezone,resulting_version) VALUES (?,?,?,?,?,?)
                """,actor,actor,key,previous,result.timezone(),result.version());
        saveReceipt(actor,key,hash,"TIMEZONE",actor,result);
        return result;
    }

    @Transactional(readOnly=true)
    public TimezoneConfirmation timezone(UUID actor) {
        requireStudent(actor);
        return jdbc.query("""
                SELECT timezone,confirmed_at,version FROM fitness.student_review_timezones
                WHERE student_id=?
                """,(rs,n)->new TimezoneConfirmation(rs.getString(1),rs.getTimestamp(2).toInstant(),rs.getLong(3)),actor)
                .stream().findFirst().orElse(null);
    }

    /** Public for deterministic integration tests; scheduler invokes the same transactional command. */
    @Scheduled(cron="0 */5 * * * *")
    @Transactional
    public void evaluate() {
        Timestamp dbTime = jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class);
        Instant now = dbTime == null ? null : dbTime.toInstant();
        if (now == null) return;
        Set<UUID> active = new HashSet<>();
        for (var period: periods.activePeriods()) {
            active.add(period.id());
            List<String> zones = jdbc.query("SELECT timezone FROM fitness.student_review_timezones WHERE student_id=?",
                    (rs,n)->rs.getString(1),period.studentId());
            if (zones.isEmpty()) continue; // A database default is not Student confirmation.
            ZoneId zone = zone(zones.getFirst());
            LocalDate anchor = period.startedAt().atZone(zone).toLocalDate();
            LocalDate today = now.atZone(zone).toLocalDate();
            // Cycle zero begins on the period start's local date; no invented 09:00 boundary.
            for (LocalDate cycle=anchor; !cycle.isAfter(today); cycle=cycle.plusDays(14)) {
                List<UUID> created=jdbc.query("""
                        INSERT INTO fitness.coaching_review_tasks(student_id,trainer_id,coaching_period_id,
                          cycle_start_date,due_date,timezone) VALUES (?,?,?,?,?,?)
                        ON CONFLICT (coaching_period_id,cycle_start_date) DO NOTHING
                        RETURNING id
                        """,(rs,n)->rs.getObject(1,UUID.class),period.studentId(),period.trainerId(),
                        period.id(),cycle,cycle.plusDays(7),zone.getId());
                for (UUID id:created) taskEvent(id,null,"CREATED",null,0,"{}");
            }
        }
        var openTasks = jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,cycle_start_date,due_date,timezone,
                  status,version,summary,evidence::text,missing_data::text,completed_at,student_acknowledged_at
                FROM fitness.coaching_review_tasks WHERE status='OPEN'
                """,(rs,n)->mapReview(rs));
        for (Review task: openTasks) {
            if (!active.contains(task.coachingPeriodId())) {
                int changed=jdbc.update("""
                        UPDATE fitness.coaching_review_tasks SET status='CANCELLED',version=version+1,
                          updated_at=clock_timestamp() WHERE id=? AND status='OPEN'
                        """,task.id());
                if (changed==1) {
                    taskEvent(task.id(),null,"CANCELLED",task.version(),task.version()+1,"{}");
                    resolveSource("REVIEW_OVERDUE_V1",task.id().toString(),"SOURCE_CANCELLED");
                }
                continue;
            }
            LocalDate today=now.atZone(zone(task.timezone())).toLocalDate();
            String severity=today.isBefore(task.dueDate().plusDays(1))?null:
                    today.isBefore(task.dueDate().plusDays(7))?"LOW":"MEDIUM";
            if (severity==null) { resolveSource("REVIEW_OVERDUE_V1",task.id().toString(),"SOURCE_CORRECTED"); continue; }
            upsertSignal(task.studentId(),task.trainerId(),task.coachingPeriodId(),
                    "REVIEW_OVERDUE_V1",task.id().toString(),0,severity,
                    null,null,null,null,task.id());
        }
        for (var candidate: workouts.candidates(now.minus(Duration.ofHours(24)))) {
            if (!candidate.eligible() || candidate.plannedEndAt()==null) continue;
            var period=contexts.findEffectiveContext(candidate.studentId(),false).orElse(null);
            UUID periodId=period!=null && period.mode()==CurrentCoachingContextQuery.Mode.HUMAN_COACH
                    ? period.periodId():null;
            UUID trainer=periodId==null?null:period.trainerId();
            upsertSignal(candidate.studentId(),trainer,periodId,"MISSED_PLANNED_V1",
                    candidate.occurrenceId()+":v"+candidate.occurrenceVersion(),candidate.occurrenceVersion(),"MEDIUM",
                    candidate.occurrenceId(),candidate.occurrenceVersion(),candidate.plannedStartAt(),
                    candidate.plannedEndAt(),null);
        }
        for (Signal signal: openRuleSignals("MISSED_PLANNED_V1")) {
            var source=workouts.find(signal.occurrenceId());
            if (source.isEmpty() || !source.get().eligible() || source.get().plannedEndAt()==null
                    || !source.get().plannedEndAt().isBefore(now.minus(Duration.ofHours(24)))) {
                resolveSource("MISSED_PLANNED_V1",signal.occurrenceId()+":v"+signal.occurrenceVersion(),"SOURCE_CORRECTED");
            } else if (signal.occurrenceVersion()!=null
                    && signal.occurrenceVersion()!=source.get().occurrenceVersion()) {
                resolveSource("MISSED_PLANNED_V1",signal.occurrenceId()+":v"+signal.occurrenceVersion(),"SOURCE_SUPERSEDED");
            }
        }
    }

    @Transactional
    public Page<Review> reviews(UUID actor, UUID student, int page, int size) {
        checkPage(page,size);
        UUID period=listPeriod(actor,student);
        boolean workoutAllowed=actor.equals(student) || authority.evaluateCurrent(new AuthorityRequest(
                actor,student,DataScope.WORKOUT_HISTORY,DataAccessLevel.VIEW)).allowed();
        List<Review> rows=jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,cycle_start_date,due_date,timezone,
                  status,version,summary,evidence::text,missing_data::text,completed_at,student_acknowledged_at
                FROM fitness.coaching_review_tasks WHERE student_id=?
                  AND (?::uuid IS NULL OR coaching_period_id=?)
                  AND (? OR evidence='[]'::jsonb)
                ORDER BY cycle_start_date DESC,id DESC
                LIMIT ? OFFSET ?
                """,(rs,n)->mapReview(rs),student,period,period,workoutAllowed,size+1,(long)page*size);
        return new Page<>(rows.size()>size?rows.subList(0,size):rows,page,size,rows.size()>size);
    }

    @Transactional
    public Review review(UUID actor,UUID id) {
        Review row=findReview(id);
        requireRead(actor,row.studentId(),row.coachingPeriodId(),!row.evidenceRefs().isEmpty());
        return row;
    }

    @Transactional
    public Review complete(UUID actor,UUID id,long expectedVersion,UUID key,String summary,
                           List<String> evidenceRefs,List<String> missingData) {
        if (key==null || summary==null || summary.isBlank() || summary.length()>4000
                || evidenceRefs==null || missingData==null) throw fail("VALIDATION_FAILED",400);
        Review task=findReview(id);
        requireTrainer(actor,task.studentId(),task.coachingPeriodId(),false,true);
        String hash=hash("COMPLETE:"+id+":"+expectedVersion+":"+summary+":"+evidenceRefs+":"+missingData);
        Review prior=receipt(actor,key,hash,Review.class);
        if (prior!=null) {
            requireRead(actor,prior.studentId(),prior.coachingPeriodId(),!prior.evidenceRefs().isEmpty());
            return prior;
        }
        task=lockReview(id);
        if (task.version()!=expectedVersion || !task.status().equals("OPEN")) throw fail("REVIEW_VERSION_CONFLICT",409);
        for (String ref:evidenceRefs) {
            UUID occurrence;
            try { occurrence=UUID.fromString(ref); } catch (IllegalArgumentException bad) { throw fail("VALIDATION_FAILED",400); }
            var evidence=workouts.find(occurrence).orElseThrow(()->fail("REVIEW_EVIDENCE_UNAVAILABLE",404));
            if (!evidence.studentId().equals(task.studentId())) throw fail("REVIEW_EVIDENCE_UNAVAILABLE",404);
            requireTrainer(actor,task.studentId(),task.coachingPeriodId(),true,true);
        }
        int changed=jdbc.update("""
                UPDATE fitness.coaching_review_tasks SET status='COMPLETED',version=version+1,
                  summary=?,evidence=?::jsonb,missing_data=?::jsonb,completed_at=clock_timestamp(),
                  updated_at=clock_timestamp() WHERE id=? AND version=? AND status='OPEN'
                """,summary,write(evidenceRefs),write(missingData),id,expectedVersion);
        if (changed!=1) throw fail("REVIEW_VERSION_CONFLICT",409);
        taskEvent(id,actor,"COMPLETED",expectedVersion,expectedVersion+1,
                write(Map.of("evidenceRefs",evidenceRefs,"missingData",missingData)));
        resolveSource("REVIEW_OVERDUE_V1",id.toString(),"SOURCE_COMPLETED");
        Review result=findReview(id);
        saveReceipt(actor,key,hash,"REVIEW",id,result);
        return result;
    }

    @Transactional
    public Review acknowledgeReview(UUID actor,UUID id,long expectedVersion,UUID key) {
        if (key==null) throw fail("VALIDATION_FAILED",400);
        Review task=findReview(id);
        requireStudent(actor);
        if (!actor.equals(task.studentId())) throw fail("REVIEW_NOT_FOUND",404);
        String hash=hash("ACK_REVIEW:"+id+":"+expectedVersion);
        Review prior=receipt(actor,key,hash,Review.class);
        if (prior!=null) return prior;
        task=lockReview(id);
        if (task.version()!=expectedVersion || !task.status().equals("COMPLETED")) throw fail("REVIEW_VERSION_CONFLICT",409);
        int changed=jdbc.update("""
                UPDATE fitness.coaching_review_tasks SET student_acknowledged_at=COALESCE(student_acknowledged_at,clock_timestamp()),
                  version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?
                """,id,expectedVersion);
        if (changed!=1) throw fail("REVIEW_VERSION_CONFLICT",409);
        taskEvent(id,actor,"STUDENT_ACKNOWLEDGED",expectedVersion,expectedVersion+1,"{}");
        Review result=findReview(id);
        saveReceipt(actor,key,hash,"REVIEW",id,result);
        return result;
    }

    @Transactional
    public Page<Signal> signals(UUID actor,UUID student,int page,int size) {
        checkPage(page,size);
        UUID period=listPeriod(actor,student);
        boolean workoutAllowed=actor.equals(student) || authority.evaluateCurrent(new AuthorityRequest(
                actor,student,DataScope.WORKOUT_HISTORY,DataAccessLevel.VIEW)).allowed();
        List<Signal> rows=jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,signal_type,severity::text,status::text,
                  version,related_entity_id,source_version,evidence::text,created_at
                FROM fitness.attention_signals WHERE student_id=? AND source='SYSTEM_RULE'
                  AND (?::uuid IS NULL OR coaching_period_id=?)
                  AND (? OR signal_type='REVIEW_OVERDUE_V1')
                  AND signal_type IN ('MISSED_PLANNED_V1','REVIEW_OVERDUE_V1')
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,n)->mapSignal(rs),student,period,period,workoutAllowed,size+1,(long)page*size);
        return new Page<>(rows.size()>size?rows.subList(0,size):rows,page,size,rows.size()>size);
    }

    @Transactional
    public Signal signal(UUID actor,UUID id) {
        Signal row=findSignal(id);
        requireRead(actor,row.studentId(),row.coachingPeriodId(),row.rule().equals("MISSED_PLANNED_V1"));
        return row;
    }

    @Transactional
    public List<SignalEvent> signalHistory(UUID actor,UUID id) {
        signal(actor,id);
        return jdbc.query("""
                SELECT event_type,previous_severity::text,resulting_severity::text,
                  previous_status::text,resulting_status::text,resulting_version,reason,occurred_at
                FROM fitness.attention_signal_events WHERE signal_id=? ORDER BY occurred_at,id
                """,(rs,n)->new SignalEvent(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getLong(6),rs.getString(7),rs.getTimestamp(8).toInstant()),id);
    }

    @Transactional
    public Signal actOnSignal(UUID actor,UUID id,String action,long expectedVersion,UUID key,String reason) {
        if (key==null || !Set.of("ACK","RESOLVE","DISMISS").contains(action)
                || (!action.equals("ACK") && (reason==null || reason.isBlank() || reason.length()>2000)))
            throw fail("VALIDATION_FAILED",400);
        Signal signal=findSignal(id);
        requireTrainer(actor,signal.studentId(),signal.coachingPeriodId(),
                signal.rule().equals("MISSED_PLANNED_V1"),true);
        String hash=hash(action+":"+id+":"+expectedVersion+":"+Objects.toString(reason,""));
        Signal prior=receipt(actor,key,hash,Signal.class);
        if (prior!=null) return prior;
        signal=lockSignal(id);
        if (signal.version()!=expectedVersion || Set.of("RESOLVED","DISMISSED").contains(signal.status()))
            throw fail("ATTENTION_VERSION_CONFLICT",409);
        String next=switch(action) { case "ACK" -> "ACKNOWLEDGED"; case "RESOLVE" -> "RESOLVED";
            default -> "DISMISSED"; };
        if (signal.status().equals(next)) throw fail("ATTENTION_VERSION_CONFLICT",409);
        int changed=jdbc.update("""
                UPDATE fitness.attention_signals SET status=?::fitness.attention_status,version=version+1,
                  acknowledged_at=CASE WHEN ?='ACKNOWLEDGED' THEN clock_timestamp() ELSE acknowledged_at END,
                  resolved_at=CASE WHEN ? IN ('RESOLVED','DISMISSED') THEN clock_timestamp() ELSE resolved_at END,
                  resolution_note=CASE WHEN ? IN ('RESOLVED','DISMISSED') THEN ? ELSE resolution_note END,
                  updated_at=clock_timestamp() WHERE id=? AND version=?
                """,next,next,next,next,reason,id,expectedVersion);
        if (changed!=1) throw fail("ATTENTION_VERSION_CONFLICT",409);
        signalEvent(signal.id(),actor,action,signal.severity(),signal.severity(),signal.status(),next,
                expectedVersion,expectedVersion+1,reason);
        Signal result=findSignal(id);
        saveReceipt(actor,key,hash,"SIGNAL",id,result);
        return result;
    }

    private void upsertSignal(UUID student,UUID trainer,UUID period,String rule,String sourceKey,
                              long sourceVersion,String severity,UUID occurrence,Long occurrenceVersion,
                              Instant start,Instant end,UUID task) {
        List<Signal> existing=jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,signal_type,severity::text,status::text,
                  version,related_entity_id,source_version,evidence::text,created_at
                FROM fitness.attention_signals WHERE student_id=? AND signal_type=? AND source_key=?
                  AND coaching_period_id IS NOT DISTINCT FROM ?
                """,(rs,n)->mapSignal(rs),student,rule,sourceKey,period);
        if (!existing.isEmpty()) {
            Signal old=existing.getFirst();
            if (!Set.of("OPEN","ACKNOWLEDGED").contains(old.status()) || old.severity().equals(severity)) return;
            jdbc.update("""
                    UPDATE fitness.attention_signals SET severity=?::fitness.severity_level,
                      version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?
                    """,severity,old.id(),old.version());
            signalEvent(old.id(),null,"SEVERITY_CHANGED",old.severity(),severity,old.status(),old.status(),
                    old.version(),old.version()+1,null);
            return;
        }
        UUID id=UUID.randomUUID();
        String evidence=write(Map.of("sourceKey",sourceKey,"sourceVersion",sourceVersion,
                "plannedStartAt",Objects.toString(start,""),"plannedEndAt",Objects.toString(end,""),
                "reviewTaskId",Objects.toString(task,"")));
        jdbc.update("""
                INSERT INTO fitness.attention_signals(id,student_id,trainer_id,coaching_period_id,
                  source,signal_type,severity,status,title,explanation,evidence,related_entity_type,
                  related_entity_id,source_key,source_version)
                VALUES (?,?,?,?,'SYSTEM_RULE',?,?::fitness.severity_level,'OPEN',?,?,?::jsonb,?,?,?,?)
                ON CONFLICT DO NOTHING
                """,id,student,trainer,period,rule,severity,
                rule.equals("MISSED_PLANNED_V1")?"Buổi tập dự kiến đã lỡ":"Đánh giá huấn luyện quá hạn",
                "Tín hiệu từ quy tắc, xem dữ liệu nguồn trước khi xử lý",evidence,
                occurrence==null?"COACHING_REVIEW_TASK":"PLANNED_WORKOUT",
                occurrence==null?task:occurrence,sourceKey,sourceVersion);
        if (jdbc.queryForObject("SELECT count(*) FROM fitness.attention_signals WHERE id=?",Long.class,id)==1)
            signalEvent(id,null,"DETECTED",severity,severity,null,"OPEN",null,0,null);
    }

    private void resolveSource(String rule,String sourceKey,String reason) {
        for (Signal old:jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,signal_type,severity::text,status::text,
                  version,related_entity_id,source_version,evidence::text,created_at
                FROM fitness.attention_signals WHERE signal_type=? AND source_key=?
                  AND status IN ('OPEN','ACKNOWLEDGED')
                """,(rs,n)->mapSignal(rs),rule,sourceKey)) {
            jdbc.update("""
                    UPDATE fitness.attention_signals SET status='RESOLVED',version=version+1,
                      resolved_at=clock_timestamp(),resolution_note=?,updated_at=clock_timestamp()
                    WHERE id=? AND version=?
                    """,reason,old.id(),old.version());
            signalEvent(old.id(),null,reason,old.severity(),old.severity(),old.status(),"RESOLVED",
                    old.version(),old.version()+1,reason);
        }
    }

    private List<Signal> openRuleSignals(String rule) {
        return jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,signal_type,severity::text,status::text,
                  version,related_entity_id,source_version,evidence::text,created_at
                FROM fitness.attention_signals WHERE signal_type=? AND status IN ('OPEN','ACKNOWLEDGED')
                """,(rs,n)->mapSignal(rs),rule);
    }

    private Review findReview(UUID id) { return reviewRow(id,false); }
    private Review lockReview(UUID id) { return reviewRow(id,true); }
    private Review reviewRow(UUID id,boolean lock) {
        return jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,cycle_start_date,due_date,timezone,
                  status,version,summary,evidence::text,missing_data::text,completed_at,student_acknowledged_at
                FROM fitness.coaching_review_tasks WHERE id=?
                """+(lock?" FOR UPDATE":""),(rs,n)->mapReview(rs),id).stream().findFirst()
                .orElseThrow(()->fail("REVIEW_NOT_FOUND",404));
    }
    private Signal findSignal(UUID id) { return signalRow(id,false); }
    private Signal lockSignal(UUID id) { return signalRow(id,true); }
    private Signal signalRow(UUID id,boolean lock) {
        return jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,signal_type,severity::text,status::text,
                  version,related_entity_id,source_version,evidence::text,created_at
                FROM fitness.attention_signals WHERE id=? AND source='SYSTEM_RULE'
                  AND signal_type IN ('MISSED_PLANNED_V1','REVIEW_OVERDUE_V1')
                """+(lock?" FOR UPDATE":""),(rs,n)->mapSignal(rs),id).stream().findFirst()
                .orElseThrow(()->fail("ATTENTION_NOT_FOUND",404));
    }
    private Review mapReview(java.sql.ResultSet rs) throws java.sql.SQLException {
        {
            List<String> refs=json.readValue(rs.getString(11),json.getTypeFactory().constructCollectionType(List.class,String.class));
            List<String> missing=json.readValue(rs.getString(12),json.getTypeFactory().constructCollectionType(List.class,String.class));
            return new Review(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getObject(4,UUID.class),rs.getDate(5).toLocalDate(),rs.getDate(6).toLocalDate(),
                    rs.getString(7),rs.getString(8),rs.getLong(9),rs.getString(10),refs,missing,
                    rs.getTimestamp(13)==null?null:rs.getTimestamp(13).toInstant(),
                    rs.getTimestamp(14)==null?null:rs.getTimestamp(14).toInstant());
        }
    }
    private Signal mapSignal(java.sql.ResultSet rs) throws java.sql.SQLException {
        {
            var evidence=json.readTree(rs.getString(11));
            String raw=evidence.path("plannedStartAt").asText("");
            String end=evidence.path("plannedEndAt").asText("");
            String task=evidence.path("reviewTaskId").asText("");
            return new Signal(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getString(7),rs.getLong(8),
                    rs.getString(5).equals("MISSED_PLANNED_V1")?rs.getObject(9,UUID.class):null,
                    rs.getString(5).equals("MISSED_PLANNED_V1") && rs.getObject(10)!=null?rs.getLong(10):null,
                    raw.isEmpty()?null:Instant.parse(raw),
                    end.isEmpty()?null:Instant.parse(end),task.isEmpty()?null:UUID.fromString(task),
                    rs.getTimestamp(12).toInstant());
        }
    }

    private void requireRead(UUID actor,UUID student,UUID period,boolean workoutEvidence) {
        if (actor.equals(student)) { requireStudent(actor); return; }
        requireTrainer(actor,student,period,workoutEvidence,false);
    }
    private UUID listPeriod(UUID actor,UUID student) {
        if (actor.equals(student)) { requireStudent(actor); return null; }
        if (accounts.getAccountStatus(actor).orElse(null)!=AccountStatus.ACTIVE || !trainers.canCoach(actor))
            throw fail("ATTENTION_NOT_FOUND",404);
        var context=contexts.findEffectiveContext(student,false).orElse(null);
        if (context==null || context.mode()!=CurrentCoachingContextQuery.Mode.HUMAN_COACH
                || !actor.equals(context.trainerId()) || context.periodId()==null)
            throw fail("ATTENTION_NOT_FOUND",404);
        return context.periodId();
    }
    private void requireTrainer(UUID actor,UUID student,UUID period,boolean workoutEvidence,boolean lock) {
        if (period==null || accounts.getAccountStatus(actor).orElse(null)!=AccountStatus.ACTIVE
                || !trainers.canCoach(actor)) throw fail("ATTENTION_NOT_FOUND",404);
        var context=contexts.findEffectiveContext(student,lock).orElse(null);
        if (context==null || context.mode()!=CurrentCoachingContextQuery.Mode.HUMAN_COACH
                || !period.equals(context.periodId()) || !actor.equals(context.trainerId()))
            throw fail("ATTENTION_NOT_FOUND",404);
        if (workoutEvidence && !authority.evaluateCurrent(new AuthorityRequest(actor,student,
                DataScope.WORKOUT_HISTORY,DataAccessLevel.VIEW)).allowed())
            throw fail("ATTENTION_NOT_FOUND",404);
    }
    private void requireStudent(UUID actor) {
        if (accounts.getAccountStatus(actor).orElse(null)!=AccountStatus.ACTIVE || !students.hasProfile(actor))
            throw fail("ATTENTION_NOT_FOUND",404);
    }
    private <T> T receipt(UUID actor,UUID key,String hash,Class<T> type) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",actor+":"+key);
        var rows=jdbc.query("""
                SELECT payload_hash,response::text FROM fitness.attention_review_command_receipts
                WHERE actor_id=? AND command_key=?
                """,(rs,n)->Map.entry(rs.getString(1),rs.getString(2)),actor,key);
        if (rows.isEmpty()) return null;
        if (!rows.getFirst().getKey().equals(hash)) throw fail("ATTENTION_IDEMPOTENCY_CONFLICT",409);
        return json.readValue(rows.getFirst().getValue(),type);
    }
    private void saveReceipt(UUID actor,UUID key,String hash,String kind,UUID id,Object response) {
        jdbc.update("""
                INSERT INTO fitness.attention_review_command_receipts(actor_id,command_key,payload_hash,
                  resource_kind,resource_id,response) VALUES (?,?,?,?,?,?::jsonb)
                """,actor,key,hash,kind,id,write(response));
    }
    private void taskEvent(UUID id,UUID actor,String type,Long previous,long result,String detail) {
        jdbc.update("""
                INSERT INTO fitness.coaching_review_task_events(task_id,actor_id,event_type,
                  previous_version,resulting_version,detail) VALUES (?,?,?,?,?,?::jsonb)
                """,id,actor,type,previous,result,detail);
    }
    private void signalEvent(UUID id,UUID actor,String type,String beforeSeverity,String afterSeverity,
                             String beforeStatus,String afterStatus,Long previous,long result,String reason) {
        jdbc.update("""
                INSERT INTO fitness.attention_signal_events(signal_id,actor_id,event_type,
                  previous_severity,resulting_severity,previous_status,resulting_status,
                  previous_version,resulting_version,reason)
                VALUES (?,?,?,?::fitness.severity_level,?::fitness.severity_level,
                  ?::fitness.attention_status,?::fitness.attention_status,?,?,?)
                """,id,actor,type,beforeSeverity,afterSeverity,beforeStatus,afterStatus,previous,result,reason);
    }
    private static ZoneId zone(String value) {
        try { if (value==null || !value.contains("/")) throw new DateTimeException("IANA zone required");
            return ZoneId.of(value); }
        catch(DateTimeException bad) { throw fail("REVIEW_TIMEZONE_INVALID",400); }
    }
    private String write(Object value) {
        return json.writeValueAsString(value);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void checkPage(int page,int size) {
        if (page<0 || size<1 || size>50 || (long)page*size>Integer.MAX_VALUE)
            throw fail("VALIDATION_FAILED",400);
    }
    private static AttentionReviewFailure fail(String code,int status) {
        return new AttentionReviewFailure(code,status);
    }
}
