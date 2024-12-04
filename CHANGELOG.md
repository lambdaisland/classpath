# Unreleased

## Added

## Fixed

## Changed

# 0.3.43 (2024-12-04 / df15f2e)

## Fixed

- Fix handling of InnocuousThread (i.e. skip them, not much more we can do)
- Version bumps

# 0.2.37 (2022-08-26 / 34be62f)

## Changed

- Upgrade directory-watcher to the latest version
- Prefix output from watch-deps with `[watch-deps]`
- Print a message when the watcher triggers, but no classpath changes are made

# 0.1.33 (2022-08-25 / fd51db4)

- Fix typo in the README's example and one in doc-string

## Added

- Support watching multiple `deps.edn` files referenced via `:local/root`

## Fixed

## Changed

# 0.0.27 (2021-10-06 / 719c1f5)

## Fixed

- Watch-deps now triggers on Mac
- Support both `main` and `master` as branch names in `git-pull-lib`
- Speed up resource lookups in priority classloader
- Several extra convenience functions for working with classloaders

# 0.0.0

## First announced version (git only)

- Classpath inspection utilities
- Priority classloader for overrides
- deps watcher
- git-pull-lib