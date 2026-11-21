# Release build keeps code unobfuscated for easier debugging of accessibility behavior.

# Shizuku 的 UserService 由服务端按类名反射实例化，R8 看不到引用，必须保留
-keep class com.example.autotapper.TapUserService { *; }
-keepclassmembers class com.example.autotapper.TapUserService {
    public <init>(...);
}

# Shizuku / Shizuku provider 的 binder 容器
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
