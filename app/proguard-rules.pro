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
# ── Chorevo release (R8) ─────────────────────────────────────────────────────
# Readable crash stack traces (upload mapping.txt to Play), without file names.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Firestore maps these classes by reflection (toObject / set(object)): keep
# their fields, getters/setters and no-arg constructors.
-keep class com.example.chorevo.dancelibrarylist.DanceLibraryModel { *; }
-keep class com.example.chorevo.dancelibrarylist.DanceComment { *; }
-keep class com.example.chorevo.community.GroupInvitation { *; }
# DanceLibraryModel.template and everything inside it (PoseTemplateModels.kt).
-keep class com.example.chorevo.posedetection.PoseTemplate { *; }
-keep class com.example.chorevo.posedetection.PoseTrack { *; }
-keep class com.example.chorevo.posedetection.PoseFrame { *; }
-keep class com.example.chorevo.posedetection.PoseBounds { *; }
-keep class com.example.chorevo.posedetection.HandTrack { *; }
-keep class com.example.chorevo.posedetection.HandLandmarkFrame { *; }
-keep class com.example.chorevo.posedetection.PoseSelectionPreview { *; }
-keep class com.example.chorevo.posedetection.PoseSelectionCandidate { *; }

# Native-backed ML and 3D libraries (JNI looks classes up by name).
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class io.github.sceneview.** { *; }
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn io.github.sceneview.**
-dontwarn com.google.android.filament.**

# No verbose/debug/info logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
