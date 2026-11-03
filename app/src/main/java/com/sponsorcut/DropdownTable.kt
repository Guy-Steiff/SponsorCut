// app/src/main/java/com/sponsorcut/DropdownTable.kt

package com.sponsorcut

data class SpinnerRow<out T>(
    val id: String,
    val label: String,
    val value: T,
    val enabled: Boolean = true,
    val isAuto: Boolean = false,
    val isInput: Boolean = false,
    val metadata: Map<String, Any?> = emptyMap(),
    val originalIndex: Int? = null
)

class DropdownTable<T>(
    private val originalValues: List<T>,
    private val displayValue: (T) -> String = { it.toString() },
    private val normalize: (T) -> String = { it.toString().trim() },
    private val allowSyntheticInput: Boolean = true
) {
    fun rowsForInput(
        inputValue: T?,
        inputRealValueOverride: T? = null
    ): List<SpinnerRow<T>> {
        val originalRows: List<SpinnerRow<T>> = originalValues.map { value ->
            SpinnerRow(
                id = valueId(value),
                value = value,
                label = displayValue(value)
            )
        }

        val effectiveInputValue = inputRealValueOverride ?: inputValue

        if (effectiveInputValue == null) {
            return originalRows
        }

        val normalizedInput = normalize(effectiveInputValue)

        val matchingRow = originalRows.firstOrNull { row ->
            normalize(row.value) == normalizedInput
        }

        return when {
            matchingRow != null -> {
                val inputRow = matchingRow.copy(
                    id = inputId(effectiveInputValue),
                    label = "${matchingRow.value} - input",
                    isInput = true
                )
                val result = mutableListOf(inputRow)
                result.addAll(originalRows.filterNot { it.id == matchingRow.id })
                result
            }

            allowSyntheticInput -> {
                listOf(
                    SpinnerRow<T>(
                        id = inputId(effectiveInputValue),
                        value = effectiveInputValue,
                        label = "$effectiveInputValue - input",
                        isInput = true
                    )
                ) + originalRows
            }

            else -> originalRows
        }
    }

    fun rowForId(
        rows: List<SpinnerRow<T>>,
        id: String?
    ): SpinnerRow<T>? {
        return rows.firstOrNull {
            it.id == id
        }
    }

    fun valueId(value: T): String = "value:${normalize(value)}"

    private fun inputId(value: T): String = "input:${normalize(value)}"
}