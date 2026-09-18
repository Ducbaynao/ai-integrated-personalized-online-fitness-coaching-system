# Permissions and authority

Authorization answers both **may this identity access the resource** and **may it make this decision now**.

## Decision authority matrix

| Decision | SELF_DIRECTED | HUMAN_COACH | AI | Administrator |
| --- | --- | --- | --- | --- |
| Create/activate Student Fitness Goal | Student | Student | Propose only | No coaching authority |
| Change Fitness Goal/target | Student | Student accepts Trainer proposal | Propose only | No coaching authority |
| Create/activate Workout Plan | Student | Eligible Trainer | Propose only | No coaching authority |
| Record Actual Workout | Student | Student | No | Support correction only through audited workflow |
| Strategic Nutrition change | Student | Student accepts Trainer proposal | Propose only | No coaching authority |
| Confirm AI-recognized food | Student | Student | Estimate only | No |
| Change Coaching Appointment | Parties through request/policy | Parties through request/policy | Suggest only | Support intervention only through workflow |
| Publish Exercise/Knowledge content | Not applicable | Not applicable | No | Authorized Content/Knowledge Admin |
| Apply AI Recommendation | Student | Trainer for plan decisions; Student for Student-owned goals | No | No |

## Trainer access checks

Every Trainer request concerning a Student must validate:

1. authenticated account and required Trainer permission/capability;
2. verified and active Trainer state;
3. active Coaching Relationship or another explicit access basis;
4. correct Coaching Period and effective time;
5. Data Sharing Permission for the requested domain;
6. access level such as view, contribute, or manage;
7. whether pre-coaching history is included;
8. object ownership/version and operation-specific business rules.

Ending a relationship removes authority over future Student data. It does not delete historical records or necessarily hide records the Trainer legitimately authored; precise retention/read policy must be encoded explicitly.

## Data Sharing Permission

A permission should be able to express:

- relationship and Student;
- data domain or resource type, such as Measurements, Nutrition, Photos, or Workout History;
- access level;
- valid-from and valid-until/time scope;
- whether history before the relationship may be read;
- grant/revoke actor and timestamps;
- policy or consent reference where required.

Permission checks are server-side. Client navigation and hidden controls improve UX but do not enforce security.

## Administrator access

Administrator roles are permission bundles such as `USER_ADMIN`, `TRAINER_VERIFICATION_ADMIN`, `CONTENT_ADMIN`, `AI_ADMIN`, `SUPPORT_ADMIN`, or `AUDIT_VIEWER`. Sensitive permissions may include `USER_SUSPEND`, `TRAINER_VERIFY`, `KNOWLEDGE_PUBLISH`, `AI_RUN_VIEW`, `AUDIT_VIEW`, and `SYSTEM_CONFIG_MANAGE`.

Private chat, Progress Photos, detailed Nutrition, Measurements, or other sensitive resources require an explicit permitted purpose. When policy requires privileged access, record Admin identity, reason, target, exact scope, open/expiry time, and resulting audit events.

High-risk operations such as changing Admin permissions, suspending accounts, revoking Trainer verification, publishing Knowledge, deleting/anonymizing a User, or changing critical configuration require step-up authentication under the security policy.

