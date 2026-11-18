package io.github.jellyforgedev.widgora.model

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Freezes the field set of [WidgetSnapshot].
 *
 * Every field of this type potentially reaches a foreign application through the bridge, which
 * carries prepared display values and nothing else. Adding a field is therefore not a local
 * change: it widens what WIDGORA gives away. A new field must break this test so that the
 * decision is made deliberately and in review, rather than slipping in with a feature.
 *
 * Java reflection is used so that the check needs no additional dependency.
 */
class WidgetSnapshotContractTest {

    @Test
    fun theFieldSetIsFrozen() {
        val expected = listOf(
            "accentToken",
            "iconToken",
            "primaryValue",
            "title",
            "updatedAtEpochMs",
            "widgetId",
        )
        val actual = WidgetSnapshot::class.java.declaredFields
            .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
            .map { field -> field.name }
            .sorted()
        assertEquals(expected, actual)
    }
}
