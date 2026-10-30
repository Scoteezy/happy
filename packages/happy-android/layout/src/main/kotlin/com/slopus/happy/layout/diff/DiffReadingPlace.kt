package com.slopus.happy.layout.diff

/** HappyLayout/DiffListLayout.swift: section, code row and fraction under the reader. */
data class DiffReadingPlace(val section: Int, val part: Part) {
    data class Section(val layout: DiffSectionLayout, val files: List<DiffFileLayout> = emptyList())

    sealed interface Part {
        data class Header(val fraction: Double) : Part
        data class Row(val file: Int, val row: Int, val fraction: Double) : Part
        data class Section(val fraction: Double) : Part
    }

    fun y(list: DiffFilesListLayout, sections: List<Section>): Double? {
        val top = list.sections.getOrNull(section)?.minY ?: return null
        val chosen = sections.getOrNull(section) ?: return null
        val layout = chosen.layout
        return when (val part = part) {
            is Part.Header -> top + layout.header.minY + part.fraction * layout.header.height
            is Part.Section -> top + part.fraction * layout.height
            is Part.Row -> {
                val frame = layout.files.getOrNull(part.file) ?: return null
                val file = chosen.files.getOrNull(part.file) ?: return null
                if (part.row !in 0 until file.input.rowCount) return null
                top + frame.minY + file.canvas.minY + file.row(part.row).minY + part.fraction * file.lineHeight
            }
        }
    }

    companion object {
        /** No anchor in notices or beyond the last section. */
        fun at(y: Double, list: DiffFilesListLayout, sections: List<Section>): DiffReadingPlace? {
            val index = list.sections.indexOfFirst { it.minY <= y && y < it.maxY }
            val section = sections.getOrNull(index) ?: return null
            val local = y - list.sections[index].minY
            val header = section.layout.header
            if (local < header.maxY) return DiffReadingPlace(index,
                Part.Header(if (header.height > 0) (local - header.minY) / header.height else 0.0))
            for ((file, frame) in section.layout.files.withIndex()) {
                val layout = section.files.getOrNull(file) ?: continue
                val inCanvas = local - frame.minY - layout.canvas.minY
                if (local < frame.minY || local >= frame.maxY) continue
                val row = layout.rowIndex(inCanvas) ?: continue
                return DiffReadingPlace(index, Part.Row(file, row, (inCanvas - layout.row(row).minY) / layout.lineHeight))
            }
            return DiffReadingPlace(index, Part.Section(if (section.layout.height > 0) local / section.layout.height else 0.0))
        }
    }
}