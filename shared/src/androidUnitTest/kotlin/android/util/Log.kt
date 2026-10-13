package android.util

/** JVM-only Logcat adapter; all other Android framework stubs remain strict. */
class Log private constructor() {
    companion object {
        @JvmStatic fun d(tag: String, message: String): Int = write("D", tag, message)
        @JvmStatic fun i(tag: String, message: String): Int = write("I", tag, message)
        @JvmStatic fun w(tag: String, message: String): Int = write("W", tag, message)
        @JvmStatic fun e(tag: String, message: String): Int = write("E", tag, message)

        private fun write(level: String, tag: String, message: String): Int {
            System.err.println("$level/$tag: $message")
            return 0
        }
    }
}
