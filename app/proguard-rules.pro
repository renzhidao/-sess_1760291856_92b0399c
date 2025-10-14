# 文件: app/proguard-rules.pro
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Keep data classes
-keep class com.infiniteclipboard.data.** { *; }

# 兜底：保留 Shizuku UserService 与 AIDL 相关类（防止被优化移除）
-keep class com.infiniteclipboard.service.ClipboardUserService { *; }
-keep class com.infiniteclipboard.IClipboardUserService { *; }
-keep class com.infiniteclipboard.IClipboardUserService$* { *; }