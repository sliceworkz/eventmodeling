# Dependency status of sliceworkz/eventmodeling

Written by the nightly dependency pipeline after every run (sliceworkz/software-factory, `docs/dependency-updates.md`). Per branch: the vulnerabilities in what its head would ship, and the pipeline PRs waiting; per release line also its last release, scanned from the SBOMs attached to it. Every finding is in `latest/<branch>.json`, every run in `history/`.

| Branch | Last run | Head | Critical | High | Medium | Low | Blocking | Released version | Open PRs | Unreleased updates | SBOMs |
|---|---|---|---:|---:|---:|---:|---:|---|---:|---|---|
| [`develop`](#develop) | 2026-11-14T09:00:21+01:00 | `64cd09d294` | 0 | 0 | 0 | 0 | 0 | - | 0 | - | ci-artifact (run 43489866814) |
| [`release/0.9`](#release09) | 2026-11-14T08:32:37+01:00 | `651f0d28fa` | 0 | 0 | 0 | 0 | 0 | - | 0 | none since v0.9.0 | ci-artifact (run 43216604393) |
| [`release/0.8`](#release08) | 2026-11-14T14:59:24+01:00 (dry run) | `f55dbe93b2` | 0 | 0 | 0 | 0 | 0 | v0.8.0: none critical/high | 0 | **4 since v0.8.0** | saved by the previous run (branch head unchanged) |
| [`release/0.7`](#release07) | 2026-11-14T08:32:35+01:00 | `3f8f174499` | 0 | 0 | 0 | 0 | 0 | - | 0 | **1 since v0.7.2** | saved by the previous run (branch head unchanged) |

## develop

No critical or high findings on the branch head.

## release/0.9

No dependency updates since v0.9.0 (2026-11-12).

No critical or high findings on the branch head.

## release/0.8

**4 dependency update(s) merged since v0.8.0 (2026-10-23), not released yet**: release a patch (`create-release-tag-and-bump` on this line) to ship them. 1 other change(s) are unreleased too.

No critical or high findings in the released v0.8.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

## release/0.7

**1 dependency update(s) merged since v0.7.2 (2026-11-08), not released yet**: release a patch (`create-release-tag-and-bump` on this line) to ship them.

No critical or high findings on the branch head.
