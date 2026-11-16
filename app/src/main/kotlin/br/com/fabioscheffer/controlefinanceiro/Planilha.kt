package br.com.fabioscheffer.controlefinanceiro

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Excel (.xlsx): gravar e ler, no mesmo formato do site e do programa Windows (planilha.py). O
 * arquivo é montado e lido à mão (zip + XML), para não pôr uma biblioteca grande no app.
 */
object Planilha {
    val COLUNAS = listOf(
        "Data Origem", "Data Vencimento", "Valor (R\$)", "Banco / Cartão", "Estabelecimento",
        "Forma de Pagamento", "Parcela", "Descrição", "Categoria", "Credor", "Devedor", "Status", "Tipo",
    )
    private val LARGURAS = listOf(14, 16, 12, 18, 22, 20, 10, 36, 18, 20, 20, 10, 12)

    // ---- gravar ----

    private fun data(iso: String?): String = if (iso.isNullOrEmpty()) "" else Formatacao.dataCurta(iso)

    /** As linhas de um lançamento: a principal e, se houver, uma por item e uma por pagamento. */
    fun linhasDe(l: Lancamento): List<List<Any?>> {
        val saida = ArrayList<List<Any?>>()
        val status = if (l.pago) "Pago" else "Pendente"
        if (l.itens.isNotEmpty() || l.pagamentos.isNotEmpty()) {
            saida += listOf(
                data(l.dataOrigem), data(l.dataVencimento), l.valor,
                if (l.pagamentos.isNotEmpty()) "(dividido)" else l.banco, l.estabelecimento,
                if (l.pagamentos.isNotEmpty()) "(dividido)" else l.forma,
                l.parcela ?: "À vista",
                if (l.itens.isNotEmpty()) "(lançamento com ${l.itens.size} itens)" else l.descricao,
                if (l.itens.isNotEmpty()) "(múltiplas)" else l.categoria,
                l.credor, l.devedor, status, "Principal",
            )
            l.itens.forEachIndexed { n, i ->
                saida += listOf(data(i.dataOriginal), "", i.valor, "", "", "", "", i.descricao, i.categoria, "", "", "", "Item ${n + 1}")
            }
            l.pagamentos.forEachIndexed { n, p ->
                saida += listOf("", "", p.valor, p.banco, "", p.forma, "", "", "", "", "", "", "Pagamento ${n + 1}")
            }
        } else {
            saida += listOf(
                data(l.dataOrigem), data(l.dataVencimento), l.valor, l.banco, l.estabelecimento, l.forma,
                l.parcela ?: "À vista", l.descricao, l.categoria, l.credor, l.devedor, status, "Simples",
            )
        }
        return saida
    }

    private fun escapar(t: String): String {
        val b = StringBuilder(t.length)
        for (c in t) {
            when {
                c == '&' -> b.append("&amp;")
                c == '<' -> b.append("&lt;")
                c == '>' -> b.append("&gt;")
                c == '"' -> b.append("&quot;")
                c.code < 32 && c != '\n' && c != '\t' && c != '\r' -> {}
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    private fun letra(coluna: Int): String {
        var n = coluna
        var s = ""
        do {
            s = ('A' + n % 26) + s
            n = n / 26 - 1
        } while (n >= 0)
        return s
    }

    private fun folha(linhas: List<List<Any?>>, larguras: List<Int>): String {
        val x = StringBuilder()
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        x.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        x.append("<cols>")
        larguras.forEachIndexed { i, w -> x.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        x.append("</cols><sheetData>")
        linhas.forEachIndexed { r, linha ->
            x.append("<row r=\"${r + 1}\">")
            linha.forEachIndexed { c, v ->
                val ref = letra(c) + (r + 1)
                when (v) {
                    null -> {}
                    is Number -> x.append("<c r=\"$ref\"><v>${String.format(Locale.ROOT, "%s", v.toDouble().toBigDecimalPlain())}</v></c>")
                    else -> {
                        val t = v.toString()
                        if (t.isNotEmpty()) x.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapar(t)}</t></is></c>")
                    }
                }
            }
            x.append("</row>")
        }
        x.append("</sheetData></worksheet>")
        return x.toString()
    }

    private fun Double.toBigDecimalPlain(): String = java.math.BigDecimal.valueOf(this).toPlainString()

    private fun dois(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    fun exportar(lancamentos: List<Lancamento>): ByteArray {
        val ordenados = lancamentos.sortedWith(
            compareBy<Lancamento>({ it.dataOrigem ?: "" }, { it.id < 0 }, { Math.abs(it.id) }),
        )
        val linhas = ArrayList<List<Any?>>()
        linhas.add(COLUNAS)
        ordenados.forEach { linhas.addAll(linhasDe(it)) }
        val total = lancamentos.sumOf { it.valor }
        val pago = lancamentos.filter { it.pago }.sumOf { it.valor }
        val resumo = listOf(
            listOf("Item", "Valor"),
            listOf("Total de Registros", lancamentos.size),
            listOf("Total Geral (R\$)", dois(total)),
            listOf("Total Pago (R\$)", dois(pago)),
            listOf("Total Pendente (R\$)", dois(total - pago)),
        )
        val saida = ByteArrayOutputStream()
        ZipOutputStream(saida).use { z ->
            fun arquivo(nome: String, conteudo: String) {
                z.putNextEntry(ZipEntry(nome))
                z.write(conteudo.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            val cab = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            arquivo(
                "[Content_Types].xml",
                cab + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>",
            )
            arquivo(
                "_rels/.rels",
                cab + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>",
            )
            arquivo(
                "xl/workbook.xml",
                cab + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                    "<sheet name=\"Lançamentos\" sheetId=\"1\" r:id=\"rId1\"/>" +
                    "<sheet name=\"Resumo\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
            )
            arquivo(
                "xl/_rels/workbook.xml.rels",
                cab + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>" +
                    "</Relationships>",
            )
            arquivo("xl/worksheets/sheet1.xml", folha(linhas, LARGURAS))
            arquivo("xl/worksheets/sheet2.xml", folha(resumo, listOf(22, 16)))
        }
        return saida.toByteArray()
    }

    // ---- ler ----

    class Aba(val nome: String, val linhas: List<List<Any?>>)

    private fun zipEmMapa(bytes: ByteArray): Map<String, ByteArray> {
        val mapa = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory) mapa[e.name] = z.readBytes()
                }
            }
        } catch (e: Exception) {
            throw ErroDocumento("Não foi possível ler a planilha. Verifique se o arquivo não está corrompido.")
        }
        if (mapa.isEmpty()) throw ErroDocumento("Não foi possível ler a planilha. Só o formato XLSX é aceito.")
        return mapa
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().also { it.setInput(ByteArrayInputStream(bytes), null) }

    private fun textosCompartilhados(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val p = parser(bytes)
        val lista = ArrayList<String>()
        var atual: StringBuilder? = null
        var emT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "si" -> atual = StringBuilder()
                    "t" -> emT = true
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (emT) atual?.append(p.text)
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "t" -> emT = false
                    "si" -> {
                        lista += atual.toString()
                        atual = null
                    }
                }
            }
            ev = p.next()
        }
        return lista
    }

    private fun colunaDe(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (c !in 'A'..'Z') break
            n = n * 26 + (c - 'A' + 1)
        }
        return n - 1
    }

    private fun lerFolha(bytes: ByteArray, textos: List<String>): List<List<Any?>> {
        val p = parser(bytes)
        val linhas = ArrayList<List<Any?>>()
        var linha: MutableList<Any?>? = null
        var coluna = 0
        var tipo = ""
        var valor: StringBuilder? = null
        var emV = false
        var emT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> linha = ArrayList()
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        if (ref != null) coluna = colunaDe(ref) else coluna = linha?.size ?: 0
                        tipo = p.getAttributeValue(null, "t") ?: ""
                        valor = StringBuilder()
                    }
                    "v" -> emV = true
                    "t" -> emT = true
                }
                XmlPullParser.TEXT -> if (emV || emT) valor?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> emV = false
                    "t" -> emT = false
                    "c" -> {
                        val bruto = valor?.toString() ?: ""
                        val v: Any? = when (tipo) {
                            "s" -> bruto.trim().toIntOrNull()?.let { textos.getOrNull(it) }
                            "inlineStr", "str" -> bruto
                            "b" -> if (bruto.trim() == "1") "VERDADEIRO" else "FALSO"
                            else -> if (bruto.isBlank()) null else bruto.trim().toDoubleOrNull() ?: bruto
                        }
                        val l = linha
                        if (l != null) {
                            while (l.size < coluna) l += null
                            l += v
                        }
                        valor = null
                    }
                    "row" -> {
                        linha?.let { linhas += it }
                        linha = null
                    }
                }
            }
            ev = p.next()
        }
        return linhas
    }

    /** As abas do arquivo .xlsx, na ordem do livro. Levanta [ErroDocumento] se não for um XLSX legível. */
    fun abas(bytes: ByteArray): List<Aba> {
        val arq = zipEmMapa(bytes)
        val livro = arq["xl/workbook.xml"] ?: throw ErroDocumento("Não foi possível ler a planilha. Só o formato XLSX é aceito.")
        val textos = textosCompartilhados(arq["xl/sharedStrings.xml"])
        // id da relação -> arquivo
        val alvos = HashMap<String, String>()
        arq["xl/_rels/workbook.xml.rels"]?.let { r ->
            val p = parser(r)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id")
                    val alvo = p.getAttributeValue(null, "Target")
                    if (id != null && alvo != null) {
                        alvos[id] = if (alvo.startsWith("/")) alvo.removePrefix("/") else "xl/$alvo"
                    }
                }
                ev = p.next()
            }
        }
        val abas = ArrayList<Aba>()
        val p = parser(livro)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                val nome = p.getAttributeValue(null, "name") ?: "Planilha"
                var rid: String? = null
                for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == "id") rid = p.getAttributeValue(i)
                val caminho = alvos[rid] ?: "xl/worksheets/sheet${abas.size + 1}.xml"
                arq[caminho]?.let { abas += Aba(nome, lerFolha(it, textos)) }
            }
            ev = p.next()
        }
        if (abas.isEmpty()) throw ErroDocumento("A planilha parece estar vazia.")
        return abas
    }

    private fun textoDe(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    const val LIMITE_TEXTO = 50_000

    /** A planilha toda como texto, para a IA ler (como o programa Windows). */
    fun emTexto(bytes: ByteArray): String {
        val partes = ArrayList<String>()
        for (aba in abas(bytes)) {
            partes += "--- Planilha: ${aba.nome} ---"
            for (linha in aba.linhas) partes += linha.joinToString(",") { textoDe(it) }
            partes += ""
        }
        if (partes.none { it.isNotBlank() && !it.startsWith("---") && it.trim(',').isNotEmpty() }) {
            throw ErroDocumento("A planilha parece estar vazia.")
        }
        return partes.joinToString("\n").take(LIMITE_TEXTO)
    }

    // ---- importar ----

    private fun lerData(v: Any?): String? {
        if (v == null) return null
        if (v is Double) {
            return try {
                LocalDate.of(1899, 12, 30).plusDays(v.toLong()).toString()
            } catch (e: Exception) {
                null
            }
        }
        val t = v.toString().trim()
        Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""").find(t)?.let {
            return try {
                LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()).toString()
            } catch (e: Exception) {
                null
            }
        }
        Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(t)?.let {
            return try {
                LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()).toString()
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private fun numero(v: Any?): Double = when (v) {
        is Double -> v
        null -> 0.0
        else -> v.toString().replace(",", ".").trim().toDoubleOrNull() ?: 0.0
    }

    private fun texto(v: Any?): String = textoDe(v).trim()

    private fun ou(v: Any?): String? = texto(v).ifEmpty { null }

    /** Os lançamentos da primeira aba, com itens e pagamentos agrupados. */
    fun ler(bytes: ByteArray): List<DadosNovo> {
        val linhas = abas(bytes).first().linhas
        val cab = linhas.firstOrNull()?.map { texto(it) }
        if (cab == null || "Valor (R\$)" !in cab) throw ErroDocumento("A planilha não tem as colunas esperadas.")
        class Em(val d: DadosNovo, val itens: MutableList<Item>, val pagamentos: MutableList<Pagamento>)
        val saida = ArrayList<Em>()
        for (valores in linhas.drop(1)) {
            if (valores.all { texto(it).isEmpty() }) continue
            val row = HashMap<String, Any?>()
            cab.forEachIndexed { i, n -> row[n] = valores.getOrNull(i) }
            val tipo = texto(row["Tipo"])
            if (tipo.startsWith("Item ")) {
                saida.lastOrNull()?.itens?.add(
                    Item(numero(row["Valor (R\$)"]), ou(row["Categoria"]), ou(row["Descrição"]), lerData(row["Data Origem"])),
                )
                continue
            }
            if (tipo.startsWith("Pagamento ")) {
                saida.lastOrNull()?.pagamentos?.add(
                    Pagamento(ou(row["Banco / Cartão"]), ou(row["Forma de Pagamento"]), numero(row["Valor (R\$)"])),
                )
                continue
            }
            val banco = texto(row["Banco / Cartão"])
            val forma = texto(row["Forma de Pagamento"])
            val parcela = texto(row["Parcela"]).let { if (it.lowercase() in setOf("à vista", "a vista")) "" else it }
            val origem = lerData(row["Data Origem"]) ?: continue
            saida += Em(
                DadosNovo(
                    dataOrigem = origem,
                    dataVencimento = lerData(row["Data Vencimento"]),
                    valor = numero(row["Valor (R\$)"]),
                    banco = if (banco == "(dividido)") null else banco.ifEmpty { null },
                    forma = if (forma == "(dividido)") null else forma.ifEmpty { null },
                    estabelecimento = ou(row["Estabelecimento"]),
                    categoria = ou(row["Categoria"]),
                    credor = ou(row["Credor"]),
                    devedor = ou(row["Devedor"]),
                    descricao = ou(row["Descrição"]),
                    pago = texto(row["Status"]).lowercase() == "pago",
                    parcela = parcela.ifEmpty { null },
                ),
                ArrayList(), ArrayList(),
            )
        }
        return saida.map { it.d.copy(itens = it.itens, pagamentos = it.pagamentos) }
    }

    /** Separa o que já existe (mesma identidade) do que é novo. Devolve os novos e quantos eram repetidos. */
    fun novosERepetidos(lidos: List<DadosNovo>, existentes: List<Lancamento>): Pair<List<DadosNovo>, Int> {
        val marcas = existentes.map { Duplicados.assinatura(it) }.toHashSet()
        val novos = ArrayList<DadosNovo>()
        for (d in lidos) {
            // Repetido dentro da própria planilha também conta: só o primeiro entra.
            if (marcas.add(Duplicados.assinatura(comoLancamento(d)))) novos += d
        }
        return novos to (lidos.size - novos.size)
    }

    fun comoLancamento(d: DadosNovo) = Lancamento(
        0, d.dataOrigem, d.dataVencimento, d.valor, d.banco, d.estabelecimento, d.forma, d.parcela,
        d.descricao, d.categoria, d.pago, d.credor, d.devedor, d.itens, d.pagamentos,
    )

    /** Os textos das listas que as linhas novas usam, para acrescentar às listas como o site faz. */
    fun opcoesDe(lancamentos: List<DadosNovo>): Map<String, Set<String>> {
        val saida = LISTAS_DE_OPCOES.associate { it.first to LinkedHashSet<String>() }
        for (l in lancamentos) {
            l.banco?.let { saida.getValue("banco") += it }
            l.forma?.let { saida.getValue("forma") += it }
            l.estabelecimento?.let { saida.getValue("estabelecimento") += it }
            l.categoria?.let { saida.getValue("categoria") += it }
            l.credor?.let { saida.getValue("credor") += it }
            l.devedor?.let { saida.getValue("devedor") += it }
            l.itens.forEach { i -> i.categoria?.let { saida.getValue("categoria") += it } }
            l.pagamentos.forEach { p ->
                p.banco?.let { saida.getValue("banco") += it }
                p.forma?.let { saida.getValue("forma") += it }
            }
        }
        return saida
    }
}

/** O que a planilha escolhida trouxe: os lançamentos novos e quantos já existiam. */
data class Importacao(val nome: String, val novos: List<DadosNovo>, val repetidos: Int)
