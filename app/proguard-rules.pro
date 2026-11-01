# NOT APPLIED: release builds currently run with minifyEnabled=false (see app/build.gradle.kts).
# Kept so R8 can be re-enabled later. If you do, also keep every member of the module's classes.
# Modern libxposed API 102 entry registration, per upstream API documentation.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# BuildConfig.DEBUG=false removes the module's diagnostic calls in release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
