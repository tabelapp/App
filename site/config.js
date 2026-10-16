/*
 * Configuração do site do Tabelapp: o ÚNICO lugar para trocar o contato.
 * whatsapp: só números, com 55 + DDD (é o mesmo número do app, em
 * app/.../core/ContatoTabelapp.kt). Deixe "" para esconder os botões de WhatsApp.
 */
window.TABELAPP = {
  whatsapp: "5524988029067",
  email: "tabelapp.economia@gmail.com"
};

/* Preenche os links de contato marcados nas páginas (não precisa mexer aqui). */
document.addEventListener("DOMContentLoaded", function () {
  var c = window.TABELAPP || {};
  document.querySelectorAll("[data-whatsapp]").forEach(function (el) {
    if (!c.whatsapp) { el.hidden = true; return; }
    var msg = el.getAttribute("data-whatsapp");
    el.href = "https://wa.me/" + c.whatsapp + (msg ? "?text=" + encodeURIComponent(msg) : "");
    el.hidden = false;
  });
  document.querySelectorAll("[data-email]").forEach(function (el) {
    if (!c.email) return;
    var assunto = el.getAttribute("data-email");
    el.href = "mailto:" + c.email + (assunto ? "?subject=" + encodeURIComponent(assunto) : "");
    if (!el.textContent.trim()) el.textContent = c.email;
  });
});
