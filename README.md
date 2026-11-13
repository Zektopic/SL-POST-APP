# SL Post Stamps — Android WebView wrapper

An Android wrapper around the [Sri Lanka Philatelic Bureau storefront](https://stamps.slpost.gov.lk/).
The site is rendered in a `WebView`; the app supplies native chrome (toolbar,
bottom navigation, drawer) and injects CSS and JavaScript to adapt the desktop
site for mobile.

## Features

- **Native chrome around the site** — toolbar, bottom navigation bar with a live
  cart badge, and a drawer for account and information pages.
- **Toolbar actions** — search the catalogue from any page, share the current
  page or product, refresh, and open in the browser.
- **Light and dark themes** — the app follows the system theme, and the page
  follows the app: `MainActivity` marks `<html>` with `slp-dark` and
  `inject.css` swaps its palette.
- **Page-aware UI** — `page-detector.js` classifies each page (home, product,
  cart, sign-in, register, account, information) and reports it to the app over
  a JavaScript bridge, which drives the toolbar subtitle, the cart badge, the
  selected tab and the signed-in/signed-out menu state.
- **Injected styling** — `inject.css` and `inject.js` reflow the desktop site
  for a phone: a two-column product grid, a card-per-line cart, a collapsible
  search, quantity steppers, a full-screen image viewer, and the site's own
  header and footer hidden so the native toolbar can take over.
- **Session persistence** — cookies are flushed on pause, so a sign-in survives
  an app restart, and the last on-site page is restored on cold start.
- **Failure handling** — separate offline, unreachable and server-error
  screens; an offline screen reloads by itself when the connection returns; a
  WebView renderer killed by the system is rebuilt instead of crashing the app.
- **Downloads and uploads** — downloads are handed to `DownloadManager` with the
  session cookie attached; `<input type="file">` opens the system picker.
- **Pull to refresh and web-history back navigation.**

## Architecture

```
MainActivity.kt        WebViewClient/WebChromeClient, navigation, toolbar,
                       drawer and bottom bar, error states, downloads, file
                       chooser, lifecycle and asset injection
Storefront.kt          The site's URLs and host rules; which tab a page is on
ResumePolicy.kt        Which pages are safe to reopen on a cold start
WebContentBridge.kt    @JavascriptInterface bridge exposed to the page as
                       `SLPBridge` — page type, auth state, cart count, product
PageType.kt            The page classification enum

assets/page-detector.js   Classifies the page, reports cart/auth/product
                          context to the bridge
assets/inject.js          Behaviour, gated by page type
assets/inject.css         Styling; all colours are tokens in its first block

res/layout/activity_main.xml   DrawerLayout > app bar + (WebView | error
                               screen | loading screen) + bottom bar
res/values/colors.xml          Light palette; values-night/ redefines it
```

### The JavaScript bridge

`page-detector.js` and `inject.js` are injected into a **third-party site that
this project does not control**. Consequences worth keeping in mind when
editing them:

- Selectors are assumptions. If the site's markup changes they stop matching,
  usually silently. Each block in `inject.css` starts with a comment recording
  the markup it expects.
- `SLPBridge` is reachable from page JavaScript, so anything it accepts is
  untrusted input. URLs coming across the bridge are validated against the
  storefront host before being stored or loaded, and text is length-limited
  before it is shown.
- The scripts and the stylesheet are injected only into pages on
  `stamps.slpost.gov.lk` itself, not into other hosts that happen to load in
  the WebView.

### Two palettes that must agree

The same colours live in two places: `res/values/colors.xml` (and
`values-night/`) for the native chrome, and the `:root` / `html.slp-dark`
blocks of `inject.css` for the page. Change them together.

## Build requirements

- Android SDK 37
- **JDK 17 or newer** (CI uses 21). The Gradle and Android Gradle Plugin
  versions in this repository will not run on JDK 11.

## Building

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android Lint
```

The unit tests run on the JVM. `MainActivitySmokeTest` starts the real Activity
under Robolectric, so a layout, menu or theme that fails to inflate is caught
there rather than on a device. It downloads an Android runtime jar the first
time it runs.

## CI and releases

`.github/workflows/build-apk.yml` runs tests, lint and a debug build on every
push and pull request. Pushing a `v*` tag additionally builds and publishes a
**signed release APK** to GitHub Releases.

Debug APKs are CI artifacts only and are never published — they are debuggable
and signed with a throwaway key. See [docs/RELEASING.md](docs/RELEASING.md) for
keystore setup and the required repository secrets.
