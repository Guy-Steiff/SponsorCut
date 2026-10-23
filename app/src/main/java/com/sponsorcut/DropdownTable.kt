// app/src/main/java/com/sponsorcut/DropdownTable.kt

package com.sponsorcut

data class DropdownRow<out T>(
    val indexAtOrig: Int,
    val realValue: T,
    val displayValue: String,
    val isInput: Boolean = false
)

class DropdownTable<T>(
    private val originalValues: List<T>,
    private val displayValue: (T) -> String = { it.toString() },
    private val normalize: (T) -> String = { it.toString().trim() },
    private val allowSyntheticInput: Boolean = true
) {
    val original: List<T> = originalValues.toList()

    fun rowsForInput(
        inputValue: T?,
        inputRealValueOverride: T? = null
    ): List<DropdownRow<T>> {
        val originalRows: List<DropdownRow<T>> = originalValues.mapIndexed { index, value ->
            DropdownRow(
                indexAtOrig = index,
                realValue = value,
                displayValue = displayValue(value)
            )
        }

        val effectiveInputValue = inputRealValueOverride ?: inputValue

        if (effectiveInputValue == null) {
            return originalRows
        }

        val normalizedInput = normalize(effectiveInputValue)

        val matchingRow = originalRows.firstOrNull { row ->
            normalize(row.realValue) == normalizedInput
        }

        return when {
            matchingRow != null -> {
                val inputRow: DropdownRow<T> = matchingRow.copy(
                    displayValue = "${matchingRow.realValue} - input",
                    isInput = true
                )
                val remainingRows: List<DropdownRow<T>> = originalRows.filterNot {
                    it.indexAtOrig == matchingRow.indexAtOrig
                }
                val result: MutableList<DropdownRow<T>> = mutableListOf(inputRow)
                result.addAll(remainingRows)
                result
            }

            allowSyntheticInput -> {
                listOf(
                    DropdownRow(
                        indexAtOrig = -1,
                        realValue = effectiveInputValue,
                        displayValue = "$effectiveInputValue - input",
                        isInput = true
                    )
                ) + originalRows
            }

            else -> originalRows
        }
    }

    fun realValueForDisplay(
        rows: List<DropdownRow<T>>,
        displayValue: String?
    ): T? {
        return rows.firstOrNull {
            it.displayValue == displayValue
        }?.realValue
    }

    fun displayForRealValue(
        rows: List<DropdownRow<T>>,
        realValue: T?
    ): String? {
        return rows.firstOrNull {
            it.realValue == realValue
        }?.displayValue
    }

    fun rowForDisplay(
        rows: List<DropdownRow<T>>,
        displayValue: String?
    ): DropdownRow<T>? {
        return rows.firstOrNull {
            it.displayValue == displayValue
        }
    }
}