# android (placeholder)

The Android app comes in Phase 6, only after the analysis core passes the validation targets in `docs/SPEC.md` on the desktop.

When it starts, it becomes the Gradle project `:android`, depends on `:core` unchanged, and uses the official OpenCV Android SDK (`org.opencv:opencv`) at the version pinned in `gradle/libs.versions.toml`. `core`'s tests already check that every OpenCV call it makes exists in that SDK.
