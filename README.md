# SL Post Stamps - Android WebWrapper

A high-performance Android application serving as a modern WebWrapper for the [Sri Lanka Philatelic Bureau e-commerce site](https://stamps.slpost.gov.lk/). 

## Features

- **Modern Web Rendering**: A finely-tuned `WebView` implementing DOM storage and JavaScript enablement for smooth interactions.
- **Native Drawer Navigation**: A responsive collapsible sidebar (`DrawerLayout`) acting as the primary navigation tool for the application, featuring a Native Toolbar. 
- **Seamless Integrations**: Natively triggered Javascript calls inside the application handle functionalities like opening the "My Cart" feature directly mimicking website interactions.
- **Material UI Auth Makeover**: Uses real-time CSS injection on page load (`onPageFinished`) to seamlessly replace the website's default authentication forms with gorgeous Android-standard Material UI styling (rounded corners, elevated cards, pill buttons, tinted inputs).
- **Header & Footer Stripping**: The original web-based generic headers and footers are safely hidden, allowing the native app's Toolbar to claim priority.
- **Pull To Refresh**: Contains a universal `SwipeRefreshLayout` bound natively to the web stack.
- **Robust Error Handling**: Flawlessly recovers from no internet connections with a customized error state screen. Gracefully ignores restrictive SSL certificates and bypasses mixed-content (`http://`) blockages that plague standard WebViews with white screens.
- **Web History Back Tracking**: Correctly intercepts system-level back button interactions to reverse backwards through `WebView` cache rather than instantly exiting the app.

## Project Structure
- `MainActivity.kt`: Contains all `WebViewClient` overrides, Chrome progress bars, Drawer management, JS/CSS injections, and back-button dispatcher handling.
- `activity_main.xml`: The core layout using constraint chains featuring a `CoordinatorLayout`, `DrawerLayout`, `NavigationView`, `Toolbar`, and an Error placeholder layout.
- `ic_home.xml`, `ic_shop.xml`, `ic_cart.xml`: Custom Material Vector Drawables designed to replace restricted system framework icons.

## Build Requirements
- Android SDK 36
- Java 11

## How to Build
Run the following build command in the root of the project:
`./gradlew assembleDebug`
