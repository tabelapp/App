package br.com.tabelapp.core

/** Pacotes de visualização do banner (briefing, seção 5). Mesmos valores da constraint no banco. */
enum class PacoteVisualizacoes(val visualizacoes: Int, val precoCentavos: Long) {
    P100(100, 1000),
    P250(250, 2500),
    P500(500, 5000);
}

enum class OrigemArte(val codigo: String, val rotulo: String) {
    PROPRIA("propria", "Enviar arte própria"),
    IA("ia", "Sugestão gerada por IA"),
    // Publicação imediata: o PDV é dono da informação, não passa pelo Admin.
    ENCARTE_PDV("encarte_pdv", "Usar meu encarte"),
}
