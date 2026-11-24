# Dependency status of sliceworkz/eventmodeling

Written by the nightly dependency pipeline after every run (sliceworkz/software-factory, `docs/dependency-updates.md`). Per branch: the vulnerabilities in what its head would ship, and the pipeline PRs waiting; per release line also its last release, scanned from the SBOMs attached to it. Every finding is in `latest/<branch>.json`, every run in `history/`.

| Branch | Last run | Head | Critical | High | Medium | Low | Blocking | Released version | Open PRs | Unreleased updates | SBOMs |
|---|---|---|---:|---:|---:|---:|---:|---|---:|---|---|
| [`develop`](#develop) | 2026-11-23T10:46:53+01:00 | `7480adf3fd` | 0 | 0 | 0 | 0 | 0 | - | 0 | - | ci-artifact (run 45044236607) |
| [`release/0.10`](#release010) | 2026-11-23T09:55:31+01:00 | `b44b34a02c` | 0 | 0 | 0 | 0 | 0 | v0.10.0: none critical/high | 0 | none since v0.10.0 | ci-artifact (run 44806918038) |
| [`release/0.9`](#release09) | 2026-11-24T11:50:04+01:00 | `7f8ab059c7` | 0 | 0 | 0 | 0 | 0 | v0.9.0: none critical/high | 1 | **2 since v0.9.0**, can be released | ci-artifact (run 44785573953) |

## develop

No critical or high findings on the branch head.

## release/0.10

No dependency updates since v0.10.0 (2026-11-20).

No critical or high findings in the released v0.10.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

## release/0.9

**2 dependency update(s) merged since v0.9.0 (2026-11-12), not released yet**: a patch release (*Release 2 · Tag* on this line) ships them.

**Can be released: 2 dependency updates since v0.9.0.**

- pipeline PR #279 waits for review: its updates are not in this release

Release it with [Release 2 · Tag](https://github.com/sliceworkz/eventmodeling/actions/workflows/create-release-tag-and-bump.yaml) (*Run workflow*, branch `release/0.9`), or:

```bash
gh workflow run create-release-tag-and-bump.yaml --ref release/0.9 -R sliceworkz/eventmodeling
```

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.9.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

Pipeline PRs open: [#279](https://github.com/sliceworkz/eventmodeling/pull/279) (review, CI green)
