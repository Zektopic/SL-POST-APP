# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# The WebView JS bridge is reached only by reflection from page JavaScript, so
# R8 cannot see the call sites. Without this, enabling minification silently
# strips or renames these methods: no build error, no stack trace, the bridge
# simply stops working at runtime. Keep the rule ahead of turning minify on.
-keepclassmembers class com.zektopic.slpoststamps.WebContentBridge {
    @android.webkit.JavascriptInterface <methods>;
}
