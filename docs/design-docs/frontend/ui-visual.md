# Frontend UI and Visual Design

PixFlow uses a restrained light operational interface built from project tokens and accessible headless primitives. Visual treatment supports repeated work: compact headings, stable controls, clear status, and dense but readable lists.

## Layout

Desktop uses the three-area shell. Tablet and phone behavior follows `product.md`; fixed side widths must never squeeze main content below its usable minimum. Boards, toolbars, icon buttons, counters, and gallery cells use stable responsive dimensions so status changes do not shift layout.

The Composer remains visible above the visual viewport and safe area. Queue rows, Proposal cards, Activity details, and gallery actions wrap or become single-column on narrow screens.

Materials image detail gives the image the flexible main area and Product Visual Analysis an approximately 420 px independently scrolling right column on desktop. Tablet keeps a proportional split; phone stacks the image and form with a sticky Save/Cancel action area. Previous/next image controls remain visually prominent and keyboard reachable at every width.

## Components

Use icons for familiar commands, tooltips for unfamiliar icon-only actions, segmented controls for view modes, and tabs only for peer views. Confirmation dialogs are reserved for irreversible deletion; the Product Visual Analysis leave guard is a three-choice unsaved-work decision dialog, not a generic confirmation. Cards are reserved for repeated items, Proposal records, and Activity records; page sections are not nested cards.

Product Visual Analysis uses labelled inputs and textareas rather than a JSON editor. Active analysis uses one restrained spinner and the text `正在分析商品图片`; existing fields remain visible but read-only during reanalysis. Reanalyze is disabled while analysis runs or the form is dirty. Writer and update time are secondary metadata, not status cards.

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
