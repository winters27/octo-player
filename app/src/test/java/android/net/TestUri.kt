package android.net

// A working Uri for tests on the computer, where the phone's own Uri is
// missing. Backed by java.net.URI; it lives in android.net only because
// Uri can be extended from nowhere else.
class TestUri(private val text: String) : Uri() {
    private val parsed = java.net.URI(text)

    override fun buildUpon(): Builder = throw UnsupportedOperationException()

    override fun getAuthority(): String? = parsed.authority

    override fun getEncodedAuthority(): String? = parsed.rawAuthority

    override fun getEncodedFragment(): String? = parsed.rawFragment

    override fun getEncodedPath(): String? = parsed.rawPath

    override fun getEncodedQuery(): String? = parsed.rawQuery

    override fun getEncodedSchemeSpecificPart(): String? = parsed.rawSchemeSpecificPart

    override fun getEncodedUserInfo(): String? = parsed.rawUserInfo

    override fun getFragment(): String? = parsed.fragment

    override fun getHost(): String? = parsed.host

    override fun getLastPathSegment(): String? = pathSegments.lastOrNull()

    override fun getPath(): String? = parsed.path

    override fun getPathSegments(): List<String> = parsed.path.orEmpty().split('/').filter { it.isNotEmpty() }

    override fun getPort(): Int = parsed.port

    override fun getQuery(): String? = parsed.query

    override fun getScheme(): String? = parsed.scheme

    override fun getSchemeSpecificPart(): String? = parsed.schemeSpecificPart

    override fun getUserInfo(): String? = parsed.userInfo

    override fun isHierarchical(): Boolean = !parsed.isOpaque

    override fun isRelative(): Boolean = !parsed.isAbsolute

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is TestUri && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun compareTo(other: Uri): Int = text.compareTo(other.toString())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: android.os.Parcel, flags: Int) = throw UnsupportedOperationException()
}
