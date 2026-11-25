# Dependency status of sliceworkz/eventmodeling

Written by the nightly dependency pipeline after every run (sliceworkz/software-factory, `docs/dependency-updates.md`). Per branch: the vulnerabilities in what its head would ship, and the pipeline PRs waiting; per release line also its last release, scanned from the SBOMs attached to it. Every finding is in `latest/<branch>.json`, every run in `history/`.

| Branch | Last run | Head | Critical | High | Medium | Low | Blocking | Released version | Open PRs | Unreleased updates | SBOMs |
|---|---|---|---:|---:|---:|---:|---:|---|---:|---|---|
| [`develop`](#develop) | 2026-11-24T12:36:49+01:00 | `d99e4b50fe` | 0 | 0 | 0 | 0 | 0 | - | 1 | - | ci-artifact (run 45212287843) |
| [`release/0.10`](#release010) | 2026-11-24T11:50:04+01:00 | `b44b34a02c` | 0 | 0 | 0 | 0 | 0 | v0.10.0: none critical/high | 2 | none since v0.10.0 | ci-artifact (run 44806918038) |
| [`release/0.9`](#release09) | 2026-11-25T10:45:43+01:00 | `aad50ae258` | 0 | 0 | 0 | 0 | 0 | v0.9.0: none critical/high | 1 | **3 since v0.9.0**, can be released | ci-artifact (run 45379417774) |

## develop

No critical or high findings on the branch head.

Pipeline PRs open: [#280](https://github.com/sliceworkz/eventmodeling/pull/280) (auto-merge, CI green)

## release/0.10

No dependency updates since v0.10.0 (2026-11-20).

**Nothing to release: no changes since v0.10.0.**

- not ready: pipeline PR #276 is about to merge: release after it, so its updates are in
- pipeline PR #278 waits for review: its updates are not in this release

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.10.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

Pipeline PRs open: [#276](https://github.com/sliceworkz/eventmodeling/pull/276) (auto-merge, CI green), [#278](https://github.com/sliceworkz/eventmodeling/pull/278) (review, CI green)

## release/0.9

**3 dependency update(s) merged since v0.9.0 (2026-11-12), not released yet**: a patch release (*Release 2 · Tag* on this line) ships them.

**Can be released: 3 dependency updates since v0.9.0.**

- pipeline PR #279 waits for review: its updates are not in this release

Release it with [Release 2 · Tag](https://github.com/sliceworkz/eventmodeling/actions/workflows/create-release-tag-and-bump.yaml) (*Run workflow*, branch `release/0.9`), or:

```bash
gh workflow run create-release-tag-and-bump.yaml --ref release/0.9 -R sliceworkz/eventmodeling
```

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.9.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

Pipeline PRs open: [#279](https://github.com/sliceworkz/eventmodeling/pull/279) (review, CI green)
