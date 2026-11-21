# Add project-specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles directive in build.gradle.
#
# For details on how to write custom rules, see:
#   http://developer.android.com/guide/minifying/proguard.html

# Room Database
-keep class com.photo_editor.my_photo_editor.db.** { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Media3 ExoPlayer
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-dontwarn androidx.media3.**

# Coil Image Loading
-keep class coil.** { *; }
-dontwarn coil.**

# Kotlin Coroutines
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * extends kotlinx.coroutines.internal.DispatchedContinuation {
    <fields>;
}
-dontwarn kotlinx.coroutines.**

# Material Components & AndroidX
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Models & Data Classes
-keep class com.photo_editor.my_photo_editor.model.** { *; }

# Keep App Classes
-keep class com.photo_editor.my_photo_editor.** { *; }
