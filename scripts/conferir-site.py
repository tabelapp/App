#!/usr/bin/env python3
"""Confere o site gerado (site/) antes de publicar: nenhum link interno quebrado (404),
menu completo em todas as páginas, e-mail de contato com mailto: e nada de CNPJ inventado.

Uso: python3 scripts/conferir-site.py   (o CI roda depois de gerar-site.py)
"""
import pathlib
import re
import sys
from urllib.parse import urlsplit

SITE = pathlib.Path(__file__).resolve().parent.parent / "site"
EMAIL = "tabelapp.economia@gmail.com"
MENU = ["index.html", "planos.html", "privacidade.html", "termos.html", "excluir-conta.html"]

erros = []
for pagina in sorted(SITE.glob("*.html")):
    texto = pagina.read_text(encoding="utf-8")
    for ref in re.findall(r'(?:href|src)="([^"]+)"', texto):
        if ref.startswith(("mailto:", "https://", "http://", "data:", "#")):
            continue
        caminho = urlsplit(ref).path
        if caminho.startswith("/"):
            erros.append(f"{pagina.name}: link absoluto '{ref}' quebra em /App/ (use caminho relativo)")
        elif not (SITE / caminho).is_file():
            erros.append(f"{pagina.name}: link quebrado '{ref}'")
    nav = re.search(r"<nav.*?</nav>", texto, re.S)
    if not nav or re.findall(r'href="([^"]+)"', nav.group(0)) != MENU:
        erros.append(f"{pagina.name}: menu diferente de {MENU}")
    if f'href="mailto:{EMAIL}"' not in texto:
        erros.append(f"{pagina.name}: falta o e-mail {EMAIL} com mailto: no rodapé")
    if re.search(r"\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}|CNPJ: em breve", texto):
        erros.append(f"{pagina.name}: não cite CNPJ do Tabelapp enquanto ele não existir")
for pagina in MENU:
    if not (SITE / pagina).is_file():
        erros.append(f"falta a página {pagina}")

if erros:
    print("\n".join(erros))
    sys.exit(1)
print(f"site ok: {len(list(SITE.glob('*.html')))} páginas, links internos, menu e contato conferidos")
