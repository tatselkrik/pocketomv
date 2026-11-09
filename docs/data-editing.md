# Data editing and scoring

## Edit a table

Open **Data**, then choose **Cells** in **Data editor**. Tap a cell, change its value and tap **Save cell**. **Cancel** leaves that cell unchanged. Saved cell changes immediately become the current table, clear the previous analysis result and save automatically on the device.

- Use **Add row** to append a blank row. Tap a row number to insert a row after it or delete that row.
- Use **Add column** for a blank column. Tap a column heading to rename or delete it. Column names must be unique; the table must retain at least one row and one column.
- Use **Go to row** for large tables. Scroll within the grid to reach more rows and swipe sideways for more columns.
- **New empty table** starts a one-cell table when no table or unfinished text is present.
- **Fictional practice data** labels an entered example appropriately.

The editor supports the existing limits: 20,000 rows, 60 columns, 250,000 data cells and 2 MB of editable table text. Entirely empty grid rows are retained in saved work; applying or importing text ignores fully blank rows.

**Text / paste** remains available for CSV-style entry. Apply unfinished text with **Use data** before switching to cells, so switching views cannot silently discard edits. Importing or editing cells changes only the app's table; the original workbook or imported file is not overwritten.

**Undo last table change** restores one preceding table, its settings and any matching result in the running app. Importing another table, applying text or editing raw text clears this undo history. The current table survives reopening; undo history is not saved for later reopening. Removing a selected analysis variable resets analysis options for the remaining columns; other selected column positions are remapped.

## Create a recoded or scored column

In Cells, choose **Recode / score**. Give the new column a unique name, choose the operation and its options, then tap **Preview column**. Review the first eight rows, counts and explanation before tapping **Add scored column**. Preview and Cancel do not change the table.

Every operation adds a separate column and preserves the source values. These are **saved values calculated at creation**, rather than linked formulas. If you edit an input later, recreate its scored column before using the updated scores. Preparation notes record the calculation and appear in copied/saved text reports. CSV exports contain the resulting cells, without formula definitions or preparation notes.

### Reverse scoring

Enter the scale's stated minimum and maximum. The calculation is:

`reversed = minimum + maximum − original`

For a 1–5 item, 1 becomes 5, 2 becomes 4 and 3 remains 3. The app does not infer scale endpoints from the values observed in a small sample. Missing numeric values remain blank; a nonnumeric or out-of-scale answer stops the operation and identifies its row. Decimal and negative scale bounds are supported.

### Recoding

**Exact text** matches case and formatting: `A` differs from `a`, and `1` differs from `1.0`. Blank cells remain missing. Literal text such as `NA` can be explicitly recoded. Set a rule's new value blank to make matching cells missing.

**Numeric ranges** include both endpoints. A blank lower or upper bound leaves that side open. The first matching rule wins when ranges overlap. Numeric range recoding treats blank, `NA`, `N/A` and `NaN` as missing and rejects other nonnumeric source cells.

Choose explicitly whether unmatched values should stay unchanged or become missing. The preview reports matched and unmatched counts. Up to 50 rules are supported; duplicate exact-text rules are rejected.

### Item scores

Select the item columns and choose **Sum** or **Mean**. Reverse-score negatively keyed items first, then select their reversed columns in place of the originals.

- **Require every item**: the score is missing if any selected answer is missing.
- **Use answered items**: enter the minimum number of answered items. Rows below this count are missing. Means divide by the number actually answered. Partial sums are not prorated and should be used only when the questionnaire or assignment permits them.

For example, using three items and a minimum of two answered items:

| Item 1 | Reversed item 2 | Item 3 | Sum requiring every item | Mean using answered items |
| --- | --- | --- | --- | --- |
| 1 | 1 | 2 | 4 | 1.3333… |
| 2 | 2 | 3 | 7 | 2.3333… |
| 3 | missing | 4 | missing | 3.5 |
| 5 | 5 | missing | missing | 5 |

Blank, `NA`, `N/A` and `NaN` are numeric missing values. Other nonnumeric selected answers produce a row-specific error. Decimal arithmetic avoids intermediate overflow and cancellation; means use 34 significant digits before the app's statistical calculations read the results as finite double-precision values. Scores outside the supported finite range are rejected rather than silently written as infinity or zero.

## References

- [jamovi transformed variables: recoding and reverse scoring](https://docs.jamovi.org/data/data_3_transformed_variables.html)
- [jamovi computed variables and sum scores](https://docs.jamovi.org/data/data_2_computed_variables.html)

pocketomv implements the bounded operations described here. It does not run jamovi's formula language or maintain automatically recalculated formula columns.
