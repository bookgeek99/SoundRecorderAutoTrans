# Keep Xposed entry point
-keep public class com.example.autotrans.AutoTransHook {
    public void handleLoadPackage(...);
}
-keep public class * implements de.robv.android.xposed.IXposedHookLoadPackage

# Keep app classes
-keep class com.example.autotrans.** { *; }