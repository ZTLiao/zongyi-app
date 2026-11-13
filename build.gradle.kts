plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // KSP：处理 @AppFunction 注解，生成 Android MCP（AppFunctions）schema
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
