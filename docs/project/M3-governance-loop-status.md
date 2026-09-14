# M3 Governance Loop Status

Date: 2026-08-21

M3 turns local Skill ZIP uploads into a restart-safe governance loop:

`local ZIP -> validation -> pending_review -> reviewer decision -> published catalog -> install record -> audit query`

## Delivered

- Governance records: `SkillVersion`, `ReviewTask`, `InstallationRecord`, and `AuditEvent`.
- Atomic local JSON persistence at `./data/governance/state.json` (configurable with `skill-center.governance-storage`).
- Product roles are `developer` (普通开发者) and `admin` (平台管理员); local actor headers also retain `viewer`, `maintainer`, and `reviewer` compatibility aliases. Missing headers default to `local-user/admin` so existing local flows remain usable.
- Upload is restricted to maintainer/admin and creates `pending_review`; the response no longer claims a package is published.
- Reviewer/admin endpoints:
  - `GET /api/v1/admin/reviews?status=pending_review`
  - `POST /api/v1/admin/reviews/{reviewId}/approve`
  - `POST /api/v1/admin/reviews/{reviewId}/reject` with `{ "reason": "..." }`
- Public catalog overlays only `published` governed versions. Pending and rejected versions are not visible in public skill detail/list APIs.
- Version history: `GET /api/v1/skills/{skillId}/versions`; viewers receive published metadata only, reviewers/admins can inspect all states.
- Installation records: `GET /api/v1/installations`; viewers/maintainers see their own records, reviewers/admins see organization records.
- Admin audit query: `GET /api/v1/audit` with optional `action` and `resourceType` filters.
- Frontend role headers, review queue, approve/reject controls, and installation record view are wired to the real API.
- High-risk packages derive a deterministic risk level from declared permissions; ordinary admin approval moves them to `security_review`, and a different platform administrator must approve before publication. Review and version evidence survives JSON store reload.

## Verification evidence

| Check | Result |
| --- | --- |
| `mvn -B -q -f apps/api/pom.xml test` | PASS (all backend tests) |
| `npm.cmd test --prefix apps/web` | PASS (12 tests) |
| `npm.cmd run build --prefix apps/web` | PASS (Vite + Sites bundle) |
| M0 Python contract suite | PASS (11 tests) |
| HTTP smoke flow | PASS: upload pending, reviewer approval, published catalog, install record, audit record |
| Restart smoke | PASS: installation and audit data remained available after API restart |

## Deliberate M3 boundaries

- Storage remains local JSON and local package files for this milestone; production should replace these adapters with Huawei-approved database/object storage.
- Header-based actor resolution is a development adapter, not enterprise SSO. SSO/JWT validation and organization directory mapping are next-stage integration work.
- Artifact download URLs remain configured manifest URLs; signed object-storage URLs and client callback delivery are not part of M3.
- No online Skill authoring, draft state, tag CRUD, or arbitrary package editing is introduced. Skills continue to originate from local ZIP uploads.

M4.1 distribution and telemetry follow-up: see `docs/project/M4.1-distribution-telemetry-status.md`. M4.2 still covers range-based analytics and M4.3 covers deprecation/withdrawal impact controls.
