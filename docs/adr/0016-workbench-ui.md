# 0016. Workbench UI design system

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Feedback on the first UI: too sparse (large cards and padding, little information per screen), it
looked like a toy rather than an engineering tool, and the visual details were weak (raw values like
`IN_WORK`, loud pills). Engineers live in these screens all day and need density and speed.

## Decision
A **dense engineering workbench**:

- **Shell:** a 40px dark top bar with **global search** (number or name across every readable
  context; arrow keys, Enter, "/" to focus), a navigator tree with icons, and the page.
- **Page anatomy**, the same for every object and context page: header strip (breadcrumbs, type icon,
  **number · name**, version, lifecycle state, lock) → **toolbar** (icon buttons, then a More menu)
  → tabs → content of bordered **panels** with small uppercase headers.
- **Toolbar actions stay visible and are disabled** when they don't apply, rather than appearing
  and disappearing. The toolbar layout stays stable, and users can see what exists.
- **Density:** 13px Inter, 28px controls, 30px table rows, property grids (label | value) instead of
  cards, 4px radii, borders instead of shadows. Help text sits behind ⓘ tooltips and is still exposed
  to screen readers through `aria-describedby`.
- **Tables:** sticky headers, sortable columns, row hover, a filter box and a count in the panel
  header. The number is the visual link; names stay in text colour.
- **Values read like engineering data:** states as "● In Work" (dot colour by state, plain text),
  value-list labels, units, exact identifiers (no contextual ligatures), tabular figures only in
  version and date columns (globally they widen hyphens in "FQ-1000").
- **Tokens only:** colours, sizes and radii are CSS variables with a full dark theme. Components
  never hard-code colours.
- Pages remount per object/context id, so no state (dialogs, drafts) leaks between pages.

## Consequences
+ Roughly 2–3× more information per screen. Every later screen (lifecycle, structure, change)
  reuses the same header/toolbar/panel/grid building blocks.
− Hand-rolled components for now. When the BOM tree table arrives (Phase 3), a data-grid library
  (AG Grid) takes over large tables. The look stays defined by these tokens.
