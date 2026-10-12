package br.com.tabelapp.ui.busca

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.tabelapp.R
import br.com.tabelapp.core.Cotacao
import br.com.tabelapp.dados.ErroAmigavel
import br.com.tabelapp.ui.comum.AcaoErro
import br.com.tabelapp.ui.comum.FolhaMensagem
import kotlinx.coroutines.launch

private enum class EtapaSobrePreco { INFO, DENUNCIA, OBRIGADO }

/** Motivos da denúncia: código gravado no banco + texto do chip. */
private val motivos = listOf(
    "combo_ou_promocao" to R.string.denuncia_motivo_combo,
    "preco_mudou" to R.string.denuncia_motivo_preco_mudou,
    "produto_errado" to R.string.denuncia_motivo_produto_errado,
    "outro" to R.string.denuncia_motivo_outro,
)

/**
 * "Sobre este preço" (ⓘ do card): de onde veio o valor e que ele pode mudar.
 * O link "Esse preço não confere" registra uma denúncia leve, com motivo opcional.
 */
@Composable
fun FolhaSobrePreco(cotacao: Cotacao, aoDenunciar: suspend (motivo: String?) -> Boolean, aoFechar: () -> Unit) {
    var etapa by remember { mutableStateOf(EtapaSobrePreco.INFO) }
    var motivo by remember { mutableStateOf<String?>(null) }
    var enviando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val escopo = rememberCoroutineScope()

    when (etapa) {
        EtapaSobrePreco.INFO -> FolhaMensagem(
            titulo = stringResource(R.string.sobre_preco_titulo),
            texto = stringResource(
                if (cotacao.fonte.ehDoPdv) R.string.sobre_preco_estabelecimento else R.string.sobre_preco_usuario
            ),
            principal = AcaoErro(stringResource(R.string.sobre_preco_entendi), aoFechar),
            secundario = AcaoErro(stringResource(R.string.sobre_preco_nao_confere), { etapa = EtapaSobrePreco.DENUNCIA }),
            aoFechar = aoFechar,
        )

        EtapaSobrePreco.DENUNCIA -> FolhaMensagem(
            titulo = stringResource(R.string.denuncia_titulo),
            texto = stringResource(R.string.denuncia_texto),
            principal = AcaoErro(stringResource(R.string.denuncia_enviar), {
                enviando = true
                erro = null
                escopo.launch {
                    try {
                        aoDenunciar(motivo)
                        etapa = EtapaSobrePreco.OBRIGADO
                    } catch (e: ErroAmigavel) {
                        erro = e.message
                    } finally {
                        enviando = false
                    }
                }
            }, habilitado = !enviando),
            secundario = AcaoErro(stringResource(R.string.denuncia_cancelar), aoFechar),
            aoFechar = aoFechar,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                motivos.forEach { (codigo, texto) ->
                    FilterChip(
                        selected = motivo == codigo,
                        onClick = { motivo = if (motivo == codigo) null else codigo },
                        label = { Text(stringResource(texto)) },
                    )
                }
            }
            erro?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        // Já tinha avisado antes? O agradecimento é o mesmo: nada a corrigir para a pessoa.
        EtapaSobrePreco.OBRIGADO -> FolhaMensagem(
            titulo = stringResource(R.string.sobre_preco_titulo),
            texto = stringResource(R.string.denuncia_obrigado),
            principal = AcaoErro(stringResource(R.string.sobre_preco_entendi), aoFechar),
            aoFechar = aoFechar,
        )
    }
}
