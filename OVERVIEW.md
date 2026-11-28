# Dependency status of sliceworkz/eventmodeling

Written by the nightly dependency pipeline after every run (sliceworkz/software-factory, `docs/dependency-updates.md`). Per branch: the vulnerabilities in what its head would ship, and the pipeline PRs waiting; per release line also its last release, scanned from the SBOMs attached to it. Every finding is in `latest/<branch>.json`, every run in `history/`.

| Branch | Last run | Head | Critical | High | Medium | Low | Blocking | Released version | Open PRs | Unreleased updates | SBOMs |
|---|---|---|---:|---:|---:|---:|---:|---|---:|---|---|
| [`develop`](#develop) | 2026-11-27T11:08:00+01:00 | `0533e8365e` | 0 | 0 | 0 | 0 | 0 | - | 0 | - | ci-artifact (run 45893011719) |
| [`release/0.11`](#release011) | 2026-11-28T09:51:28+01:00 | `4ef13ff8f1` | 0 | 0 | 0 | 0 | 0 | v0.11.0: none critical/high | 0 | none since v0.11.0 | ci-artifact (run 45896947237) |
| [`release/0.10`](#release010) | 2026-11-27T10:37:23+01:00 | `376c0c6cb8` | 0 | 0 | 0 | 0 | 0 | v0.10.0: none critical/high | 0 | **3 since v0.10.0**, can be released | ci-artifact (run 45879535593) |

## develop

No critical or high findings on the branch head.

## release/0.11

No dependency updates since v0.11.0 (2026-11-26).

**Nothing to release: no changes since v0.11.0.**

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.11.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

## release/0.10

**3 dependency update(s) merged since v0.10.0 (2026-11-20), not released yet**: a patch release (*Release 2 · Tag* on this line) ships them.

**Can be released: 3 dependency updates since v0.10.0.**

Release it with [Release 2 · Tag](https://github.com/sliceworkz/eventmodeling/actions/workflows/create-release-tag-and-bump.yaml) (*Run workflow*, branch `release/0.10`), or:

```bash
gh workflow run create-release-tag-and-bump.yaml --ref release/0.10 -R sliceworkz/eventmodeling
```

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.10.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.
