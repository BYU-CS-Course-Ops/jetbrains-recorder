package recorder

internal object RecorderChangeValidator {
    fun applyChange(
        previousText: String,
        offset: Int,
        oldFragment: String,
        newFragment: String
    ): String? {
        if (offset < 0 || offset > previousText.length) {
            return null
        }

        val replaceEnd = offset + oldFragment.length
        if (replaceEnd > previousText.length) {
            return null
        }

        if (previousText.substring(offset, replaceEnd) != oldFragment) {
            return null
        }

        return buildString(previousText.length - oldFragment.length + newFragment.length) {
            append(previousText, 0, offset)
            append(newFragment)
            append(previousText, replaceEnd, previousText.length)
        }
    }
}
