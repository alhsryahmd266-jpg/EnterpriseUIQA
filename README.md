# Enterprise UI QA Automation Framework

> أداة أتمتة فحص واجهات المستخدم الرسومية للأنظمة المؤسسية — مبنية بـ Kotlin

## المميزات

- **Custom AccessibilityService** — خدمة وصول مخصصة تتيح التحكم الكامل في الواجهات
- **BezierTouchEngine** — محرك محاكاة لمس بمنحنيات بيزيه يحاكي حركة الإصبع الحقيقية
- **Swipe / Tap / LongPress** — إيماءات كاملة بمدد قابلة للضبط
- **Window Tree Dump** — فحص شجرة العناصر الكاملة في Logcat
- **GitHub Actions CI/CD** — بناء، توقيع، ونشر APK تلقائياً

## البنية

```
app/src/main/kotlin/com/enterprise/uiqa/
├── UiAutomationService.kt   ← خدمة الوصول الرئيسية
├── BezierTouchEngine.kt     ← محرك منحنيات بيزيه
├── MainActivity.kt          ← شاشة الحالة والتفعيل
└── BootReceiver.kt          ← استقبال إعادة التشغيل
```

## مثال على الاستخدام

```kotlin
val service = UiAutomationService.instance ?: return

// سحب من اليسار لليمين
service.swipe(100f, 800f, 900f, 800f, durationMs = 400L)

// نقر على إحداثي
service.tap(540f, 960f)

// ضغط مطوّل
service.longPress(540f, 960f, durationMs = 1000L)

// طباعة شجرة العناصر
service.dumpWindowTree()
```

## البناء المحلي

```bash
./gradlew assembleDebug    # بناء debug
./gradlew assembleRelease  # بناء release (يتطلب keystore)
```

## GitHub Actions

كل push على `main` يبني الـ APK تلقائياً.  
لإنشاء release رسمي، أنشئ tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## المتطلبات

- Android 8.0+ (API 26+)
- تفعيل خدمة إمكانية الوصول يدوياً من إعدادات الجهاز
