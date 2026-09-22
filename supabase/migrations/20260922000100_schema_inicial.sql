-- =============================================================================
-- Tabelapp — schema inicial (briefing do MVP, seção 7)
--
-- Convenções:
--   * Preço é sempre guardado em CENTAVOS (integer) para evitar erro de arredondamento.
--   * Nomes de tabelas/colunas em português, snake_case.
--   * CPF/CNPJ do COMPRADOR nunca é armazenado em lugar nenhum.
--   * Escritas de preço (cotacoes) só acontecem via funções RPC (migração 0200),
--     que aplicam as regras de cota, validade e antifraude. RLS está na 0300.
-- =============================================================================

create schema if not exists extensions;
create extension if not exists unaccent with schema extensions;
create extension if not exists pg_trgm with schema extensions;

-- -----------------------------------------------------------------------------
-- Tipos
-- -----------------------------------------------------------------------------
create type public.tipo_usuario as enum ('cpf', 'cnpj', 'admin');

create type public.fonte_cotacao as enum (
  'pdv_manual',      -- PDV digitou no app
  'pdv_excel',       -- PDV importou planilha
  'usuario_nf',      -- usuário comum lançou a partir de uma Nota Fiscal
  'usuario_encarte'  -- usuário comum fotografou um encarte (aprovado pelo Admin)
);

create type public.status_encarte as enum ('pendente', 'aprovado', 'rejeitado');

-- Toda alteração feita pelo PDV é registrada; só criar_item e aumentar_preco contam na cota.
create type public.tipo_operacao as enum (
  'criar_item', 'aumentar_preco', 'diminuir_preco', 'excluir_item', 'editar_dados'
);

create type public.origem_arte as enum ('propria', 'ia', 'encarte_pdv');
create type public.status_promocao as enum ('aguardando_pagamento', 'ativa', 'pausada', 'esgotada');
create type public.tipo_pagamento as enum ('pacote_operacoes', 'pacote_visualizacoes');
create type public.status_pagamento as enum ('pendente', 'pago', 'cancelado', 'expirado');

-- -----------------------------------------------------------------------------
-- Utilitários
-- -----------------------------------------------------------------------------

-- Texto normalizado para busca: minúsculo, sem acento, espaços colapsados.
-- "Feijão  PRETO" -> "feijao preto"
create function public.normalizar(texto text)
returns text
language sql
immutable
parallel safe
set search_path = ''
as $$
  select btrim(regexp_replace(
    lower(extensions.unaccent('extensions.unaccent'::regdictionary, coalesce(texto, ''))),
    '\s+', ' ', 'g'
  ))
$$;

-- Distância em km entre dois pontos (haversine). Suficiente para uma cidade; sem PostGIS.
create function public.distancia_km(lat1 double precision, lng1 double precision,
                                    lat2 double precision, lng2 double precision)
returns double precision
language sql
immutable
parallel safe
set search_path = ''
as $$
  select case
    when lat1 is null or lng1 is null or lat2 is null or lng2 is null then null
    else 6371.0 * 2 * asin(sqrt(
      power(sin(radians(lat2 - lat1) / 2), 2) +
      cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lng2 - lng1) / 2), 2)
    ))
  end
$$;

-- Mês de referência da cota, no fuso de Petrópolis.
create function public.competencia_atual()
returns date
language sql
stable
set search_path = ''
as $$
  select date_trunc('month', (now() at time zone 'America/Sao_Paulo'))::date
$$;

create function public.hoje()
returns date
language sql
stable
set search_path = ''
as $$
  select (now() at time zone 'America/Sao_Paulo')::date
$$;

create function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- -----------------------------------------------------------------------------
-- usuarios — perfil ligado ao auth.users do Supabase (Google, e-mail, WhatsApp)
-- -----------------------------------------------------------------------------
create table public.usuarios (
  id          uuid primary key references auth.users (id) on delete cascade,
  tipo        public.tipo_usuario not null default 'cpf',
  nome        text check (nome is null or length(nome) <= 120),
  email       text,
  telefone    text,
  -- false até o usuário escolher CPF/CNPJ (ex.: primeiro login pelo Google).
  cadastro_completo boolean not null default false,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create trigger usuarios_updated_at before update on public.usuarios
  for each row execute function public.set_updated_at();

-- Cria o perfil automaticamente quando alguém se cadastra no Supabase Auth.
-- O app pode mandar {"nome": "...", "tipo": "cpf"|"cnpj"} em raw_user_meta_data.
create function public.criar_perfil_usuario()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_tipo public.tipo_usuario := 'cpf';
begin
  if new.raw_user_meta_data ->> 'tipo' = 'cnpj' then
    v_tipo := 'cnpj';  -- nunca 'admin' por aqui
  end if;

  insert into public.usuarios (id, tipo, nome, email, telefone, cadastro_completo)
  values (
    new.id,
    v_tipo,
    coalesce(new.raw_user_meta_data ->> 'nome', new.raw_user_meta_data ->> 'full_name',
             new.raw_user_meta_data ->> 'name'),
    new.email,
    new.phone,
    coalesce(new.raw_user_meta_data ->> 'tipo', '') in ('cpf', 'cnpj')
  )
  on conflict (id) do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.criar_perfil_usuario();

-- -----------------------------------------------------------------------------
-- pdvs — o "CNPJ". Um PDV pode ter várias lojas.
-- -----------------------------------------------------------------------------
create table public.pdvs (
  id             uuid primary key default gen_random_uuid(),
  dono_id        uuid not null references public.usuarios (id) on delete cascade,
  cnpj           char(14) not null unique check (cnpj ~ '^[0-9]{14}$'),
  razao_social   text,
  nome_fantasia  text not null check (length(btrim(nome_fantasia)) between 1 and 120),
  site           text,
  -- true  = "modo rede": edita uma vez, replica para todas as lojas.
  -- false = "modo varejo": cada loja tem sua tabela; a cota conta por loja.
  modo_rede      boolean not null default true,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create index pdvs_dono_idx on public.pdvs (dono_id);
create trigger pdvs_updated_at before update on public.pdvs
  for each row execute function public.set_updated_at();

create table public.lojas (
  id          uuid primary key default gen_random_uuid(),
  pdv_id      uuid not null references public.pdvs (id) on delete cascade,
  nome        text,                                  -- ex.: "Loja Centro" (opcional)
  endereco    text not null check (length(btrim(endereco)) > 0),
  bairro      text,
  cidade      text not null default 'Petrópolis',
  uf          char(2) not null default 'RJ',
  cep         text,
  telefone    text,                                  -- contato próprio de cada loja
  latitude    double precision check (latitude between -90 and 90),
  longitude   double precision check (longitude between -180 and 180),
  ativa       boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index lojas_pdv_idx on public.lojas (pdv_id);
create trigger lojas_updated_at before update on public.lojas
  for each row execute function public.set_updated_at();

-- -----------------------------------------------------------------------------
-- encartes_pendentes — fila do Admin (só encartes de USUÁRIO COMUM)
-- -----------------------------------------------------------------------------
create table public.encartes_pendentes (
  id               uuid primary key default gen_random_uuid(),
  enviado_por      uuid references public.usuarios (id) on delete set null,
  foto_path        text not null,                    -- caminho no bucket "encartes"
  loja_id          uuid references public.lojas (id) on delete set null,
  pdv_nome         text,                             -- quando a loja não é cadastrada
  pdv_endereco     text,
  comentario       text check (comentario is null or length(comentario) <= 500),
  status           public.status_encarte not null default 'pendente',
  revisado_por     uuid references public.usuarios (id) on delete set null,
  revisado_em      timestamptz,
  motivo_rejeicao  text,
  created_at       timestamptz not null default now(),
  constraint encarte_pdv_identificado
    check (loja_id is not null or length(btrim(coalesce(pdv_nome, ''))) > 0)
);

create index encartes_status_idx on public.encartes_pendentes (status, created_at);

-- -----------------------------------------------------------------------------
-- cotacoes — tabela central de preços
-- -----------------------------------------------------------------------------
create table public.cotacoes (
  id                  uuid primary key default gen_random_uuid(),
  -- PDV cadastrado -> loja_id. PDV não cadastrado -> nome/endereço em texto livre.
  loja_id             uuid references public.lojas (id) on delete cascade,
  pdv_nome_livre      text,
  pdv_endereco_livre  text,
  produto             text not null check (length(btrim(produto)) between 1 and 200),
  produto_busca       text generated always as (public.normalizar(produto)) stored,
  preco_centavos      integer not null check (preco_centavos > 0 and preco_centavos < 100000000),
  validade            date,
  -- Texto livre do PDV ("cerveja gelada"). A regra de EXIBIÇÃO do campo OBS
  -- (NF / produto de encarte / Preço oficial) é lógica de apresentação, no app.
  obs                 text check (obs is null or length(obs) <= 140),
  fonte               public.fonte_cotacao not null,
  chave_acesso_nf     char(44) check (chave_acesso_nf ~ '^[0-9]{44}$'),
  enviado_por         uuid references public.usuarios (id) on delete set null,
  encarte_id          uuid references public.encartes_pendentes (id) on delete set null,
  lote_id             uuid,  -- agrupa itens enviados juntos (mesma NF, mesma planilha...)
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),

  constraint cotacoes_pdv_identificado
    check (loja_id is not null or length(btrim(coalesce(pdv_nome_livre, ''))) > 0),
  constraint cotacoes_fonte_pdv_tem_loja
    check (fonte not in ('pdv_manual', 'pdv_excel') or loja_id is not null),
  constraint cotacoes_chave_so_em_nf
    check (chave_acesso_nf is null or fonte = 'usuario_nf')
);

create index cotacoes_busca_trgm_idx on public.cotacoes
  using gin (produto_busca extensions.gin_trgm_ops);
create index cotacoes_loja_idx on public.cotacoes (loja_id);
create index cotacoes_recentes_idx on public.cotacoes (created_at desc);
create index cotacoes_chave_nf_idx on public.cotacoes (chave_acesso_nf) where chave_acesso_nf is not null;
-- Um único "preço oficial" por produto em cada loja (base do upsert do PDV).
create unique index cotacoes_preco_oficial_uidx on public.cotacoes (loja_id, produto_busca)
  where fonte in ('pdv_manual', 'pdv_excel');

create trigger cotacoes_updated_at before update on public.cotacoes
  for each row execute function public.set_updated_at();

-- -----------------------------------------------------------------------------
-- operacoes_log — controle da cota de 50 operações/mês do PDV
-- -----------------------------------------------------------------------------
create table public.operacoes_log (
  id            bigint generated always as identity primary key,
  pdv_id        uuid not null references public.pdvs (id) on delete cascade,
  loja_id       uuid references public.lojas (id) on delete set null,  -- null = modo rede
  produto       text not null,
  tipo          public.tipo_operacao not null,
  conta_na_cota boolean generated always as (tipo in ('criar_item', 'aumentar_preco')) stored,
  competencia   date not null default public.competencia_atual(),
  created_at    timestamptz not null default now()
);

create index operacoes_cota_idx on public.operacoes_log (pdv_id, competencia) where conta_na_cota;

-- -----------------------------------------------------------------------------
-- listas de compra (CPF)
-- -----------------------------------------------------------------------------
create table public.listas_compra (
  id          uuid primary key default gen_random_uuid(),
  usuario_id  uuid not null references public.usuarios (id) on delete cascade,
  nome        text not null default 'Minha lista' check (length(nome) <= 80),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index listas_usuario_idx on public.listas_compra (usuario_id);
create trigger listas_updated_at before update on public.listas_compra
  for each row execute function public.set_updated_at();

create table public.lista_itens (
  id          uuid primary key default gen_random_uuid(),
  lista_id    uuid not null references public.listas_compra (id) on delete cascade,
  produto     text not null check (length(btrim(produto)) between 1 and 200),
  quantidade  numeric(8, 3) not null default 1 check (quantidade > 0),
  marcado     boolean not null default false,
  created_at  timestamptz not null default now()
);

create index lista_itens_lista_idx on public.lista_itens (lista_id);

-- -----------------------------------------------------------------------------
-- promocoes (banner pago do PDV)
-- -----------------------------------------------------------------------------
create table public.promocoes (
  id                         uuid primary key default gen_random_uuid(),
  pdv_id                     uuid not null references public.pdvs (id) on delete cascade,
  loja_id                    uuid references public.lojas (id) on delete set null, -- centro do raio
  titulo                     text not null check (length(btrim(titulo)) between 1 and 80),
  descricao                  text check (descricao is null or length(descricao) <= 500),
  arte_path                  text,                  -- caminho no bucket "promocoes"
  origem_arte                public.origem_arte not null,
  raio_km                    numeric(5, 1) check (raio_km is null or (raio_km > 0 and raio_km <= 100)),
  palavras_chave             text[] not null default '{}',
  visualizacoes_contratadas  integer not null default 0 check (visualizacoes_contratadas >= 0),
  visualizacoes_exibidas     integer not null default 0 check (visualizacoes_exibidas >= 0),
  status                     public.status_promocao not null default 'aguardando_pagamento',
  created_at                 timestamptz not null default now(),
  updated_at                 timestamptz not null default now(),
  constraint promocao_exibidas_ate_contratadas
    check (visualizacoes_exibidas <= visualizacoes_contratadas)
);

create index promocoes_pdv_idx on public.promocoes (pdv_id);
create index promocoes_ativas_idx on public.promocoes (status) where status = 'ativa';
create trigger promocoes_updated_at before update on public.promocoes
  for each row execute function public.set_updated_at();

-- -----------------------------------------------------------------------------
-- pagamentos (Pix via Mercado Pago)
-- -----------------------------------------------------------------------------
create table public.pagamentos (
  id                     uuid primary key default gen_random_uuid(),
  usuario_id             uuid not null references public.usuarios (id) on delete cascade,
  pdv_id                 uuid not null references public.pdvs (id) on delete cascade,
  tipo                   public.tipo_pagamento not null,
  promocao_id            uuid references public.promocoes (id) on delete set null,
  quantidade             integer not null check (quantidade > 0),   -- operações ou visualizações
  valor_centavos         integer not null check (valor_centavos > 0),
  competencia            date,                                        -- mês em que o pacote vale
  status                 public.status_pagamento not null default 'pendente',
  provedor               text not null default 'mercado_pago',
  provedor_pagamento_id  text unique,
  pix_copia_e_cola       text,
  pago_em                timestamptz,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),

  constraint pagamento_promocao_obrigatoria
    check (tipo <> 'pacote_visualizacoes' or promocao_id is not null),
  constraint pagamento_competencia_obrigatoria
    check (tipo <> 'pacote_operacoes' or competencia is not null),
  -- Tabela de preços do briefing: +50 operações = R$10; 100/250/500 visualizações = R$10/25/50.
  constraint pagamento_pacote_valido check (
    (tipo = 'pacote_operacoes' and quantidade = 50 and valor_centavos = 1000)
    or (tipo = 'pacote_visualizacoes' and (quantidade, valor_centavos) in ((100, 1000), (250, 2500), (500, 5000)))
  )
);

create index pagamentos_pdv_idx on public.pagamentos (pdv_id, tipo, status);
create trigger pagamentos_updated_at before update on public.pagamentos
  for each row execute function public.set_updated_at();
