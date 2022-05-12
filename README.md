# lambdaisland/classpath

<!-- badges -->
[![cljdoc badge](https://cljdoc.org/badge/com.lambdaisland/classpath)](https://cljdoc.org/d/com.lambdaisland/classpath) [![Clojars Project](https://img.shields.io/clojars/v/com.lambdaisland/classpath.svg)](https://clojars.org/com.lambdaisland/classpath)
<!-- /badges -->

Experimental utilities for dealing with "the classpath", and dynamically loading libraries.

Blog post: [The Classpath is a Lie](https://lambdaisland.com/blog/2021-08-25-classpath-is-a-lie)

## With thanks to Nextjournal!

[Nextjournal](https://nextjournal.com/) provided the incentive and financial
support to dive into this. Many thanks to them for pushing to move the needle on
dev experience.

## Usage

### Watch `deps.edn`

```clojure
(require '[lambdaisland.classpath.watch-deps :as watch-deps])

(watch-deps/start! {:aliases [:dev :test]})
```

Whenever you change `deps.edn` this will pick up any extra libraries or changed
versions, and add them to the classpath.

Caveat: we can only *add* to the classpath, any dependencies that were present
when the app started will remain accessible.

### Classpath inspection and manipulation

```clojure
(require '[lambdaisland.classpath :as licp])
```

Get the current chain of classloaders

```clojure
(licp/classloader-chain)
```

Also see which entries each loader searches

```clojure
(licp/classpath-chain)
```

Update a gitlib in `deps.edn` to the latest `:git/sha` in `main` or in the specified `:git/branch`

```clojure
(licp/git-pull-lib 'com.lambdaisland/ornament)
```

Add/override the classpath based on the current deps.edn.

```clojure
(licp/update-classpath!
  '{:aliases [:dev :test :licp]
    :extra {:deps {com.lambdaisland/webstuff {:local/root "/home/arne/github/lambdaisland/webstuff"}}}})
```

Access specific class loaders

```clojure
(licp/context-classloader)
(licp/base-loader)
(licp/root-loader)
(licp/compiler-loader)
```

<!-- opencollective -->
## Lambda Island Open Source

<img align="left" src="https://github.com/lambdaisland/open-source/raw/master/artwork/lighthouse_readme.png">

&nbsp;

classpath is part of a growing collection of quality Clojure libraries created and maintained
by the fine folks at [Gaiwan](https://gaiwan.co).

Pay it forward by [becoming a backer on our Open Collective](http://opencollective.com/lambda-island),
so that we may continue to enjoy a thriving Clojure ecosystem.

You can find an overview of our projects at [lambdaisland/open-source](https://github.com/lambdaisland/open-source).

&nbsp;

&nbsp;
<!-- /opencollective -->

<!-- contributing -->
## Contributing

Everyone has a right to submit patches to classpath, and thus become a contributor.

Contributors MUST

- adhere to the [LambdaIsland Clojure Style Guide](https://nextjournal.com/lambdaisland/clojure-style-guide)
- write patches that solve a problem. Start by stating the problem, then supply a minimal solution. `*`
- agree to license their contributions as MPL 2.0.
- not break the contract with downstream consumers. `**`
- not break the tests.

Contributors SHOULD

- update the CHANGELOG and README.
- add tests for new functionality.

If you submit a pull request that adheres to these rules, then it will almost
certainly be merged immediately. However some things may require more
consideration. If you add new dependencies, or significantly increase the API
surface, then we need to decide if these changes are in line with the project's
goals. In this case you can start by [writing a pitch](https://nextjournal.com/lambdaisland/pitch-template),
and collecting feedback on it.

`*` This goes for features too, a feature needs to solve a problem. State the problem it solves, then supply a minimal solution.

`**` As long as this project has not seen a public release (i.e. is not on Clojars)
we may still consider making breaking changes, if there is consensus that the
changes are justified.
<!-- /contributing -->

<!-- license -->
## License

Copyright &copy; 2021 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
<!-- /license -->
