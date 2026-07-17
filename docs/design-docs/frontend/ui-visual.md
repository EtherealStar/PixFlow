# Frontend UI and Visual Design

PixFlow uses a restrained light operational interface built from project tokens and accessible headless primitives. Visual treatment supports repeated work: compact headings, stable controls, clear status, and dense but readable lists.

## Layout

Desktop uses the three-area shell. Tablet and phone behavior follows `product.md`; fixed side widths must never squeeze main content below its usable minimum. Boards, toolbars, icon buttons, counters, and gallery cells use stable responsive dimensions so status changes do not shift layout.

The Composer remains visible above the visual viewport and safe area. Queue rows, Proposal cards, Activity details, and gallery actions wrap or become single-column on narrow screens.

## Components

Use icons for familiar commands, tooltips for unfamiliar icon-only actions, segmented controls for view modes, tabs only for peer views, and confirmation dialogs only for irreversible deletion. Cards are reserved for repeated items, Proposal records, and Activity records; page sections are not nested cards.

Mention tokens have a distinct atomic style, keyboard selection, visible focus, and one-step removal. Disabled Proposal actions explain that the Agent turn is still completing.

## Status language

Status labels use the canonical product terms in `product.md`. Raw enum values, tool names, storage keys, reference keys, stack traces, and trace IDs are not normal interface text. Failed operations may show a copyable error number.

## Accessibility

All functions are keyboard reachable. Drawers and dialogs trap and restore focus. Live progress uses restrained ARIA live regions; streaming token deltas do not announce every fragment. Color is never the only status signal.

## Invariants

1. Only light theme is supported unless a later design explicitly adds another theme.
2. Product UI uses the existing icon system or installed Lucide icons, never emoji.
3. Business components use design tokens rather than hard-coded colors.
4. Text and controls must not overlap at desktop, tablet, or mobile widths.
