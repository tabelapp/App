package br.com.tabelapp.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lê a planilha de preços do PDV: Excel (.xlsx) ou texto separado por ";" ou ","
 * (.csv). Devolve as células como texto, linha a linha, já sem o cabeçalho —
 * a validação fica em [Planilha.validar].
 *
 * Colunas: A = produto, B = preço, C = validade (opcional), D = OBS (opcional).
 * Só a primeira aba do Excel é lida.
 */
object LeitorPlanilha {

    /** O que foi lido e a partir de que número de linha (para as mensagens de erro). */
    data class Lida(val celulas: List<List<String?>>, val primeiraLinha: Int)

    fun ler(bytes: ByteArray, nomeArquivo: String): Lida {
        val linhas = if (ehXlsx(bytes, nomeArquivo)) lerXlsx(bytes) else lerCsv(bytes)
        // Cabeçalho ("Produto | Preço ..."): primeira linha cujo preço não é número.
        val temCabecalho = linhas.firstOrNull()?.let { Dinheiro.parse(it.getOrNull(1).orEmpty()) == null } == true
        return if (temCabecalho) Lida(linhas.drop(1), 2) else Lida(linhas, 1)
    }

    /** Modelo para o PDV preencher (abre no Excel, Google Planilhas etc.). */
    val modeloCsv: String =
        "Produto;Preço;Validade (opcional);OBS (opcional)\n" +
            "Arroz Tio João Tipo 1 5kg;24,90;;\n" +
            "Cerveja Brahma lata 350ml;3,49;;Gelada\n"

    private fun ehXlsx(bytes: ByteArray, nome: String) =
        nome.endsWith(".xlsx", ignoreCase = true) ||
            (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())

    // ------------------------------------------------------------------ csv

    private fun lerCsv(bytes: ByteArray): List<List<String?>> {
        var texto = String(bytes, Charsets.UTF_8)
        if (texto.contains('�')) texto = String(bytes, Charsets.ISO_8859_1) // Excel antigo
        texto = texto.removePrefix("﻿")
        val linhas = texto.lines().filter { it.isNotBlank() }
        // Excel brasileiro usa ";" (a vírgula é decimal); fora isso, ",".
        val separador = if (linhas.firstOrNull()?.contains(';') == true) ';' else ','
        return linhas.map { dividir(it, separador) }
    }

    /** Divide respeitando aspas ("Arroz, tipo 1";"24,90"). */
    private fun dividir(linha: String, sep: Char): List<String?> {
        val campos = mutableListOf<String?>()
        val atual = StringBuilder()
        var aspas = false
        var i = 0
        while (i < linha.length) {
            val c = linha[i]
            when {
                c == '"' && aspas && i + 1 < linha.length && linha[i + 1] == '"' -> { atual.append('"'); i++ }
                c == '"' -> aspas = !aspas
                c == sep && !aspas -> { campos += atual.toString(); atual.clear() }
                else -> atual.append(c)
            }
            i++
        }
        campos += atual.toString()
        return campos
    }

    // ------------------------------------------------------------------ xlsx

    private fun lerXlsx(bytes: ByteArray): List<List<String?>> {
        val arquivos = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) {
                    arquivos[e.name] = zip.readBytes()
                }
            }
        }
        val compartilhados = arquivos["xl/sharedStrings.xml"]?.let(::textosCompartilhados).orEmpty()
        val aba = arquivos["xl/worksheets/sheet1.xml"]
            ?: arquivos.entries.filter { it.key.startsWith("xl/worksheets/sheet") }.minByOrNull { it.key }?.value
            ?: throw IllegalArgumentException("Planilha sem abas")
        val doc = xml(aba)
        val linhas = sortedMapOf<Int, MutableMap<Int, String>>()
        val rows = doc.getElementsByTagName("row")
        for (r in 0 until rows.length) {
            val row = rows.item(r) as Element
            val numero = row.getAttribute("r").toIntOrNull() ?: (r + 1)
            val cells = row.getElementsByTagName("c")
            for (c in 0 until cells.length) {
                val cell = cells.item(c) as Element
                val coluna = coluna(cell.getAttribute("r")) ?: c
                val tipo = cell.getAttribute("t")
                val valor = when (tipo) {
                    "s" -> texto(cell, "v")?.toIntOrNull()?.let { compartilhados.getOrNull(it) }
                    "inlineStr" -> cell.getElementsByTagName("t").let { ts ->
                        (0 until ts.length).joinToString("") { ts.item(it).textContent }
                    }
                    else -> texto(cell, "v")
                } ?: continue
                linhas.getOrPut(numero) { mutableMapOf() }[coluna] = valor
            }
        }
        return linhas.values.map { cols -> (0..3).map { cols[it] } }
    }

    private fun textosCompartilhados(bytes: ByteArray): List<String> {
        val doc = xml(bytes)
        val itens = doc.getElementsByTagName("si")
        return (0 until itens.length).map { i ->
            val ts = (itens.item(i) as Element).getElementsByTagName("t")
            (0 until ts.length).joinToString("") { ts.item(it).textContent }
        }
    }

    private fun texto(cell: Element, tag: String): String? =
        cell.getElementsByTagName(tag).item(0)?.textContent

    /** "B12" -> 1 (A=0). */
    private fun coluna(ref: String): Int? {
        val letras = ref.takeWhile { it.isLetter() }.uppercase()
        if (letras.isEmpty()) return null
        return letras.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // Sem entidades externas (arquivo vem de fora). O parser do Android não conhece
        // essa opção (e já não resolve entidades externas): por isso o runCatching.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}
