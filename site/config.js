/*
 * Número de WhatsApp dos botões do site: o ÚNICO lugar para trocá-lo.
 * Só números, com 55 + DDD (o mesmo do app, em core/.../Pdv.kt, ContatoTabelapp).
 * Deixe "" para esconder os botões de WhatsApp.
 * O e-mail de contato é fixo nas páginas (scripts/gerar-site.py, EMAIL).
 */
window.TABELAPP = {
  whatsapp: "5524988029067"
};

/* Preenche os botões de WhatsApp marcados nas páginas (não precisa mexer aqui). */
document.addEventListener("DOMContentLoaded", function () {
  var c = window.TABELAPP || {};
  document.querySelectorAll("[data-whatsapp]").forEach(function (el) {
    if (!c.whatsapp) { el.hidden = true; return; }
    var msg = el.getAttribute("data-whatsapp");
    el.href = "https://wa.me/" + c.whatsapp + (msg ? "?text=" + encodeURIComponent(msg) : "");
    el.hidden = false;
  });
});
