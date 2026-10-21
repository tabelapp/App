#!/usr/bin/env python3
"""Gera o site estático do Tabelapp em site/ (publicado pelo GitHub Pages).

- privacidade.html e termos.html saem dos MESMOS textos do app
  (app/src/main/res/raw/*.md): mudou no app, rode este script.
- index.html, planos.html e excluir-conta.html saem de scripts/site/*.html
  (só o miolo da página; cabeçalho e rodapé são aplicados aqui).

Contato oficial: EMAIL abaixo (link mailto: estático, funciona sem JavaScript).
O número de WhatsApp dos botões fica só em site/config.js.

Uso: python3 scripts/gerar-site.py
"""
import html
import pathlib
import re

RAIZ = pathlib.Path(__file__).resolve().parent.parent
TEXTOS_APP = RAIZ / "app/src/main/res/raw"
FONTES = RAIZ / "scripts/site"
DESTINO = RAIZ / "site"
EMAIL = "tabelapp.economia@gmail.com"

# A lupa da marca (app/src/main/res/drawable/lupa_marca.xml) sobre a placa amarela arredondada,
# como o ícone do app: disco amarelo, aro e cabo pretos, cifrão vermelho escuro.
LOGO = (
    '<svg viewBox="0 0 108 108" aria-hidden="true" fill="none" stroke-linecap="round">'
    '<rect width="108" height="108" rx="24" fill="#FDDE03"/>'
    '<g transform="translate(2 4) translate(54 54) scale(1.05) translate(-54 -54)">'
    '<path d="M61,59 L72,70" stroke="#141414" stroke-width="8"/>'
    '<path d="M31,46 A17,17 0 1,1 65,46 A17,17 0 1,1 31,46 Z" fill="#FDDE03" stroke="#141414" stroke-width="6"/>'
    '<path d="M48,34.5 L48,57.5" stroke="#B00000" stroke-width="3"/>'
    '<path d="M52.8,37.8 A5.5,5.5 0 1,0 48,46 A5.5,5.5 0 1,1 43.2,54.2" stroke="#B00000" stroke-width="4"/>'
    "</g></svg>"
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
<p>Fale com a gente: <a href="mailto:{email}">{email}</a></p>
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
            classe=' class="texto"' if texto_longo else "", corpo=corpo.strip(), email=EMAIL,
        ),
        encoding="utf-8",
    )
    print(f"gerado site/{arquivo}")


def inline(texto: str) -> str:
    t = html.escape(texto)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    # O e-mail de contato vira link (mailto:), igual ao rodapé.
    return t.replace(EMAIL, f'<a href="mailto:{EMAIL}">{EMAIL}</a>')


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
