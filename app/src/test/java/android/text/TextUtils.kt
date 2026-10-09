package android.text

// The parts of TextUtils the media readers use, working, for tests on the
// computer; classes compiled with the tests come before the phone's stand-ins.
object TextUtils {
    @JvmStatic
    fun isEmpty(text: CharSequence?): Boolean = text.isNullOrEmpty()

    @JvmStatic
    fun equals(a: CharSequence?, b: CharSequence?): Boolean = a?.toString() == b?.toString()
}
