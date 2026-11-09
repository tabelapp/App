package br.com.tabelapp.dados.demo

import br.com.tabelapp.core.IgnoradoEncarte
import br.com.tabelapp.core.ItemLidoEncarte
import br.com.tabelapp.core.LeituraEncarte
import br.com.tabelapp.core.RegrasEncarte
import br.com.tabelapp.core.Unidade
import br.com.tabelapp.dados.EncartesAdminRepositorio
import br.com.tabelapp.dados.LojaEncarte
import br.com.tabelapp.dados.LoteLancado
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.delay

/** Demonstração: a tela mostra dados de exemplo, sem chamar o servidor. */
class DemoEncartesAdmin : EncartesAdminRepositorio {
    override suspend fun ler(arquivo: ByteArray, tipo: String, loja: String): LeituraEncarte {
        delay(900)
        val validade = LocalDate.now().plusDays(6)
        val (cada, obs) = RegrasEncarte.levePor(3, 1000)
        return LeituraEncarte(
            lojaNome = loja.ifBlank { "Mercado Demonstração" },
            itens = listOf(
                ItemLidoEncarte("Café Pilão Tradicional 500g", 1899, validade = validade),
                ItemLidoEncarte("Leite Integral Italac 1L", 479, 529, true, validade = validade, obs = "Limite 6 un. por CPF"),
                ItemLidoEncarte("Picanha Bovina", 6990, unidade = Unidade.KG, validade = validade),
                ItemLidoEncarte("Biscoito Recheado Trakinas 126g", cada, validade = validade, obs = obs),
                ItemLidoEncarte("Cerveja Heineken Lata 350ml", 499, validade = validade, alcoolico = true),
                ItemLidoEncarte("Sabão em Pó Omo 1,6kg", 2290, precoApp = true, validade = validade),
            ),
            ignorados = listOf(IgnoradoEncarte("Detergente Ypê 500ml", "preço ilegível")),
        )
    }

    override suspend fun lancar(loja: String, endereco: String, itens: List<ItemLidoEncarte>): LoteLancado {
        delay(500)
        return LoteLancado(UUID.randomUUID().toString(), itens.size, itens.size)
    }

    override suspend fun desfazer(loteId: String): Int {
        delay(300)
        return 0
    }

    override suspend fun lojasConhecidas(): List<LojaEncarte> =
        listOf(LojaEncarte("Assaí Atacadista", "Rua Washington Luiz, 300 - Petrópolis/RJ"))
}
