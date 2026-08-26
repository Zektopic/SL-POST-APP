# SL Post Stamps — Android WebView wrapper

An Android wrapper around the [Sri Lanka Philatelic Bureau storefront](https://stamps.slpost.gov.lk/).
The site is rendered in a `WebView`; the app supplies native chrome (toolbar,
bottom navigation, drawer) and injects CSS and JavaScript to adapt the desktop
site for mobile.

## Features

- **Native chrome around the site** — toolbar, bottom navigation bar with a live
  cart badge, and a drawer for account and information pages.
- **Page-aware UI** — `page-detector.js` classifies each page (home, listing,
  product, cart, checkout, login, register, account) and reports the result to
  the app over a JavaScript bridge, which drives the toolbar subtitle, the cart
  badge and the signed-in/signed-out menu state.
- **Injected styling** — `inject.css` and `inject.js` restyle the site for
  mobile: Material-styled auth forms, quantity steppers, image zoom, password
  visibility toggles, and hiding the site's own header and footer so the native
  toolbar can take over.
- **Session persistence** — cookies are flushed on pause, so a sign-in survives
  an app restart, and the last on-site page is restored on cold start.
- **Downloads and uploads** — downloads are handed to `DownloadManager` with the
  session cookie attached; `<input type="file">` opens the system picker.
- **Pull to refresh, offline error state, and web-history back navigation.**

## Architecture

```
MainActivity.kt        WebViewClient/WebChromeClient overrides, navigation,
                       drawer and bottom nav, downloads, file chooser,
                       lifecycle and asset injection
WebContentBridge.kt    @JavascriptInterface bridge exposed to the page as
                       `SLPBridge` — page type, auth state, cart count, product
PageType.kt            The page classification enum

assets/page-detector.js   Classifies the page, extracts cart/auth/product
                          context, calls the bridge
assets/inject.js          UX enhancements, gated by page type
assets/inject.css         Mobile restyling of the site

res/layout/activity_main.xml   CoordinatorLayout + DrawerLayout + Toolbar +
                               WebView + bottom nav + error and splash states
```

### The JavaScript bridge

`page-detector.js` and `inject.js` are injected into a **third-party site that
this project does not control**. Two consequences worth keeping in mind when
editing them:

- Selectors are assumptions. If the site's markup changes they stop matching,
  usually silently.
- `SLPBridge` is reachable from page JavaScript, so anything it accepts is
  untrusted input. URLs coming across the bridge are validated against the
  storefront host before being stored or loaded.

## Build requirements

- Android SDK 36
- **JDK 17 or newer** (CI uses 21). Gradle 9.3.1 with AGP 9.1 will not run on
  JDK 11.

## Building

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android Lint
```

## CI and releases

`.github/workflows/build-apk.yml` runs tests, lint and a debug build on every
push and pull request. Pushing a `v*` tag additionally builds and publishes a
**signed release APK** to GitHub Releases.

Debug APKs are CI artifacts only and are never published — they are debuggable
and signed with a throwaway key. See [docs/RELEASING.md](docs/RELEASING.md) for
keystore setup and the required repository secrets.
