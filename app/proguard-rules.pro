# R8 / ProGuard 规则
#
# 当前为空是安全的：
#
# - Manifest 声明的组件由 AAPT 生成的规则自动保留。
# - 应用没有反射访问自己的类；两处反射都指向 framework 类型
#   （ActivityOptions#setLaunchWindowingMode、UsageEvents.Event#getInstanceId）。
# - Compose 与 coroutines 的 consumer rules 由各自 AAR 提供，AGP 自动合并。
#
# 新增「反射访问自己的类」时，在这里补 keep 规则。
