#!/usr/bin/env python3
"""Gera docs/site/privacidade.html e termos.html a partir dos textos do app
(app/src/main/res/raw/*.md), para publicar num endereço web (exigência da Play Store).

Uso: python3 scripts/gerar-paginas-legais.py
"""
import html
import pathlib
import re

RAIZ = pathlib.Path(__file__).resolve().parent.parent
ORIGEM = RAIZ / "app/src/main/res/raw"
DESTINO = RAIZ / "docs/site"

MODELO = """<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{titulo}</title>
<style>
  body {{ margin: 0; background: #FFFCEB; color: #1A1A1A; font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }}
  header {{ background: #FFE000; padding: 16px; }}
  header strong {{ color: #C00000; font-size: 22px; }}
  main {{ max-width: 760px; margin: 0 auto; padding: 16px 20px 48px; }}
  h1 {{ font-size: 26px; line-height: 1.25; }}
  h2 {{ font-size: 19px; margin-top: 28px; }}
  li {{ margin: 4px 0; }}
</style>
</head>
<body>
<header><strong>Tabelapp</strong> · Quem pesquisa economiza</header>
<main>
{corpo}
</main>
</body>
</html>
"""


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
        titulo, corpo = converter((ORIGEM / origem).read_text(encoding="utf-8"))
        (DESTINO / destino).write_text(MODELO.format(titulo=html.escape(titulo), corpo=corpo), encoding="utf-8")
        print(f"gerado docs/site/{destino}")


if __name__ == "__main__":
    main()
