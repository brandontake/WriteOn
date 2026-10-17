# Project specific R8 / ProGuard rules.
# The default file in use is proguard-android-optimize.txt (see app/build.gradle.kts), so
# R8 runs with its full optimizer; -dontoptimize is deliberately NOT used anywhere.

# Keep readable stack traces in release crash reports (stripped class names are remapped
# through app/build/outputs/mapping/release/mapping.txt; keeping the line table makes the
# remapped traces useful).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Annotation attributes
# ---------------------------------------------------------------------------
# Gson needs runtime-visible annotations (@SerializedName, @Expose, ...) and the generic
# signature attribute for TypeToken<T>.
-keepattributes Signature
-keepattributes *Annotation*

# ---------------------------------------------------------------------------
# Gson
# ---------------------------------------------------------------------------
# gson 2.11.0 ships its own rules in META-INF/proguard/gson.pro (Signature, TypeToken,
# @JsonAdapter, @Expose), so no blanket `-keep class com.google.gson.**` is needed.
# Only the app model that is (de)serialised reflectively has to be kept:
#   SettingsModel.kt        -> List<Flashcard> (settings backup JSON)
#   NoteDatabaseProvider.kt -> List<String> tags via TypeToken (no data class involved)
-keep class com.ezpnix.writeon.presentation.screens.settings.model.Flashcard { *; }

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
# room-runtime 2.6.1 ships `-keep class * extends androidx.room.RoomDatabase` itself, so the
# duplicate rule was dropped. Entities are kept explicitly: Room's generated DAO/cursor code
# and schema validation refer to the entity members, and a wrong removal only fails at runtime.
-keep @androidx.room.Entity class * { *; }
-keepattributes InnerClasses

# ---------------------------------------------------------------------------
# Hilt / Dagger
# ---------------------------------------------------------------------------
# dagger 2.52 ships META-INF/com.android.tools/proguard/proguard.pro and its generated
# components are linked statically, so the blanket `-keep class dagger.**` was dropped.
# Hilt's generated module/entry-point holders are still kept defensively.
-keep class **_HiltModules { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.** <methods>;
}

# ---------------------------------------------------------------------------
# OkHttp
# ---------------------------------------------------------------------------
# okhttp 4.12.0 ships META-INF/proguard/okhttp3.pro (-dontwarn for the optional Conscrypt /
# OpenJSSE / BouncyCastle platforms + keepnames for PublicSuffixDatabase), so the blanket
# `-keep class com.squareup.okhttp3.**` and its -dontwarn were dropped.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# ProcessPhoenix
# ---------------------------------------------------------------------------
# No consumer rules are shipped; ProcessPhoenix reflects on android.app.ActivityThread.
-keep class com.jakewharton.processphoenix.** { *; }
-dontwarn com.jakewharton.processphoenix.**

# ---------------------------------------------------------------------------
# Tink (cloud backup keyset encryption)
# ---------------------------------------------------------------------------
# tink-android 1.8.0 only ships protobuf rules (META-INF/proguard/protobuf.pro). Tink's key
# managers are resolved through its own registries, so the blanket keep is retained
# deliberately: cloud backup cannot be exercised without a WebDAV server.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
