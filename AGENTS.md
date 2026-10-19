# AGENTS.md

Instructions for AI agents working in this repository.

## License headers

Every Java file starts with the Apache 2.0 header from `gradle/spotless.java.current.license`:

```
/*
 * Copyright <year> Consensys Incorporated.
 * ...
 */
```

Files added on or after 2026-09-01 must carry the current owner above. When you create a file -
including when you copy an existing file as a starting point - set that header yourself. A file that
is deleted and added back on or after 2026-09-01 counts as added. Mind the capitalization: the
current owner is `Consensys` (lowercase s), the pre-switchover owner is `ConsenSys`.

Do not reuse the header of the file you copied from. Most files in this repository still carry the
pre-switchover owner, `Copyright <year> ConsenSys AG.`, which is only valid in files that predate
the switchover. Spotless keeps it in those files on purpose and will not replace a `ConsenSys AG`
header, so a copied header stays wrong until someone changes it by hand.

- `./gradlew spotlessApply` adds the current header to a new file that has none.
- `./gradlew spotlessCheck` fails when a file's header is missing or malformed.
- `build.gradle` documents a commented one-shot switch that re-owns every file at once. Do not
  enable it as part of unrelated work; it is a decision for the maintainers.
