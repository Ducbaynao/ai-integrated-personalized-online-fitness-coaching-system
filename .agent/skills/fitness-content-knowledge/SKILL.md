---
name: fitness-content-knowledge
description: Design or implement the Exercise Library, exercise variations, media, content governance, Nutrition Database content, and versioned RAG Knowledge Base publishing workflow.
---

# Fitness Content and Knowledge

Read `digital-fitness-core` first.

## Exercise library

Model canonical Exercise records with name, description, difficulty, movement pattern, primary/secondary muscles, equipment, instructions, media, common mistakes, and tags. Represent variations and replacements explicitly so filters and AI can reason about equipment and movement similarity.

Use `DRAFT`, `ACTIVE`, and `ARCHIVED`. Never hard-delete an Exercise referenced by a plan or log. For duplicates, merge through canonical mapping while preserving historical references and provenance.

Store exercise images and videos in object storage; PostgreSQL stores metadata and URLs. Validate media ownership, type, size, and access.

## Knowledge Base

Use `KnowledgeDocument -> KnowledgeVersion -> KnowledgeChunk -> Embedding`. Store title, source type, author/publisher, source date, evidence/review status, reviewer, version, publication time, and lifecycle.

Publishing flow: create/import, metadata, processing/chunking/embedding, review, then publish. AI retrieves only authorized `ACTIVE` versions. Never edit an active version in place; create a draft version, review/publish it, and archive the previous version according to policy.

Keep chunk and embedding traceability to the exact Knowledge Version. Re-embedding must not destroy the human-readable source/version lineage.

## Nutrition content

Maintain structured foods with canonical name, serving, calories, protein, carbohydrate, fat, source, and category. Support external sources and curated Vietnamese foods. Record source and revision provenance; deterministic calculations use the structured record, not free-form LLM values.

## Governance

Separate content authoring, review, publish, archive, and audit permissions. Publishing or merging is privileged and should be validated and audited. Design moderation and correction workflows so content fixes do not rewrite historical user logs unexpectedly.
