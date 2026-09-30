# Internationalization implementation and verification plan

## Baseline audit

The remote audit on 2026-09-30 found main at
`345e9e9ec96e35ff8fc539d9d0c3a507dbc7ef13`, with security PRs #6 and #7
merged and successful main CI. Security PR #8 remained an open draft at
`75d80707adf258f4a88e6c1bd6a0e3f537abbfdc`. That exact commit passed the
complete Linux security suite, Android JVM tests and arm64 debug builds, and
native Linux x86_64/aarch64 release validation. Device instrumentation had not
been executed. The i18n branch depends on #8 and initially targets its branch;
it must be retargeted to main after the dependencies merge.

Actual API 26/35 instrumentation subsequently exposed a pre-existing temporary
NKPR reader-routing failure: staged .tmp files passed fingerprint verification
but Core treated them as plaintext PEM during decryption. This prerequisite is
isolated in PR #10 (fix/android-temporary-nkpr-suffix), dependent on #8. The i18n
PR targets #10 so its diff excludes this file-operation fix. Integration order
is #8, #10, then #9; none is merged automatically.

Android uses Compose with ComponentActivity, minSdk 26 and targetSdk 35.
English and Simplified Chinese resources existed; language settings were static.
There was no application locale API or language preference. Linux uses C17 and
OpenSSL 3.5 EVP, with mixed Chinese/English prompts, no locale selection, and no
user configuration mechanism. Core also emits human-readable diagnostics.
README was Chinese; SECURITY contained the existing private-reporting policy.

## Design

Both applications support en, zh-CN, zh-TW, ja and ko. The first system language
is matched explicitly: CN/SG use Simplified Chinese, TW/HK/MO use Traditional
Chinese, en/ja/ko regions match their language, and unsupported languages use
English. Chinese script tags are supported as well.

Android keeps standard resources and the existing Compose architecture. Android
13+ LocaleManager is the single authoritative preference, including changes from
system Settings. Older systems store the selection in private SharedPreferences.
An empty selection follows system changes. Activity contexts are canonicalized
before resource access, and changing language rebuilds the Activity. Settings
remain scrollable; language changes are disabled during active file operations.
No SAF transaction, cancellation, backup or cryptographic algorithm is changed.

The CLI uses embedded UTF-8 catalogs to keep static release binaries independent
of external gettext installations and locale catalog paths. Language priority is
--lang, XDG preference, LC_ALL/LC_MESSAGES/LANG, then English. Explicit system
selection restores automatic detection. Invalid preferences fall back safely.
ASCII-only locales use English. Diagnostics are translated independently of
protocol identifiers, file headers, algorithm names and --version output. Core
translation is opt-in and defaults to the existing diagnostic text.

README.md and SECURITY.md remain at the repository root in Simplified Chinese;
complete English versions use relative language navigation. Policy commitments,
reporting address and license are preserved.

## Verification plan

Resource/catalog checks compare coverage and printf parameter signatures, and
check bilingual document links. CLI regressions cover all languages, region
matching, environment/config/flag precedence, corrupt configuration, persistence,
system changes, noninteractive execution, help/errors and invariant output.
Android JVM tests cover mapping, persistence, invalid preferences, resources,
Activity recreation and Framework locale synchronization. Real API 26 and API 35
emulator instrumentation retains the existing JNI/SAF integration tests and adds
language switching, process restart, accessibility and real Activity rendering
with dark mode and increased font scale. Production Android remains arm64;
x86_64 native builds are restricted to debug emulator tests.

All existing Linux crypto, filesystem, cancellation, static analysis, UBSan,
ASan/LSan and fuzz gates remain required. The release matrix builds and tests
native x86_64 and aarch64 packages. Final results must distinguish executed tests,
artifact builds, failures and unverified device/provider boundaries.
