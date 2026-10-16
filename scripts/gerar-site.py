#!/usr/bin/env python3
"""Gera o site estático do Tabelapp em site/ (publicado pelo GitHub Pages).

- privacidade.html e termos.html saem dos MESMOS textos do app
  (app/src/main/res/raw/*.md): mudou no app, rode este script.
- index.html, planos.html e excluir-conta.html saem de scripts/site/*.html
  (só o miolo da página; cabeçalho e rodapé são aplicados aqui).

O contato (WhatsApp e e-mail) fica só em site/config.js.

Uso: python3 scripts/gerar-site.py
"""
import html
import pathlib
import re

RAIZ = pathlib.Path(__file__).resolve().parent.parent
TEXTOS_APP = RAIZ / "app/src/main/res/raw"
FONTES = RAIZ / "scripts/site"
DESTINO = RAIZ / "site"

# A lupa com cifrão da logomarca (app/src/main/res/drawable/logo_lupa.xml).
LOGO = (
    '<svg viewBox="0 0 54 54" aria-hidden="true" fill="none" stroke-linecap="round">'
    '<path d="M37,37 L48,48" stroke="#ECEDEF" stroke-width="8"/>'
    '<path d="M7,24 A17,17 0 1,1 41,24 A17,17 0 1,1 7,24 Z" stroke="#ECEDEF" stroke-width="6"/>'
    '<path d="M24,12.5 L24,35.5" stroke="#F08A45" stroke-width="3"/>'
    '<path d="M28.8,15.8 A5.5,5.5 0 1,0 24,24 A5.5,5.5 0 1,1 19.2,32.2" stroke="#F08A45" stroke-width="4"/>'
    "</svg>"
)
FAVICON = "data:image/svg+xml," + re.sub(r'aria-hidden="true" ', "", LOGO).replace("#", "%23").replace('"', "'")

MENU = [
    ("index.html", "Início"),
    ("planos.html", "Planos"),
    ("privacidade.html", "Privacidade"),
    ("termos.html", "Termos"),
    ("excluir-conta.html", "Excluir conta"),
]

MODELO = """<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{titulo}</title>
<meta name="description" content="{descricao}">
<meta name="theme-color" content="#283847">
<link rel="icon" href="{favicon}">
<link rel="stylesheet" href="style.css">
<script src="config.js" defer></script>
</head>
<body>
<header class="topo"><div class="barra">
<a class="marca" href="index.html">{logo}<span>Tabelapp</span></a>
<nav aria-label="Páginas">{menu}</nav>
</div></header>
<main{classe}>
{corpo}
</main>
<footer><div class="barra">
<p><strong>Tabelapp — Quem pesquisa economiza</strong></p>
<p>Contato: <a data-email="Contato pelo site"></a> · <a data-whatsapp="Olá! Vim pelo site do Tabelapp." hidden>WhatsApp</a></p>
<p>CNPJ: em breve</p>
</div></footer>
</body>
</html>
"""

DESCRICAO_PADRAO = "Tabelapp: compare preços de mercados, lojas e serviços perto de você antes de comprar."


def pagina(arquivo: str, titulo: str, corpo: str, texto_longo: bool, descricao: str = DESCRICAO_PADRAO) -> None:
    atual = ' aria-current="page"'
    menu = " ".join(f'<a href="{href}"{atual if href == arquivo else ""}>{nome}</a>' for href, nome in MENU)
    (DESTINO / arquivo).write_text(
        MODELO.format(
            titulo=html.escape(titulo), descricao=html.escape(descricao), favicon=FAVICON, logo=LOGO, menu=menu,
            classe=' class="texto"' if texto_longo else "", corpo=corpo.strip(),
        ),
        encoding="utf-8",
    )
    print(f"gerado site/{arquivo}")


def inline(texto: str) -> str:
    t = html.escape(texto)
    return re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)


def converter(md: str) -> tuple[str, str]:
    partes, lista, titulo = [], False, "Tabelapp"
    for linha in (l.strip() for l in md.splitlines()):
        if not linha:
            continue
        if linha.startswith("- "):
            if not lista:
                partes.append("<ul>")
                lista = True
            partes.append(f"<li>{inline(linha[2:])}</li>")
            continue
        if lista:
            partes.append("</ul>")
            lista = False
        if linha.startswith("## "):
            partes.append(f"<h2>{inline(linha[3:])}</h2>")
        elif linha.startswith("# "):
            titulo = linha[2:]
            partes.append(f"<h1>{inline(titulo)}</h1>")
        else:
            partes.append(f"<p>{inline(linha)}</p>")
    if lista:
        partes.append("</ul>")
    return titulo, "\n".join(partes)


def main() -> None:
    DESTINO.mkdir(parents=True, exist_ok=True)
    for origem, destino in [("politica_privacidade.md", "privacidade.html"), ("termos_uso.md", "termos.html")]:
        titulo, corpo = converter((TEXTOS_APP / origem).read_text(encoding="utf-8"))
        pagina(destino, titulo, corpo, texto_longo=True)
    for fonte in sorted(FONTES.glob("*.html")):
        texto = fonte.read_text(encoding="utf-8")
        cab = re.match(r"<!--\s*titulo:\s*(.+?)\s*\|\s*descricao:\s*(.+?)\s*-->\s*", texto, re.S)
        if not cab:
            raise SystemExit(f"{fonte.name}: falta o comentário <!-- titulo: ... | descricao: ... --> na 1ª linha")
        pagina(fonte.name, cab.group(1), texto[cab.end():], texto_longo=fonte.name != "index.html",
               descricao=cab.group(2))


if __name__ == "__main__":
    main()
