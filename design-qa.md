# M1 Design QA

## Evidence

- Source market visual: `D:\2026.8\SkillCenter\ChatGPT Image 2026年8月12日 16_27_36.png` (1402 × 1122 px).
- Source detail visual: `D:\2026.8\SkillCenter\ChatGPT Image 2026年8月12日 16_09_09.png` (1086 × 1448 px).
- Rendered market visual: `D:\2026.8\SkillCenter\qa\implementation-market-target.png` (1387 × 1182 px; CSS viewport 1402 × 1122, density 1; 15 px scrollbar normalization noted).
- Rendered detail visual: `D:\2026.8\SkillCenter\qa\implementation-detail-target.png` (1086 × 1448 px; CSS viewport 1086 × 1448, density 1).
- Browser: Codex In-app Browser, local Vite preview at `http://127.0.0.1:5173/`.
- States: market with all categories and 12 sample skills; detail for EOX查询Skill; admin role.

## Comparison

The source and rendered images were opened in the same visual QA pass and compared at full view, then at focused regions (market header/filter/card grid; detail hero/operation panel/metrics). The implementation preserves the source hierarchy: header, left navigation for the market, metrics strip, filter row, 4-column card grid, detail hero, metric cards, detail body, and right-side distribution/permission panels.

## Findings

- No actionable P0/P1/P2 visual findings remain after the second pass.
- P3 follow-up: replace the temporary Phosphor sparkle brand mark with the official Huawei department/AI Skill logo asset when the standalone asset is supplied. The current mark is intentionally kept within the same blue/white visual language and does not block the M1 workflow.
- P3 follow-up: market content is mock data (12 representative skills) until the catalog API and organization directory are connected.

## Comparison history

1. First pass: the market header used the detail-page dark treatment and the detail page retained the market sidebar. Fix: added market/light and detail/dark header variants and removed the sidebar from the detail state to match the two source compositions.
2. Second pass: added the complete 12-card sample catalog to match the source grid density, re-captured both states, and checked focused regions. No P0/P1/P2 differences remained.

## Interaction and accessibility checks

- Card click opens the detail state; breadcrumb and market navigation return to the catalog.
- Category filtering updates the visible card set; search updates the market state.
- Role selector gates management navigation: viewer hides upload/review links; admin exposes them.
- Upload Skill opens a local ZIP-only modal with validation affordances; install/download/favorite/share actions surface feedback toasts.
- Skill statistics page renders KPI cards, a seven-day invocation chart, and a popular-Skill table.
- Mobile check at 390 × 844: no horizontal overflow in market or detail states.
- Browser console: no error or warning entries after the tested flows.

## Final result

passed

## M2 API-driven recheck

- Market data now comes from `GET /api/v1/skills`; the page shows loading and API error states without reintroducing mock cards.
- Detail actions call the installation manifest endpoint; upload uses multipart `POST /api/v1/skill-packages`; analytics uses `GET /api/v1/analytics/overview`.
- Vite proxy was verified at `http://127.0.0.1:5173/api/v1/skills?page=1&pageSize=12` while Spring Boot ran on port 8080.
- Automated evidence: backend 12/12, frontend 10/10, M0 contracts 11/11. Unknown invocation content fields return `EVENT_SCHEMA_INVALID`.
- Remaining visual follow-up is unchanged: replace the temporary Phosphor brand mark when the official Huawei department asset is supplied.
