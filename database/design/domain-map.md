# Database Domain Map

This map groups the physical PostgreSQL schema by business boundary. It is a navigation aid for developers and coding agents; Flyway migrations in `services/backend/src/main/resources/db/migration` remain the executable schema history.

## Authority rules

- PostgreSQL is the system of record for normalized business state.
- Spring Boot application services enforce business authority and lifecycle transitions.
- Student owns personal Fitness Goals and Nutrition Goals.
- An eligible Trainer controls Workout Plans only within authorized `HUMAN_COACH` scope.
- AI tables store runs, evidence, recommendations, and evaluation; they do not grant business authority.
- Admin tables support Platform Authority, least privilege, audited support, and governance. They do not grant Coaching Authority.
- Historical, versioned, applied, audit, and evidence records are not overwritten to make current state appear simpler.

## Domain ownership

| Domain | Main physical tables | Business authority and notes | Primary migrations |
| --- | --- | --- | --- |
| Identity and access | `users`, `roles`, `permissions`, `user_roles`, `role_permissions`, `auth_identities`, `refresh_tokens`, `one_time_tokens`, `user_devices`, `user_settings`, `session_revocation_events` | User identity plus platform security policy. Profile existence is not coaching authority. Tokens are stored as hashes where applicable. | V2, V16 |
| Account security and lifecycle | `user_account_status_history`, `mfa_factors`, `step_up_authentications`, `security_events` | Platform-authorized lifecycle and security evidence. Sensitive Admin actions may require step-up authentication. | V14 |
| Student profile | `student_profiles`, `student_availability_windows`, `student_movement_limitations`, `student_equipment`, `student_exercise_preferences` | Student-managed facts and preferences; health-adjacent data requires scoped access. Coaching Mode is not stored here. | V2, V3 |
| Trainer profile and eligibility | `trainer_profiles`, `trainer_certificates`, `trainer_specialties`, `trainer_specialty_assignments`, `trainer_applications`, `trainer_application_certificates`, `trainer_verification_documents`, `trainer_application_status_history`, `trainer_activity_status_history` | Platform verifies/activates capability. Profile creation or role assignment alone does not permit coaching. | V2, V10, V14, V18 |
| Measurement catalog | `measurement_units`, `metric_definitions`, `measurement_methods`, `measurement_sources`, `metric_supported_methods`, `measurement_source_priorities` | Platform-governed catalog used by all measurement sources; definitions do not contain observations. | V3, V16 |
| Exercise catalog | `exercise_categories`, `muscle_groups`, `equipment`, `exercises`, `exercise_variations`, `exercise_muscles`, `exercise_equipment`, `exercise_media`, `exercise_tags`, `exercise_tag_assignments`, `exercise_guidance`, `exercise_canonical_mappings`, `exercise_merge_events` | Platform-governed content. Referenced exercises are archived or canonically mapped, not destructively deleted. | V3, V15, V16 |
| Food and nutrient catalog | `food_categories`, `nutrients`, `foods`, `food_nutrients` | Platform-governed deterministic nutrition reference data. AI estimates do not replace confirmed food identity and quantity. | V3 |
| Coaching relationship | `coaching_relationships`, `coaching_relationship_status_history`, `coaching_periods`, `data_sharing_permissions` | Student-Trainer relationship and time-bounded decision authority. Only `SELF_DIRECTED` and `HUMAN_COACH` are valid modes, recorded by Coaching Period. | V4 |
| Fitness Goal | `goal_types`, `fitness_goals`, `fitness_goal_status_history`, `fitness_goal_versions`, `goal_objectives`, `goal_targets`, `goal_proposals`, `goal_proposal_objectives`, `goal_proposal_targets`, `goal_transitions`, `goal_coaching_periods`, `goal_type_metric_requirements` | Student owns the Goal. Trainer/AI changes use a Proposal and Student confirmation. Versions and transitions preserve history. | V4, V16 |
| Workout templates and plans | `workout_templates`, `workout_template_versions`, `workout_template_sessions`, `workout_template_session_exercises`, `workout_plans`, `workout_plan_versions`, `workout_plan_sessions`, `workout_plan_session_exercises`, `workout_session_adjustments` | Student controls self-directed plans; eligible Trainer controls plans in valid human coaching. Significant changes version the plan; minor adjustments retain change evidence. | V5, V13 |
| Workout execution | `planned_workouts`, `workout_session_logs`, `exercise_logs`, `set_logs` | Planned intent and actual execution remain separate. Planned date, performed date, completion, and schedule adherence are separate facts. | V5 |
| Schedule | `trainer_availability_rules`, `trainer_availability_exceptions`, `recurring_schedules`, `appointments`, `reschedule_requests`, `appointment_history` | Student and Trainer collaborate within authorization. Appointment lifecycle does not implicitly mutate Workout lifecycle. | V5 |
| Media | `media_files` | PostgreSQL stores ownership, object key, type, size, visibility, and lifecycle metadata; bytes remain in object storage. | V6 |
| Measurement observations | `integration_providers`, `integration_connections`, `ingestion_batches`, `raw_ingestion_records`, `measurement_batches`, `measurements`, `measurement_validation_events`, `measurement_checkins`, `measurement_checkin_requirements`, `measurement_checkin_batches`, `measurement_deduplication_groups`, `measurement_deduplication_members`, `measurement_conflict_resolutions` | Student/system observations retain source, method, provenance, quality, validation, and conflict evidence. Missing values are unknown, never zero. | V6, V16 |
| Progress and continuity | `progress_photos`, `training_activity_periods`, `return_to_training_assessments`, `progress_snapshots`, `progress_metric_values`, `goal_progress_checkpoints`, `goal_checkpoint_values`, `daily_training_aggregates`, `adherence_snapshots` | Deterministic Progress Engine outputs precede AI context. Derived records retain calculation version, evidence, and source window. | V6 |
| Nutrition goals and targets | `nutrition_goal_types`, `nutrition_goals`, `nutrition_goal_status_history`, `nutrition_goal_versions`, `nutrition_proposals`, `nutrition_proposal_targets`, `daily_nutrition_targets`, `daily_nutrition_target_overrides`, `resolved_daily_nutrition_targets`, `nutrition_review_requests` | Student owns strategic objectives. Trainer/AI changes use Proposals and Student approval. Strategic versions and one-day overrides are different lifecycles. | V7, V13, V16 |
| Nutrition actuals | `meals`, `food_log_items`, `food_log_item_nutrients`, `daily_nutrition_summaries`, `daily_nutrition_log_status`, `food_analysis_requests`, `food_analysis_candidates` | Actual intake is separate from targets. No log, partial log, estimated food, and confirmed food remain distinguishable. | V7, V13 |
| Coaching review and attention | `attention_signals`, `coaching_review_schedules`, `coaching_reviews` | Deterministic rule signals are traceable to evidence and remain distinct from AI Recommendations. Trainer workflows use management by exception. | V8, V13 |
| Chat | `conversations`, `conversation_members`, `messages`, `message_attachments`, `message_read_receipts` | Participants communicate under relationship/privacy authorization. Persisted messages are durable facts; presence/typing is ephemeral. | V8 |
| Notification and events | `notification_preferences`, `notifications`, `notification_deliveries`, `outbox_events` | Notification reacts to committed domain events and is not the source of truth for transitions. Outbox consumers must be idempotent. | V8 |
| AI models and runs | `ai_models`, `ai_model_versions`, `prompt_templates`, `prompt_versions`, `ai_runs`, `ai_context_snapshots`, `ai_run_input_references`, `ai_run_knowledge_chunks`, `ai_validation_results`, `ai_recommendations`, `ai_recommendation_applications`, `ai_feedback`, `ai_threads`, `ai_thread_messages` | AI Assistance only. Spring validates structured output; authorized humans decide applicable changes. Replay/evaluation never changes Student business state. | V9 |
| Knowledge and RAG | `knowledge_documents`, `knowledge_versions`, `knowledge_chunks`, `knowledge_embeddings`, `knowledge_version_reviews`, `knowledge_processing_runs` | Admin-governed, versioned knowledge. Retrieval uses only eligible active versions. | V9, V15 |
| AI evaluation governance | `ai_evaluation_datasets`, `ai_evaluation_cases`, `ai_evaluation_runs`, `ai_evaluation_results`, `ai_replay_requests`, `ai_evaluation_comparisons` | Offline/system evaluation and replay evidence. No direct business application. | V15 |
| Admin governance | `user_reports`, `user_report_attachments`, `admin_actions`, `admin_action_policies`, `system_configurations`, `feature_flags`, `feature_flag_rollouts` | Permission-, scope-, and purpose-based Platform Authority with validation and audit. | V10, V14, V16 |
| Moderation and support | `moderation_cases`, `moderation_actions`, `support_cases`, `support_case_events`, `privileged_data_access_requests`, `privileged_data_access_events`, `data_correction_actions` | Support workflows cannot bypass domain validation. Sensitive access is reasoned, scoped, time-bound, and audited. | V14 |
| Audit, jobs, and integrations | `audit_logs`, `background_jobs`, `job_executions`, `integration_status_events` | Audit is immutable from Admin UI. Jobs expose controlled status/retry behavior without becoming business authority. | V10, V14 |
| Commercial | `trainer_packages`, `trainer_package_versions`, `subscriptions`, `payments`, `payment_transactions`, `invoices`, `invoice_items`, `trainer_reviews` | Later-phase commercial boundary built on identity, coaching relationship, versioning, and audit. | V10 |
| Data deletion | `data_deletion_requests` | Account deletion follows retention, legal/policy, anonymization, and audit workflow rather than immediate uncontrolled hard deletion. | V10 |
| Organizations | `organizations`, `organization_locations`, `organization_memberships` | Later-phase organization boundary; membership does not override Student/Trainer authority rules. | V11 |
| Computer vision | `pose_analysis_sessions`, `pose_rep_events`, `movement_feedback`, `pose_keypoint_artifacts` | Phase 4 assistance with explicit safety limits. It does not replace qualified supervision or become the source of workout truth. | V11 |

## Migration index

| Migration | Purpose |
| --- | --- |
| `V1__foundation.sql` | Schema, extensions, enums, and reusable domains |
| `V2__identity_and_profiles.sql` | Identity, access, sessions, settings, Student and Trainer profiles |
| `V3__fitness_catalogs.sql` | Measurement, exercise, equipment, and food catalogs |
| `V4__coaching_and_goals.sql` | Coaching authority, permissions, Fitness Goals, versions, proposals, and transitions |
| `V5__workouts_and_schedule.sql` | Templates, plans, execution logs, availability, appointments, and changes |
| `V6__measurements_and_progress.sql` | Media, ingestion, Measurements, continuity, and Progress Engine storage |
| `V7__nutrition.sql` | Nutrition goals, targets, food logs, analysis candidates, and summaries |
| `V8__communication_and_notifications.sql` | Chat, notification, Attention Signals, and outbox |
| `V9__ai_and_knowledge.sql` | AI runs/recommendations and versioned RAG knowledge |
| `V10__admin_commercial_and_audit.sql` | Trainer verification, platform governance, commercial records, audit, and deletion |
| `V11__organizations_and_computer_vision.sql` | Organization and future movement-analysis boundaries |
| `V12__integrity_indexes_and_views.sql` | Cross-domain integrity, indexes, and operational views |
| `V13__nutrition_schedule_and_coaching_review.sql` | Missing nutrition lifecycle, plan adjustments, and coaching review |
| `V14__admin_governance_and_security.sql` | Admin permission workflows, moderation/support, jobs, MFA, and security events |
| `V15__content_and_ai_governance.sql` | Content merge/version review and AI evaluation/replay governance |
| `V16__domain_completeness_and_invariants.sql` | Remaining catalogs, check-ins, deduplication, policies, and session revocation |
| `V17__seed_initial_roles.sql` | Seed initial system roles (STUDENT, TRAINER, ADMIN) |
| `V18__trainer_application_submission_schema.sql` | Trainer verification state alignment, applicant note, certificates junction, and unique pending application constraint |

## Change rules

1. Never edit an applied migration; add the next ordered migration starting with `V19`.
2. A table belongs to one owning module even when other modules read its published contract.
3. Modules do not access another module's repository directly.
4. Cross-module changes use application services, commands, or committed domain events.
5. New physical tables or important ownership changes must update this map and `docs/04-database/data-model.md`.
6. Do not add a permanent Coaching Mode field to `student_profiles`, collapse Goal/Plan/Target history, merge Appointment with Workout, or encode missing observations as zero.
