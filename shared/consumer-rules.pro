# ORT JNI constructs Java metadata/tensors by name. R8 must retain classes,
# constructors and members (keepnames alone still removes JNI-only constructors).
# https://onnxruntime.ai/docs/build/android.html
-keep class ai.onnxruntime.** { *; }
# The runtime remains optional in explicitly configured rules-only builds.
-dontwarn ai.onnxruntime.**
