# Contributing

Thanks for helping.

- **The format comes first.** `SPEC.md` is the contract between publishers and the watch app. A change to it needs an issue first. Breaking changes bump the `wristfeed` version.
- **Publisher (Python):** `cd publisher && pip install -e .[dev] && pytest && ruff check .`
- **Watch app (Android):** `cd android && ./gradlew test assembleDebug` (JDK 17).
- Keep pull requests small and focused. Add a test for any behavior change.
- Never put secrets in example feeds or tests. Feeds are public.
- Do not copy code from GPL projects (for example Complications Suite). Ideas are fine; credit them in the README.

By contributing you agree your work is released under the MIT license and that you follow the [Code of Conduct](CODE_OF_CONDUCT.md).
