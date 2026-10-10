# Ativar o mapa ("Ver no mapa")

O mapa usa o **Maps SDK for Android** do Google. Mapas no app Android **não têm custo** (uso ilimitado),
mas o Google exige uma chave e uma conta de faturamento cadastrada no projeto.

1. Entre em <https://console.cloud.google.com> com a conta do Tabelapp e escolha (ou crie) o projeto.
2. **Faturamento:** menu ☰ → *Faturamento* → vincule uma conta (cartão). O Maps SDK for Android é gratuito.
3. Menu ☰ → *APIs e serviços* → *Biblioteca* → procure **Maps SDK for Android** → *Ativar*.
4. *APIs e serviços* → *Credenciais* → *Criar credenciais* → *Chave de API*. Copie a chave.
5. Na chave criada, toque em *Editar* e restrinja:
   - **Restrições do aplicativo:** *Apps Android* → *Adicionar* →
     - Nome do pacote: `br.com.tabelapp`
     - Impressão digital SHA-1: `1D:12:6E:F5:31:74:5A:C0:91:89:DA:F3:A4:09:B6:36:C6:61:2F:A3`
       (assinatura dos APKs gerados pelo GitHub; a da Play Store será outra — adicionar depois)
   - **Restrições de API:** *Restringir chave* → marque só **Maps SDK for Android**.
   - Salvar.
6. No GitHub: repositório → *Settings* → *Secrets and variables* → *Actions* → *New repository secret*
   - Nome: `MAPS_API_KEY` — Valor: a chave copiada.
7. Peça um novo build (qualquer commit) e instale o APK novo.

Sem a chave, "Ver no mapa" abre uma lista dos locais com o preço; tocar num local abre o Google Maps.

**Endereços:** lojas cadastradas usam a coordenada do cadastro (se houver); os demais (notas fiscais) são
localizados pelo endereço no próprio celular (Geocoder do Android, sem chave e sem custo) e ficam
guardados no aparelho.
