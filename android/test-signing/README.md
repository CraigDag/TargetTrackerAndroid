# Test signing key

`sideload-test.keystore` signs the test builds that are installed by hand
(sideloaded). It is a throwaway key with the well-known password `android`,
like Android's own debug key, and is checked in on purpose: every build,
wherever it is made, then has the same signature, so a new build installs over
the old one and keeps the app's data.

It is **not** secret and must **not** be used for Google Play. The Play upload
key will be created separately and kept out of the repository.
