package com.ivy.googlesheets.sync

import com.ivy.googlesheets.sync.SheetMapping.Field
import java.text.Normalizer

/**
 * Proposes a [SheetMapping] from the header names of a sheet (Spanish and English),
 * so most users only need to confirm it.
 */
object ColumnAutoMapper {
    // More specific fields first, so "Cuenta destino" isn't taken as "Cuenta".
    private val Keywords: List<Pair<Field, List<String>>> = listOf(
        Field.TO_ACCOUNT to listOf("cuenta destino", "to account", "destino", "hacia"),
        Field.TO_AMOUNT to listOf("monto destino", "to amount", "receive amount", "monto recibido"),
        Field.DATE to listOf("fecha", "date", "dia", "day", "when", "cuando"),
        Field.TYPE to listOf("tipo", "type", "movimiento", "ingreso/gasto", "kind"),
        Field.AMOUNT to listOf(
            "monto", "importe", "cantidad", "valor", "amount", "value", "total",
            "precio", "price", "gasto", "egreso", "sum",
        ),
        Field.CURRENCY to listOf("moneda", "divisa", "currency"),
        Field.ACCOUNT to listOf(
            "cuenta", "account", "banco", "bank", "medio de pago", "metodo de pago",
            "forma de pago", "tarjeta", "card", "billetera", "wallet",
        ),
        Field.CATEGORY to listOf("categoria", "category", "rubro", "clasificacion"),
        Field.TITLE to listOf(
            "concepto", "titulo", "title", "detalle", "comercio", "payee", "nombre", "name", "item",
        ),
        Field.DESCRIPTION to listOf(
            "descripcion", "description", "nota", "notas", "notes", "note", "comentario", "memo",
        ),
    )

    fun guess(table: SheetTable): SheetMapping {
        val headers = table.header.map(::normalize)
        val used = mutableSetOf<Int>()
        var mapping = SheetMapping.Empty

        for ((field, keywords) in Keywords) {
            val column = findColumn(headers, keywords, used) ?: continue
            used += column
            mapping = mapping.with(field, column)
        }
        if (mapping.title < 0 && mapping.description >= 0) {
            mapping = mapping.copy(title = mapping.description, description = SheetMapping.NONE)
        }

        val sample = table.sample(SAMPLE_FOR_GUESS)
        val amountSamples = sample.mapNotNull { it.getOrNull(mapping.amount) }
            .mapNotNull(SheetValueNormalizer::normalizeAmount)
        val typeMode = when {
            mapping.type >= 0 -> SheetMapping.TypeMode.COLUMN
            amountSamples.any { it.startsWith("-") } -> SheetMapping.TypeMode.AMOUNT_SIGN
            else -> SheetMapping.TypeMode.ALL_EXPENSES
        }
        val dateOrder = SheetValueNormalizer.guessDateOrder(
            sample.mapNotNull { it.getOrNull(mapping.date) }
        )
        return mapping.copy(typeMode = typeMode, dateOrder = dateOrder)
    }

    /** Exact header match first, then a whole word of the header, then a multi-word phrase. */
    private fun findColumn(headers: List<String>, keywords: List<String>, used: Set<Int>): Int? {
        val matchers: List<(header: String, keyword: String) -> Boolean> = listOf(
            { header, keyword -> header == keyword },
            { header, keyword -> header.split(' ', '/', '_', '-', '(', ')').contains(keyword) },
            { header, keyword -> keyword.contains(' ') && header.contains(keyword) },
        )
        return matchers.firstNotNullOfOrNull { matches ->
            keywords.firstNotNullOfOrNull { keyword ->
                headers.indices.firstOrNull { it !in used && matches(headers[it], keyword) }
            }
        }
    }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")

    private const val SAMPLE_FOR_GUESS = 20
}
