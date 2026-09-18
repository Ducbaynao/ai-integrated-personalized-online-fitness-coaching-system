# Governance and audit

## Admin permission model

The target product composes Administrator roles from permissions instead of relying on one unlimited `ADMIN`. Example roles include `SUPER_ADMIN`, `USER_ADMIN`, `TRAINER_VERIFICATION_ADMIN`, `CONTENT_ADMIN`, `AI_ADMIN`, `SUPPORT_ADMIN`, and `AUDIT_VIEWER`.

Permissions may include account suspension, Trainer verification, Exercise management, Knowledge publishing, AI Run viewing, Audit viewing, and system configuration. Backend checks the specific permission for each operation.

## Trainer governance

Trainer Application supports draft, submission, review, request-more-information, approval, rejection, and withdrawal. Verification state is separate from operational activity. Trainer Certification records issuer, certificate number, issue/expiry date, document reference, verification state, reviewer, and review time.

Expiry/revocation retains history and prevents new authority according to policy; it does not erase the Trainer Profile or past coaching records.

## Content and knowledge governance

Exercise content uses draft/active/archived lifecycle. Referenced content is archived rather than hard-deleted. Duplicate detection and canonical mapping/merge preserve historical references and provenance.

Knowledge follows import/create, metadata, processing/chunking/embedding, review, and publish. Publishing creates an immutable active version for traceability; changes use a new draft version.

## Moderation support and correction

User Report/Moderation Case retains reporter, subject, category, evidence, previous cases, status, actions, reasons, actors, and timestamps. Outcomes may include dismiss, warn, restrict, suspend, or escalate according to policy.

Support Case records its lifecycle and communications. If a system defect requires business-data correction, Admin uses a Data Correction Action rather than a direct table edit. The owning domain validates the correction, and audit retains before/after, reason, actor, and case reference.

## Privileged data access

Private chat, Progress Photos, detailed Nutrition, Measurements, and other sensitive resources are not visible merely because a person is an Administrator. Support, moderation, security, or legal/compliance access uses an explicit permission and a Privileged Data Access record when policy requires it.

The record identifies Administrator, purpose/reason, exact scope, target resource/person, start, expiry, and audit events. Access should be time-bound and minimized.

## Audit

Important records include:

- authentication/security and permission changes;
- account suspension, disablement, deletion/anonymization;
- Trainer approval, rejection, revocation, or certification change;
- Goal/Plan/Target proposal decisions and important version changes;
- privileged reads and Data Correction Actions;
- Exercise/Knowledge publishing or archive;
- AI Recommendation decisions and application results;
- critical configuration/feature-flag changes;
- moderation and support actions.

Audit records identify actor, action, target, time, request/correlation reference, reason/purpose where relevant, and a safe representation of change. Admin UI may search/export only with permission and cannot edit/delete audit history. Retention is controlled by system policy.

