-keep class com.enterprise.uiqa.** { *; }
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    public *;
}
-dontwarn kotlin.**
