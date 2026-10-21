# SPDX-FileCopyrightText: 2025 Flow
# SPDX-License-Identifier: GPL-3.0-or-later
# Based on NewPipe's ProGuard configuration

# https://developer.android.com/build/shrink-code

## Open-source readable stack traces (no de-obfuscation pipeline required)
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

## Strip debug and verbose logging from release binaries
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

## Rules for NewPipeExtractor
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.schabi.newpipe.extractor.services.** { *; }
-keep class * extends org.schabi.newpipe.extractor.Extractor { *; }
-keep class * implements org.schabi.newpipe.extractor.Service { *; }
-keepattributes Exceptions, InnerClasses

## Rules for Rhino and Rhino Engine (JavaScript engine used by NewPipe)
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

## Rules for Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

## Keep data models and serialization structures
-keep class app.zaid.flow.data.model.** { *; }
-keep class app.zaid.flow.data.local.** { *; }
-keep class app.zaid.flow.data.lyrics.** { *; }
-keep class app.zaid.flow.innertube.models.** { *; }

## Gson-persisted models that live OUTSIDE the packages above (issue #996): without an
## explicit keep, R8 may strip the generic Signature of MusicTrack.artists, and cached
## history/queues then deserialize artist entries as LinkedTreeMaps that crash the music
## feed with a ClassCastException on first access. Any new Gson-persisted model must be
## added here (or live in a kept package), whatever package it renders from.
-keep class app.zaid.flow.data.music.model.** { *; }
-keep class app.zaid.flow.data.music.Playlist { *; }
-keep class app.zaid.flow.data.music.DownloadedTrack { *; }

## Backup & restore. The manifest written into a master backup is (de)serialized by Gson through
## reflection, so its classes and their fields must survive shrinking; the restore reader
## (FlowBackupReader) is plain code, but its part enum and exceptions are matched by type and
## name. BackupData and the other app-data models are already kept by data.local.** above.
-keep class app.zaid.flow.data.backup.BackupManifest { *; }
-keep class app.zaid.flow.data.backup.BackupCounts { *; }
-keep class app.zaid.flow.data.backup.ManifestEntry { *; }
-keep class app.zaid.flow.data.backup.BackupSection { *; }
-keep class app.zaid.flow.data.backup.BackupPart { *; }
-keep class app.zaid.flow.data.backup.*Exception { *; }

## Kotlin enums that Gson reads and writes by constant name
-keepclassmembers enum app.zaid.flow.data.** { <fields>; public static **[] values(); public static ** valueOf(java.lang.String); }

## Stream-denial recovery reads the HTTP status from an exception's responseCode field by reflection
-keepclassmembers class * extends java.lang.Throwable { int responseCode; }
-keep class androidx.media3.datasource.HttpDataSource$InvalidResponseCodeException { *; }

## Shazam recognition models + kotlinx serializers
-keepclasseswithmembers class app.zaid.flow.data.recognition.shazam.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class app.zaid.flow.data.recognition.shazam.** {
    *** Companion;
}

## Rules for Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

## Rules for Brotli & re2j
-dontwarn org.brotli.**
-keep class org.brotli.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.google.re2j.**
-keep class com.google.re2j.** { *; }
-dontwarn org.jsoup.helper.Re2jRegex
-dontwarn org.jsoup.helper.Re2jRegex$Re2jMatcher

## Third-party / Platform warning suppressions
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**