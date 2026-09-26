# UI Implementation Workflow

## Prepare the task

1. Load `digital-fitness-core`, then read the relevant UI/UX overview, actor flow, screen specification, inventory, and traceability row.
2. Name the actor, feature, surface, screen ID, navigation entry, and business capability.
3. List data fields with source, optionality, authority, and quality/provenance needs. List user actions and backend transitions.
4. Define loading, first-use empty, no-result, error, permission, partial/stale-data, mutation, and success states that apply.

## Inspect Figma through MCP

1. Start from an exact Figma file/page/frame URL or a documented file key and node ID. Never guess a node.
2. Retrieve metadata only to locate relevant frames. Retrieve design context with its screenshot for each frame/component used as an implementation reference.
3. If the response is sparse, inspect the visible child node IDs and retrieve their design context before implementation.
4. Inspect variable definitions and reusable components for the selected node. Record exact values and node IDs as `VERIFIED` evidence.
5. Record missing actors, states, or components as `NOT FOUND IN INSPECTED NODES`; do not infer that they exist elsewhere in the file.
6. Compare the verified design with `docs/06-ui-ux`. Product rules and project tokens remain authoritative when the preset conflicts with them.

The current SuperFit file/node evidence and known gaps are maintained in `../../../docs/06-ui-ux/references/superfit-reference.md`. Re-inspect the relevant node when implementation depends on visual detail; the reference is an audit trail, not a substitute for MCP context.

## Map design to code

1. Inspect existing Mobile/Admin components, token sources, dependencies, and styling conventions before creating anything.
2. Build a mapping from each verified Figma component to an existing project component, a composed variant, or a justified new reusable component.
3. Keep Exercise, Workout Plan, Planned Workout, Actual Workout, Coaching Appointment, System Alert, and AI Recommendation as separate product concepts even when the preset uses a generic card name.
4. Adapt Figma geometry to responsive React Native/Admin Web layout. Do not copy generated React/Tailwind code, temporary asset URLs, template copy, or absolute positioning into production code.
5. Use repository assets or download required Figma assets through the supported MCP flow. Do not leave temporary Figma asset URLs in source.

## During coding

- Keep screen containers thin; place reusable visuals in the design system.
- Keep server state in query/mutation layer and local ephemeral state in the screen/form layer.
- Render backend status values explicitly; do not collapse distinct domain states into booleans.
- Gate actions by server-provided capability/permission, not only role labels in the client.
- Use locale-aware date/time and display timezone where ambiguity matters.

## Verify

1. Exercise the relevant navigation/deep-link path and every applicable state.
2. Check mobile responsiveness, safe areas, keyboard behavior, dynamic type, touch targets, and screen-reader labels; check Admin Web width, keyboard navigation, focus, table overflow, and permission-based actions.
3. Compare the rendered screen side-by-side with the retrieved Figma screenshot in an emulator/browser. Separate functional bugs, implementation mismatches, missing Figma design, and unresolved product decisions.
4. Update design tokens, component specifications, screen inventory, traceability, or the SuperFit audit only when verified evidence or the product contract changed.

## Figma write policy

- Treat Figma as read-only unless the current task explicitly requires a design change and write capability has been verified.
- Before any write, identify the exact file, page, and frame; describe the proposed change and prefer duplicating the source frame.
- Write to the Figma canvas only after the user confirms the proposed target and change. Read access does not imply write access.

## Pull request evidence

Include:

- Screen IDs and requirement rows affected.
- Screenshots for default and non-happy states.
- Accessibility checks.
- Explanation of authority-sensitive actions.
- Updates to docs/tokens when a new pattern was introduced.

