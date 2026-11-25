# Dependency status of sliceworkz/eventmodeling

Written by the nightly dependency pipeline after every run (sliceworkz/software-factory, `docs/dependency-updates.md`). Per branch: the vulnerabilities in what its head would ship, and the pipeline PRs waiting; per release line also its last release, scanned from the SBOMs attached to it. Every finding is in `latest/<branch>.json`, every run in `history/`.

| Branch | Last run | Head | Critical | High | Medium | Low | Blocking | Released version | Open PRs | Unreleased updates | SBOMs |
|---|---|---|---:|---:|---:|---:|---:|---|---:|---|---|
| [`develop`](#develop) | 2026-11-25T11:28:59+01:00 | `8eb6fb9d25` | 0 | 0 | 0 | 0 | 0 | - | 1 | - | ci-artifact (run 45386732000) |
| [`release/0.10`](#release010) | 2026-11-25T10:45:45+01:00 | `37062785a8` | 0 | 0 | 0 | 0 | 0 | v0.10.0: none critical/high | 1 | **1 since v0.10.0**, can be released | ci-artifact (run 45379474367) |
| [`release/0.9`](#release09) | 2026-11-25T10:45:43+01:00 | `aad50ae258` | 0 | 0 | 0 | 0 | 0 | v0.9.0: none critical/high | 1 | **3 since v0.9.0**, can be released | ci-artifact (run 45379417774) |

## develop

No critical or high findings on the branch head.

Pipeline PRs open: [#283](https://github.com/sliceworkz/eventmodeling/pull/283) (auto-merge, CI green)

## release/0.10

**1 dependency update(s) merged since v0.10.0 (2026-11-20), not released yet**: a patch release (*Release 2 · Tag* on this line) ships them.

**Can be released: 1 dependency update since v0.10.0.**

- pipeline PR #278 waits for review: its updates are not in this release

Release it with [Release 2 · Tag](https://github.com/sliceworkz/eventmodeling/actions/workflows/create-release-tag-and-bump.yaml) (*Run workflow*, branch `release/0.10`), or:

```bash
gh workflow run create-release-tag-and-bump.yaml --ref release/0.10 -R sliceworkz/eventmodeling
```

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.10.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

Pipeline PRs open: [#278](https://github.com/sliceworkz/eventmodeling/pull/278) (review, CI green)

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
