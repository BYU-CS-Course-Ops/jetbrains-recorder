package recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecorderChangeValidatorTest {
    @Test
    fun `apply change returns updated text when offset and fragment match`() {
        assertEquals(
            "print('hi')\n",
            RecorderChangeValidator.applyChange(
                previousText = "print('bye')\n",
                offset = 7,
                oldFragment = "bye",
                newFragment = "hi"
            )
        )
    }

    @Test
    fun `apply change returns null when old fragment does not match tracked state`() {
        assertNull(
            RecorderChangeValidator.applyChange(
                previousText = "abcdef\n",
                offset = 2,
                oldFragment = "ZZ",
                newFragment = "xy"
            )
        )
    }
}
