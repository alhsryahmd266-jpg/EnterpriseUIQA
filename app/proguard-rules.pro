# ── تطبيق EnterpriseUIQA ──────────────────────────────────────────────────
-keep class com.enterprise.uiqa.** { *; }
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    public *;
}

# ── ML Kit Pose Detection ─────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_pose.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ── Material Components ───────────────────────────────────────────────────
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── AndroidX ─────────────────────────────────────────────────────────────
-keep class androidx.** { *; }
-dontwarn androidx.**

# ── Kotlin ───────────────────────────────────────────────────────────────
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
