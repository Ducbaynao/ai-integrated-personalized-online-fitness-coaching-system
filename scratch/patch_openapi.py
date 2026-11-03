with open('contracts/openapi/openapi.yaml', 'r', encoding='utf-8') as f:
    content = f.read()

paths_insertion = """  /ai/workout-adjustments:
    post:
      tags: [AI Workout Adjustment (F03)]
      operationId: generateWorkoutAdjustment
      summary: Generate contextual workout adjustment proposal (F03)
      description: >-
        Requests an AI-assisted workout adjustment proposal for an OCCURRENCE or TEMPLATE.
        In SELF_DIRECTED mode, the student requests for self.
        In HUMAN_COACH mode, the assigned trainer requests for the student.
        Returns a PENDING recommendation with structured workout adjustment payload.
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/WorkoutAdjustmentRequest' }
      responses:
        '200':
          description: Adjustment proposal generated successfully in PENDING status
          content:
            application/json:
              schema: { $ref: '#/components/schemas/WorkoutAdjustmentResponse' }
        '400': { $ref: '#/components/responses/ValidationError' }
        '401': { $ref: '#/components/responses/Unauthenticated' }
        '403': { $ref: '#/components/responses/AIForbidden' }
        '503': { $ref: '#/components/responses/ServiceUnavailable' }

  /ai/workout-adjustments/{recommendationId}:
    get:
      tags: [AI Workout Adjustment (F03)]
      operationId: getWorkoutAdjustmentDetail
      summary: Get AI workout adjustment recommendation detail by ID
      security:
        - bearerAuth: []
      parameters:
        - name: recommendationId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Workout adjustment recommendation detail
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AiRecommendationDetailResponse' }
        '401': { $ref: '#/components/responses/Unauthenticated' }
        '403': { $ref: '#/components/responses/AIForbidden' }
        '404':
          description: Recommendation not found
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }

  /ai/workout-adjustments/{recommendationId}/accept:
    post:
      tags: [AI Workout Adjustment (F03)]
      operationId: acceptWorkoutAdjustment
      summary: Atomic accept-and-apply workout adjustment recommendation (F03)
      description: >-
        Executes atomic Accept-and-Apply transaction for workout adjustment. Locks recommendation,
        verifies authority and expiry, executes domain commands on occurrence or template, and records application receipt.
      security:
        - bearerAuth: []
      parameters:
        - name: recommendationId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      requestBody:
        required: false
        content:
          application/json:
            schema: { $ref: '#/components/schemas/AcceptAdjustmentRequest' }
      responses:
        '200':
          description: Workout adjustment accepted and applied successfully
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AcceptAdjustmentResponse' }
        '400': { $ref: '#/components/responses/ValidationError' }
        '401': { $ref: '#/components/responses/Unauthenticated' }
        '403': { $ref: '#/components/responses/AIForbidden' }
        '404':
          description: Recommendation not found
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
        '409':
          description: Conflict (expired recommendation, status not PENDING, target version mismatch, or occurrence already attempted/completed)
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }

  /ai/workout-adjustments/{recommendationId}/reject:
    post:
      tags: [AI Workout Adjustment (F03)]
      operationId: rejectWorkoutAdjustment
      summary: Reject workout adjustment recommendation (F03)
      description: >-
        Rejects a PENDING workout adjustment recommendation with reason and audit trail.
      security:
        - bearerAuth: []
      parameters:
        - name: recommendationId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      requestBody:
        required: false
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RejectAdjustmentRequest' }
      responses:
        '200':
          description: Workout adjustment rejected successfully
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RejectAdjustmentResponse' }
        '400': { $ref: '#/components/responses/ValidationError' }
        '401': { $ref: '#/components/responses/Unauthenticated' }
        '403': { $ref: '#/components/responses/AIForbidden' }
        '404':
          description: Recommendation not found
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
        '409':
          description: Recommendation not PENDING
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }

"""

target_path_anchor = '  /admin/content/knowledge/import:'
if target_path_anchor not in content:
    raise Exception('target_path_anchor not found')

content = content.replace(target_path_anchor, paths_insertion + target_path_anchor, 1)

schemas_insertion = """    WorkoutAdjustmentRequest:
      type: object
      required: [commandKey, scope, target]
      properties:
        studentId:
          type: string
          format: uuid
        commandKey:
          type: string
        scope:
          type: string
          enum: [OCCURRENCE, TEMPLATE]
        target:
          type: object
          required: [id, type]
          properties:
            id:
              type: string
              format: uuid
            type:
              type: string
              enum: [PLANNED_WORKOUT_OCCURRENCE, WORKOUT_PLAN_TEMPLATE]
            version:
              type: integer
              format: int64
              nullable: true
        requestedChangeType:
          type: string
          enum: [SUBSTITUTE_EXERCISE, ADJUST_LOAD_OR_REPS, REDUCE_VOLUME, ALTERNATIVE_FLOW]
        userReason:
          type: string
        physicalLimitations:
          type: array
          items:
            type: string
        equipmentAvailable:
          type: array
          items:
            type: string
        allowLoadIncrease:
          type: boolean

    WorkoutAdjustmentResponse:
      type: object
      required: [recommendationId, runId, status, decisionAuthority, title, explanation, expiresAt, createdAt, structuredPayload]
      properties:
        recommendationId:
          type: string
          format: uuid
        runId:
          type: string
          format: uuid
        status:
          type: string
          enum: [PENDING, ACCEPTED, REJECTED, EXPIRED, APPLIED]
        decisionAuthority:
          type: string
          enum: [STUDENT, TRAINER]
        title:
          type: string
        explanation:
          type: string
        expiresAt:
          type: string
          format: date-time
        createdAt:
          type: string
          format: date-time
        structuredPayload:
          type: object

    AcceptAdjustmentRequest:
      type: object
      properties:
        commandKey:
          type: string
        decisionNote:
          type: string
        expectedRevision:
          type: integer
          minimum: 1
        expectedTargetVersion:
          type: integer
          format: int64

    AcceptAdjustmentResponse:
      type: object
      required: [recommendationId, status, appliedBy, resultingEntityType, resultingEntityId, appliedAt, replayed]
      properties:
        recommendationId:
          type: string
          format: uuid
        status:
          type: string
          enum: [APPLIED]
        appliedBy:
          type: string
          format: uuid
        resultingEntityType:
          type: string
        resultingEntityId:
          type: string
          format: uuid
        appliedAt:
          type: string
          format: date-time
        replayed:
          type: boolean

    RejectAdjustmentRequest:
      type: object
      properties:
        reason:
          type: string

    RejectAdjustmentResponse:
      type: object
      required: [recommendationId, status, rejectedBy, rejectedAt]
      properties:
        recommendationId:
          type: string
          format: uuid
        status:
          type: string
          enum: [REJECTED]
        rejectedBy:
          type: string
          format: uuid
        reason:
          type: string
        rejectedAt:
          type: string
          format: date-time

"""

target_schema_anchor = 'security:\n  - bearerAuth: []'
if target_schema_anchor not in content:
    target_schema_anchor = 'security:\r\n  - bearerAuth: []'

if target_schema_anchor not in content:
    raise Exception('target_schema_anchor not found')

content = content.replace(target_schema_anchor, schemas_insertion + target_schema_anchor, 1)

with open('contracts/openapi/openapi.yaml', 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('Successfully patched openapi.yaml')
