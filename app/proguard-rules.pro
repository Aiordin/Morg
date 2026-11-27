# R8 / ProGuard 规则
#
# 目前 release 构建没有开启压缩与混淆（app/build.gradle.kts 里
# isMinifyEnabled = false），这个文件是开启前的占位。
#
# 开启时需要注意：应用内没有通过反射访问自己的类。仅有的两处反射都指向
# framework 类型（ActivityOptions#setLaunchWindowingMode、
# UsageEvents.Event#getInstanceId），它们不参与 R8，因此不需要 keep 规则。
