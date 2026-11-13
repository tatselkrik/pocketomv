package io.github.tatselkrik.statis130lite

import java.io.InputStream

internal sealed interface DataImport {
    data class Table(val data: CourseData) : DataImport
    data class Workbook(val workbook: ExcelWorkbook) : DataImport
    data class Project(val project: OmvProject) : DataImport
}

internal fun readDataImport(input: InputStream, name: String, mimeType: String?, headers: Boolean): DataImport {
    val buffered = input.buffered()
    buffered.mark(8)
    val signature = ByteArray(4)
    var size = 0
    while (size < signature.size) {
        val count = buffered.read(signature, size, signature.size - size)
        if (count < 0) break
        size += count
    }
    buffered.reset()
    val zip = size >= 2 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()
    if (name.endsWith(".omv", true) || mimeType == OMV_MIME || (zip && OmvProjects.hasManifestHeader(buffered))) {
        require(zip) { "The selected .omv file is not a jamovi archive." }
        return DataImport.Project(OmvProjects.read(buffered))
    }
    val compound = size >= 4 && signature.contentEquals(byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte()))
    require(!compound && !name.endsWith(".xls", true) && !name.endsWith(".xlsb", true)) {
        "Choose an Excel .xlsx workbook. Older .xls and binary .xlsb workbooks are not supported."
    }
    return if (zip || name.endsWith(".xlsx", true) || mimeType == EXCEL_MIME) {
        DataImport.Workbook(ExcelWorkbook.read(buffered))
    } else {
        DataImport.Table(readDelimited(buffered, headers))
    }
}

internal const val EXCEL_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

internal data class WorksheetImportState(
    val name: String,
    val sheets: List<ExcelSheet>,
    val selectedId: String,
    val firstRowHasNames: Boolean,
    val error: String? = null,
)
