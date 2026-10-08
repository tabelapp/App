package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Grava em arquivo PDF o que um [PrintDocumentAdapter] (ex.: de uma WebView)
 * imprimiria — sem abrir a tela de impressão do Android.
 *
 * Fica no pacote android.print porque os construtores dos callbacks de
 * impressão só são acessíveis daqui (técnica conhecida para "imprimir em PDF").
 */
class ImpressoraPdf(private val atributos: PrintAttributes) {

    fun imprimir(adaptador: PrintDocumentAdapter, destino: File, aoTerminar: (Boolean) -> Unit) {
        adaptador.onLayout(null, atributos, null, object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val arquivo = ParcelFileDescriptor.open(
                    destino,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE,
                )
                adaptador.onWrite(arrayOf(PageRange.ALL_PAGES), arquivo, CancellationSignal(),
                    object : PrintDocumentAdapter.WriteResultCallback() {
                        override fun onWriteFinished(pages: Array<out PageRange>?) {
                            arquivo.close()
                            aoTerminar(true)
                        }

                        override fun onWriteFailed(error: CharSequence?) {
                            arquivo.close()
                            aoTerminar(false)
                        }
                    })
            }

            override fun onLayoutFailed(error: CharSequence?) = aoTerminar(false)
        }, null)
    }
}
