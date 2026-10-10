package android.util

// A working SparseArray for tests on the computer, where the phone's own is
// missing; classes compiled with the tests come before the phone's stand-ins.
class SparseArray<E> {
    private val map = java.util.TreeMap<Int, E>()

    fun get(key: Int): E? = map[key]

    fun get(key: Int, fallback: E): E = map[key] ?: fallback

    fun put(key: Int, value: E) {
        map[key] = value
    }

    fun append(key: Int, value: E) = put(key, value)

    fun remove(key: Int) {
        map.remove(key)
    }

    fun delete(key: Int) = remove(key)

    fun size(): Int = map.size

    fun keyAt(index: Int): Int = map.keys.elementAt(index)

    fun valueAt(index: Int): E = map.values.elementAt(index)

    fun indexOfKey(key: Int): Int = map.keys.indexOf(key)

    fun contains(key: Int): Boolean = map.containsKey(key)

    fun clear() = map.clear()
}
