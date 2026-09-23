-- =============================================================================
-- Tabelapp — instalação completa do banco (todas as migrações, em ordem).
-- Cole TUDO no SQL Editor do Supabase (projeto novo, vazio) e clique em Run.
-- Arquivo gerado por scripts/juntar-migracoes.sh — não edite à mão.
-- =============================================================================

-- >>>>>>>>>> 20260922000100_schema_inicial.sql
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
  -- Validade das promoções impressa no encarte (o usuário informa; o Admin confirma ao aprovar).
  validade         date,
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
  -- Todo preço tem validade: PDV até 30 dias, NF 1 dia, encarte a data impressa nele.
  validade            date not null,
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
-- operacoes_log — controle da cota de 50 operações grátis/mês POR LOJA
-- (em modo rede, a rede inteira é uma cota só: loja_id = null)
-- -----------------------------------------------------------------------------
create table public.operacoes_log (
  id            bigint generated always as identity primary key,
  pdv_id        uuid not null references public.pdvs (id) on delete cascade,
  loja_id       uuid references public.lojas (id) on delete cascade,  -- null = modo rede
  produto       text not null,
  tipo          public.tipo_operacao not null,
  conta_na_cota boolean generated always as (tipo in ('criar_item', 'aumentar_preco')) stored,
  -- De onde saiu a operação: null = das 50 grátis do mês; senão, do pacote pago.
  pagamento_id  uuid,
  competencia   date not null default public.competencia_atual(),
  created_at    timestamptz not null default now()
);

create index operacoes_cota_idx on public.operacoes_log (pdv_id, loja_id, competencia) where conta_na_cota;
create index operacoes_pacote_idx on public.operacoes_log (pagamento_id) where pagamento_id is not null;

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
  -- Pacote de operações é de uma loja (modo varejo) ou da rede toda (null, modo rede).
  loja_id                uuid references public.lojas (id) on delete cascade,
  quantidade             integer not null check (quantidade > 0),   -- operações ou visualizações
  valor_centavos         integer not null check (valor_centavos > 0),
  valido_ate             timestamptz,                                 -- pacote de operações: pago_em + 30 dias
  status                 public.status_pagamento not null default 'pendente',
  provedor               text not null default 'mercado_pago',
  provedor_pagamento_id  text unique,
  pix_copia_e_cola       text,
  pago_em                timestamptz,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),

  constraint pagamento_promocao_obrigatoria
    check (tipo <> 'pacote_visualizacoes' or promocao_id is not null),
  constraint pagamento_loja_so_em_operacoes
    check (tipo = 'pacote_operacoes' or loja_id is null),
  -- Tabela de preços do briefing: +50 operações = R$10; 100/250/500 visualizações = R$10/25/50.
  constraint pagamento_pacote_valido check (
    (tipo = 'pacote_operacoes' and quantidade = 50 and valor_centavos = 1000)
    or (tipo = 'pacote_visualizacoes' and (quantidade, valor_centavos) in ((100, 1000), (250, 2500), (500, 5000)))
  )
);

create index pagamentos_pdv_idx on public.pagamentos (pdv_id, tipo, status);

alter table public.operacoes_log
  add constraint operacoes_pagamento_fk foreign key (pagamento_id)
  references public.pagamentos (id) on delete set null;
create trigger pagamentos_updated_at before update on public.pagamentos
  for each row execute function public.set_updated_at();

-- >>>>>>>>>> 20260922000200_funcoes.sql
-- =============================================================================
-- Tabelapp — regras de negócio no banco (funções RPC chamadas pelo app)
--
-- Erros de negócio são levantados com mensagens estáveis, em snake_case
-- (ex.: 'cota_excedida'), que o app traduz para texto amigável.
-- =============================================================================

-- Constantes do produto -------------------------------------------------------
create function public.cota_gratis_mensal() returns integer
language sql immutable as $$ select 50 $$;

create function public.operacoes_por_pacote() returns integer
language sql immutable as $$ select 50 $$;

create function public.validade_maxima_dias() returns integer
language sql immutable as $$ select 30 $$;

-- Pacote de +50 operações vale 30 dias a partir do pagamento.
create function public.validade_pacote_dias() returns integer
language sql immutable as $$ select 30 $$;

-- Preço vindo de Nota Fiscal vale 1 dia (data do envio + 1).
create function public.validade_nf_dias() returns integer
language sql immutable as $$ select 1 $$;

-- Helpers de permissão ---------------------------------------------------------
create function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.usuarios where id = auth.uid() and tipo = 'admin'
  )
$$;

create function public.eh_dono_pdv(p_pdv_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.pdvs where id = p_pdv_id and dono_id = auth.uid()
  )
$$;

-- =============================================================================
-- Cota de operações
--
-- Cada LOJA tem 50 operações grátis por mês (modo varejo). Em modo rede, a
-- rede inteira funciona como uma loja só: uma cota de 50, e cada alteração
-- replicada conta uma vez. Essa "carteira" é identificada por (pdv, loja_id),
-- com loja_id = null no modo rede.
--
-- Pacotes pagos (+50 por R$10) valem 30 dias a partir do pagamento e são da
-- mesma carteira (loja ou rede). O consumo usa primeiro as grátis do mês,
-- depois o pacote que vence antes.
-- =============================================================================

-- Resolve a carteira: modo rede -> null; varejo -> a loja (precisa ser do PDV e ativa).
create function public._carteira_cota(p_pdv public.pdvs, p_loja_id uuid)
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if p_pdv.modo_rede then
    return null;
  end if;
  if p_loja_id is null or not exists (
    select 1 from public.lojas where id = p_loja_id and pdv_id = p_pdv.id and ativa
  ) then
    raise exception 'loja_invalida';
  end if;
  return p_loja_id;
end;
$$;

-- Operações grátis já usadas no mês pela carteira.
create function public._gratis_usadas(p_pdv_id uuid, p_carteira uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select count(*)::integer from public.operacoes_log o
  where o.pdv_id = p_pdv_id and o.loja_id is not distinct from p_carteira
    and o.conta_na_cota and o.pagamento_id is null
    and o.competencia = public.competencia_atual()
$$;

-- Pacotes pagos ainda válidos da carteira, com o saldo de cada um.
create function public._pacotes_ativos(p_pdv_id uuid, p_carteira uuid)
returns table (pagamento_id uuid, valido_ate timestamptz, saldo integer)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.valido_ate,
         p.quantidade - (select count(*)::integer from public.operacoes_log o where o.pagamento_id = p.id)
  from public.pagamentos p
  where p.pdv_id = p_pdv_id and p.loja_id is not distinct from p_carteira
    and p.tipo = 'pacote_operacoes' and p.status = 'pago' and p.valido_ate > now()
    and p.quantidade > (select count(*) from public.operacoes_log o where o.pagamento_id = p.id)
  order by p.valido_ate, p.id
$$;

-- p_loja_id é ignorado em modo rede e obrigatório em modo varejo.
create function public.cota_status(p_pdv_id uuid, p_loja_id uuid default null)
returns table (
  loja_id           uuid,
  gratis            integer,
  gratis_usadas     integer,
  saldo_pacotes     integer,
  pacote_vence_em   timestamptz,
  restantes         integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_pdv       public.pdvs%rowtype;
  v_carteira  uuid;
  v_usadas    integer;
begin
  select * into v_pdv from public.pdvs where id = p_pdv_id;
  if not found or not (v_pdv.dono_id = auth.uid() or public.is_admin()) then
    raise exception 'sem_permissao';
  end if;
  v_carteira := public._carteira_cota(v_pdv, p_loja_id);
  v_usadas := public._gratis_usadas(p_pdv_id, v_carteira);

  return query
  select v_carteira,
         public.cota_gratis_mensal(),
         v_usadas,
         coalesce(sum(a.saldo), 0)::integer,
         min(a.valido_ate),
         greatest(public.cota_gratis_mensal() - v_usadas, 0) + coalesce(sum(a.saldo), 0)::integer
  from public._pacotes_ativos(p_pdv_id, v_carteira) a;
end;
$$;

-- =============================================================================
-- PDV: salvar/importar preços
--
-- Mesma rotina para edição manual (1 item) e importação de planilha (N itens).
-- O produto (normalizado) é a chave dentro da loja: se já existe, atualiza.
--
-- Contagem de operações (regra do briefing):
--   * produto novo            -> criar_item      (conta 1)
--   * preço maior que o atual -> aumentar_preco  (conta 1)
--   * preço menor             -> diminuir_preco  (grátis)
--   * mesmo preço (só OBS/validade mudou) -> editar_dados (grátis)
--   * excluir                 -> excluir_item    (grátis, ver pdv_excluir_item)
-- Modo rede: o item é replicado para todas as lojas ativas e conta UMA vez.
-- Modo varejo: aplica só na loja informada e usa a cota daquela loja.
--
-- p_simular = true não grava nada: devolve o resumo e se cabe na cota — usado
-- pelo app para mostrar a tela de pagamento ANTES de concluir a importação.
-- =============================================================================
create function public.pdv_salvar_precos(
  p_pdv_id   uuid,
  p_loja_id  uuid,
  p_itens    jsonb,
  p_fonte    public.fonte_cotacao default 'pdv_manual',
  p_simular  boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv          public.pdvs%rowtype;
  v_lojas        uuid[];
  v_hoje         date := public.hoje();
  v_restantes    integer;
  v_resumo       record;
  v_faltam       integer;
  v_lote         uuid := gen_random_uuid();
  v_invalidos    jsonb;
  v_carteira     uuid;
  v_gratis       integer;
  v_pacote       uuid;
  v_op           record;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if p_fonte not in ('pdv_manual', 'pdv_excel') then
    raise exception 'fonte_invalida';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 2000 then
    raise exception 'itens_demais';
  end if;

  -- Trava o PDV: evita duas importações simultâneas furarem a cota.
  select * into v_pdv from public.pdvs where id = p_pdv_id for update;
  if not found or v_pdv.dono_id <> auth.uid() then
    raise exception 'sem_permissao';
  end if;

  if v_pdv.modo_rede then
    select array_agg(id order by created_at) into v_lojas
    from public.lojas where pdv_id = p_pdv_id and ativa;
  else
    select array_agg(id) into v_lojas
    from public.lojas where id = p_loja_id and pdv_id = p_pdv_id and ativa;
  end if;
  if v_lojas is null then
    raise exception 'loja_invalida';
  end if;
  v_carteira := public._carteira_cota(v_pdv, p_loja_id);

  -- Itens de entrada, já normalizados.
  -- Tabelas temporárias de trabalho (somem no fim da transação).
  if to_regclass('pg_temp._itens') is null then
    create temp table _itens (
      ord bigint, produto text, norm text, preco integer, validade date, obs text
    ) on commit drop;
    create temp table _classif (norm text, tipo public.tipo_operacao) on commit drop;
  else
    truncate _itens, _classif;
  end if;

  insert into _itens
  select e.ord,
         btrim(e.item ->> 'produto'),
         public.normalizar(e.item ->> 'produto'),
         (e.item ->> 'preco_centavos')::integer,
         coalesce((e.item ->> 'validade')::date, v_hoje + public.validade_maxima_dias()),
         nullif(btrim(e.item ->> 'obs'), '')
  from jsonb_array_elements(p_itens) with ordinality as e(item, ord);

  -- Validação: devolve TODAS as linhas com problema de uma vez (útil p/ planilha).
  select jsonb_agg(jsonb_build_object('linha', ord, 'erro', erro) order by ord) into v_invalidos
  from (
    select ord,
      case
        when produto is null or norm = '' then 'produto_vazio'
        when length(produto) > 200 then 'produto_longo'
        when preco is null or preco <= 0 then 'preco_invalido'
        when preco >= 100000000 then 'preco_invalido'
        when validade < v_hoje then 'validade_passada'
        when validade > v_hoje + public.validade_maxima_dias() then 'validade_maior_que_30_dias'
        when length(obs) > 140 then 'obs_longa'
        when count(*) over (partition by norm) > 1 then 'produto_duplicado'
      end as erro
    from _itens
  ) v
  where erro is not null;

  if v_invalidos is not null then
    raise exception 'itens_invalidos' using detail = v_invalidos::text;
  end if;

  -- Classifica cada item contra o preço atual (maior preço entre as lojas-alvo).
  insert into _classif
  select i.norm,
         case
           when atual.preco is null then 'criar_item'
           when i.preco > atual.preco then 'aumentar_preco'
           when i.preco < atual.preco then 'diminuir_preco'
           else 'editar_dados'
         end::public.tipo_operacao
  from _itens i
  left join lateral (
    select max(c.preco_centavos) as preco
    from public.cotacoes c
    where c.loja_id = any (v_lojas)
      and c.produto_busca = i.norm
      and c.fonte in ('pdv_manual', 'pdv_excel')
  ) atual on true;

  select count(*) filter (where tipo = 'criar_item')                      as criados,
         count(*) filter (where tipo = 'aumentar_preco')                  as aumentados,
         count(*) filter (where tipo = 'diminuir_preco')                  as diminuidos,
         count(*) filter (where tipo = 'editar_dados')                    as inalterados,
         count(*) filter (where tipo in ('criar_item', 'aumentar_preco')) as operacoes
  into v_resumo
  from _classif;

  select c.restantes into v_restantes from public.cota_status(p_pdv_id, p_loja_id) c;
  v_faltam := greatest(v_resumo.operacoes - v_restantes, 0);

  if p_simular or v_faltam > 0 then
    if not p_simular then
      raise exception 'cota_excedida'
        using detail = jsonb_build_object(
          'operacoes', v_resumo.operacoes,
          'restantes', v_restantes,
          'pacotes_necessarios', ceil(v_faltam::numeric / public.operacoes_por_pacote())
        )::text;
    end if;
    return jsonb_build_object(
      'simulacao', true,
      'criados', v_resumo.criados,
      'aumentados', v_resumo.aumentados,
      'diminuidos', v_resumo.diminuidos,
      'inalterados', v_resumo.inalterados,
      'operacoes', v_resumo.operacoes,
      'restantes', v_restantes,
      'cabe_na_cota', v_faltam = 0,
      'pacotes_necessarios', ceil(v_faltam::numeric / public.operacoes_por_pacote())
    );
  end if;

  -- Grava: um registro por (item x loja-alvo).
  insert into public.cotacoes as c
    (loja_id, produto, preco_centavos, validade, obs, fonte, enviado_por, lote_id)
  select l.loja_id, i.produto, i.preco, i.validade, i.obs, p_fonte, auth.uid(), v_lote
  from _itens i
  cross join unnest(v_lojas) as l(loja_id)
  on conflict (loja_id, produto_busca) where fonte in ('pdv_manual', 'pdv_excel')
  do update set
    produto        = excluded.produto,
    preco_centavos = excluded.preco_centavos,
    validade       = excluded.validade,
    obs            = excluded.obs,
    fonte          = excluded.fonte,
    enviado_por    = excluded.enviado_por,
    lote_id        = excluded.lote_id;

  -- Registra as operações, tirando primeiro das grátis do mês e depois do pacote
  -- que vence antes. O saldo já foi conferido acima, então sempre há de onde tirar.
  v_gratis := greatest(public.cota_gratis_mensal() - public._gratis_usadas(p_pdv_id, v_carteira), 0);
  for v_op in
    select i.produto, c.tipo, c.tipo in ('criar_item', 'aumentar_preco') as conta
    from _itens i join _classif c using (norm)
    order by i.ord
  loop
    v_pacote := null;
    if v_op.conta then
      if v_gratis > 0 then
        v_gratis := v_gratis - 1;
      else
        select a.pagamento_id into v_pacote from public._pacotes_ativos(p_pdv_id, v_carteira) a limit 1;
      end if;
    end if;
    insert into public.operacoes_log (pdv_id, loja_id, produto, tipo, pagamento_id)
    values (p_pdv_id, v_carteira, v_op.produto, v_op.tipo, v_pacote);
  end loop;

  return jsonb_build_object(
    'simulacao', false,
    'lote_id', v_lote,
    'criados', v_resumo.criados,
    'aumentados', v_resumo.aumentados,
    'diminuidos', v_resumo.diminuidos,
    'inalterados', v_resumo.inalterados,
    'operacoes', v_resumo.operacoes,
    'restantes', v_restantes - v_resumo.operacoes
  );
end;
$$;

-- Excluir é sempre grátis. Em modo rede, remove o produto de todas as lojas.
create function public.pdv_excluir_item(p_cotacao_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cot   public.cotacoes%rowtype;
  v_pdv   public.pdvs%rowtype;
  v_n     integer;
begin
  select c.* into v_cot from public.cotacoes c where c.id = p_cotacao_id;
  if not found or v_cot.fonte not in ('pdv_manual', 'pdv_excel') then
    raise exception 'item_nao_encontrado';
  end if;

  select p.* into v_pdv
  from public.pdvs p join public.lojas l on l.pdv_id = p.id
  where l.id = v_cot.loja_id;
  if v_pdv.dono_id is distinct from auth.uid() then
    raise exception 'sem_permissao';
  end if;

  delete from public.cotacoes c
  where c.fonte in ('pdv_manual', 'pdv_excel')
    and c.produto_busca = v_cot.produto_busca
    and (
      (v_pdv.modo_rede and c.loja_id in (select id from public.lojas where pdv_id = v_pdv.id))
      or (not v_pdv.modo_rede and c.loja_id = v_cot.loja_id)
    );
  get diagnostics v_n = row_count;

  insert into public.operacoes_log (pdv_id, loja_id, produto, tipo)
  values (v_pdv.id, case when v_pdv.modo_rede then null else v_cot.loja_id end,
          v_cot.produto, 'excluir_item');

  return v_n;
end;
$$;

-- =============================================================================
-- Busca de preços (tela principal do CPF)
--
-- Sem termo: últimos preços lançados (a tela nunca fica vazia).
-- Com termo: todas as palavras precisam aparecer no nome do produto,
-- sem diferenciar acento/maiúscula ("feijao preto" acha "Feijão Preto 1kg").
-- Ordenação e destaque do mais barato são feitos no app.
-- =============================================================================
create function public.buscar_cotacoes(
  p_termo   text default null,
  p_lat     double precision default null,
  p_lng     double precision default null,
  p_limite  integer default 100
)
returns table (
  id              uuid,
  produto         text,
  preco_centavos  integer,
  validade        date,
  obs             text,
  fonte           public.fonte_cotacao,
  loja_id         uuid,
  pdv_id          uuid,
  pdv_nome        text,
  loja_nome       text,
  endereco        text,
  telefone        text,
  site            text,
  latitude        double precision,
  longitude       double precision,
  distancia_km    double precision,
  created_at      timestamptz
)
language sql
stable
security invoker
set search_path = ''
as $$
  with termo as (
    select nullif(public.normalizar(p_termo), '') as t
  ),
  palavras as (
    select array_remove(string_to_array(t, ' '), '') as ps from termo
  )
  select c.id,
         c.produto,
         c.preco_centavos,
         c.validade,
         c.obs,
         c.fonte,
         c.loja_id,
         p.id,
         coalesce(p.nome_fantasia, c.pdv_nome_livre),
         l.nome,
         coalesce(
           concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf),
           c.pdv_endereco_livre
         ),
         l.telefone,
         p.site,
         l.latitude,
         l.longitude,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude),
         c.created_at
  from public.cotacoes c
  left join public.lojas l on l.id = c.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  cross join termo
  cross join palavras
  where (l.id is null or l.ativa)
    and c.validade >= public.hoje()
    and (
      termo.t is null
      or not exists (
        select 1 from unnest(palavras.ps) w
        where strpos(c.produto_busca, w) = 0
      )
    )
  order by
    case when termo.t is null then c.created_at end desc,
    c.preco_centavos asc
  limit least(greatest(coalesce(p_limite, 100), 1), 500)
$$;

-- =============================================================================
-- Usuário comum: Nota Fiscal (manual no V1)
-- Uma NF -> vários produtos, enviados de uma vez após a tela de confirmação.
-- Preço de NF vale 1 dia (data do envio + 1).
-- CPF do comprador NUNCA é pedido nem armazenado.
-- =============================================================================
create function public.enviar_nota_fiscal(
  p_chave_acesso  text,
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_itens         jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_chave  text := nullif(regexp_replace(coalesce(p_chave_acesso, ''), '\D', '', 'g'), '');
  v_lote   uuid := gen_random_uuid();
  v_n      integer;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if v_chave is not null and length(v_chave) <> 44 then
    raise exception 'chave_acesso_invalida';
  end if;
  if v_chave is not null and exists (select 1 from public.cotacoes where chave_acesso_nf = v_chave) then
    raise exception 'nf_ja_enviada';
  end if;
  if p_loja_id is null and length(btrim(coalesce(p_pdv_nome, ''))) = 0 then
    raise exception 'pdv_obrigatorio';
  end if;
  if p_loja_id is not null and not exists (select 1 from public.lojas where id = p_loja_id and ativa) then
    raise exception 'loja_invalida';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 500 then
    raise exception 'itens_demais';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where length(btrim(coalesce(e ->> 'produto', ''))) = 0
       or coalesce((e ->> 'preco_centavos')::integer, 0) <= 0
  ) then
    raise exception 'itens_invalidos';
  end if;

  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade,
     fonte, chave_acesso_nf, enviado_por, lote_id)
  select p_loja_id,
         case when p_loja_id is null then btrim(p_pdv_nome) end,
         case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         public.hoje() + public.validade_nf_dias(),
         'usuario_nf',
         v_chave,
         auth.uid(),
         v_lote
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  return jsonb_build_object('lote_id', v_lote, 'itens', v_n);
end;
$$;

-- =============================================================================
-- Admin: fila de encartes de usuários
-- =============================================================================
create function public.aprovar_encarte(
  p_encarte_id  uuid,
  p_itens       jsonb,          -- [{produto, preco_centavos, validade?}] montado pelo Admin
  p_validade    date default null  -- validade impressa no encarte (vale para itens sem validade própria)
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_enc  public.encartes_pendentes%rowtype;
  v_n    integer;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;

  select * into v_enc from public.encartes_pendentes where id = p_encarte_id for update;
  if not found then
    raise exception 'encarte_nao_encontrado';
  end if;
  if v_enc.status <> 'pendente' then
    raise exception 'encarte_ja_revisado';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  -- Cada preço usa a validade informada no encarte: a do item, a informada pelo
  -- Admin na aprovação ou a que o usuário digitou ao enviar, nessa ordem.
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where coalesce((e ->> 'validade')::date, p_validade, v_enc.validade) is null
  ) then
    raise exception 'validade_obrigatoria';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where coalesce((e ->> 'validade')::date, p_validade, v_enc.validade) < public.hoje()
  ) then
    raise exception 'validade_passada';
  end if;

  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade,
     fonte, enviado_por, encarte_id, lote_id)
  select v_enc.loja_id,
         case when v_enc.loja_id is null then v_enc.pdv_nome end,
         case when v_enc.loja_id is null then v_enc.pdv_endereco end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         coalesce((e ->> 'validade')::date, p_validade, v_enc.validade),
         'usuario_encarte',
         v_enc.enviado_por,
         v_enc.id,
         v_enc.id
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  update public.encartes_pendentes
     set status = 'aprovado', revisado_por = auth.uid(), revisado_em = now()
   where id = p_encarte_id;

  return v_n;
end;
$$;

create function public.rejeitar_encarte(p_encarte_id uuid, p_motivo text default null)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;

  update public.encartes_pendentes
     set status = 'rejeitado', revisado_por = auth.uid(), revisado_em = now(),
         motivo_rejeicao = nullif(btrim(p_motivo), '')
   where id = p_encarte_id and status = 'pendente';

  if not found then
    raise exception 'encarte_nao_encontrado';
  end if;
end;
$$;

-- =============================================================================
-- Promoções: banners exibidos na busca
-- Segmentação do MVP: raio (a partir da loja) e/ou palavra-chave do termo buscado.
-- =============================================================================
create function public.promocoes_para_busca(
  p_termo  text default null,
  p_lat    double precision default null,
  p_lng    double precision default null,
  p_limite integer default 3
)
returns table (
  id          uuid,
  pdv_id      uuid,
  pdv_nome    text,
  titulo      text,
  descricao   text,
  arte_path   text,
  distancia_km double precision
)
language sql
stable
security invoker
set search_path = ''
as $$
  select pr.id, pr.pdv_id, p.nome_fantasia, pr.titulo, pr.descricao, pr.arte_path,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude)
  from public.promocoes pr
  join public.pdvs p on p.id = pr.pdv_id
  left join public.lojas l on l.id = pr.loja_id
  where pr.status = 'ativa'
    and pr.visualizacoes_exibidas < pr.visualizacoes_contratadas
    and (
      pr.raio_km is null
      or public.distancia_km(p_lat, p_lng, l.latitude, l.longitude) <= pr.raio_km
    )
    and (
      cardinality(pr.palavras_chave) = 0
      or exists (
        select 1 from unnest(pr.palavras_chave) k
        where strpos(public.normalizar(p_termo), public.normalizar(k)) > 0
      )
    )
  order by random()
  limit least(greatest(coalesce(p_limite, 3), 1), 10)
$$;

create function public.registrar_visualizacao_promocao(p_promocao_id uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.promocoes
     set visualizacoes_exibidas = visualizacoes_exibidas + 1,
         status = case when visualizacoes_exibidas + 1 >= visualizacoes_contratadas
                       then 'esgotada'::public.status_promocao else status end
   where id = p_promocao_id
     and status = 'ativa'
     and visualizacoes_exibidas < visualizacoes_contratadas
$$;

-- =============================================================================
-- Pagamentos: confirmação chamada pelo webhook do Mercado Pago (Edge Function,
-- com service_role). Nunca exposta ao app.
-- =============================================================================
create function public.confirmar_pagamento(p_pagamento_id uuid, p_provedor_pagamento_id text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pag public.pagamentos%rowtype;
begin
  update public.pagamentos
     set status = 'pago', pago_em = now(),
         valido_ate = case when tipo = 'pacote_operacoes'
                           then now() + make_interval(days => public.validade_pacote_dias()) end,
         provedor_pagamento_id = coalesce(p_provedor_pagamento_id, provedor_pagamento_id)
   where id = p_pagamento_id and status = 'pendente'
  returning * into v_pag;

  if not found then
    return;  -- idempotente: webhook pode chegar mais de uma vez
  end if;

  if v_pag.tipo = 'pacote_visualizacoes' then
    update public.promocoes
       set visualizacoes_contratadas = visualizacoes_contratadas + v_pag.quantidade,
           status = case when status in ('aguardando_pagamento', 'esgotada')
                         then 'ativa'::public.status_promocao else status end
     where id = v_pag.promocao_id;
  end if;
  -- pacote_operacoes: nada a fazer, cota_status() já soma os pacotes válidos.
end;
$$;

-- >>>>>>>>>> 20260922000300_seguranca_rls.sql
-- =============================================================================
-- Tabelapp — permissões (Row Level Security)
--
-- Princípios:
--   * Nada é acessível sem login (role "anon" não lê nem escreve nada).
--   * Preços (cotacoes) são públicos para quem está logado, mas só são
--     gravados pelas funções RPC (que aplicam cota, validade, antifraude).
--   * Cada um só vê o que é seu (listas, pagamentos, encartes enviados).
--   * O Admin vê a fila de encartes e pode moderar preços.
-- =============================================================================

alter table public.usuarios            enable row level security;
alter table public.pdvs                enable row level security;
alter table public.lojas               enable row level security;
alter table public.encartes_pendentes  enable row level security;
alter table public.cotacoes            enable row level security;
alter table public.operacoes_log       enable row level security;
alter table public.listas_compra       enable row level security;
alter table public.lista_itens         enable row level security;
alter table public.promocoes           enable row level security;
alter table public.pagamentos          enable row level security;

-- Sem acesso anônimo a nada.
revoke all on all tables in schema public from anon;
revoke all on all sequences in schema public from anon;

-- usuarios --------------------------------------------------------------------
create policy usuarios_select on public.usuarios for select to authenticated
  using (id = auth.uid() or public.is_admin());

-- Pode trocar entre cpf/cnpj e editar nome/telefone; nunca se promover a admin.
create policy usuarios_update on public.usuarios for update to authenticated
  using (id = auth.uid())
  with check (id = auth.uid() and (tipo <> 'admin' or public.is_admin()));

revoke insert, delete on public.usuarios from authenticated;  -- criado pelo trigger do Auth
revoke update on public.usuarios from authenticated;
grant update (tipo, nome, telefone, cadastro_completo) on public.usuarios to authenticated;

-- pdvs (dados públicos da empresa) --------------------------------------------
create policy pdvs_select on public.pdvs for select to authenticated using (true);
create policy pdvs_insert on public.pdvs for insert to authenticated
  with check (dono_id = auth.uid());
create policy pdvs_update on public.pdvs for update to authenticated
  using (dono_id = auth.uid()) with check (dono_id = auth.uid());
create policy pdvs_delete on public.pdvs for delete to authenticated
  using (dono_id = auth.uid());

-- lojas -----------------------------------------------------------------------
create policy lojas_select on public.lojas for select to authenticated using (true);
create policy lojas_insert on public.lojas for insert to authenticated
  with check (public.eh_dono_pdv(pdv_id));
create policy lojas_update on public.lojas for update to authenticated
  using (public.eh_dono_pdv(pdv_id)) with check (public.eh_dono_pdv(pdv_id));
create policy lojas_delete on public.lojas for delete to authenticated
  using (public.eh_dono_pdv(pdv_id));

-- cotacoes --------------------------------------------------------------------
create policy cotacoes_select on public.cotacoes for select to authenticated using (true);
-- Moderação: o Admin pode remover qualquer preço.
create policy cotacoes_delete_admin on public.cotacoes for delete to authenticated
  using (public.is_admin());
revoke insert, update on public.cotacoes from authenticated;

-- operacoes_log ---------------------------------------------------------------
create policy operacoes_select on public.operacoes_log for select to authenticated
  using (public.eh_dono_pdv(pdv_id) or public.is_admin());
revoke insert, update, delete on public.operacoes_log from authenticated;

-- encartes_pendentes ----------------------------------------------------------
create policy encartes_select on public.encartes_pendentes for select to authenticated
  using (enviado_por = auth.uid() or public.is_admin());
create policy encartes_insert on public.encartes_pendentes for insert to authenticated
  with check (
    enviado_por = auth.uid()
    and status = 'pendente'
    and revisado_por is null and revisado_em is null and motivo_rejeicao is null
  );
revoke update, delete on public.encartes_pendentes from authenticated;  -- via aprovar/rejeitar

-- listas de compra ------------------------------------------------------------
create policy listas_dono on public.listas_compra for all to authenticated
  using (usuario_id = auth.uid()) with check (usuario_id = auth.uid());

create policy lista_itens_dono on public.lista_itens for all to authenticated
  using (exists (select 1 from public.listas_compra l
                 where l.id = lista_id and l.usuario_id = auth.uid()))
  with check (exists (select 1 from public.listas_compra l
                      where l.id = lista_id and l.usuario_id = auth.uid()));

-- promocoes -------------------------------------------------------------------
create policy promocoes_select on public.promocoes for select to authenticated
  using (status = 'ativa' or public.eh_dono_pdv(pdv_id) or public.is_admin());
create policy promocoes_insert on public.promocoes for insert to authenticated
  with check (
    public.eh_dono_pdv(pdv_id)
    and status = 'aguardando_pagamento'
    and visualizacoes_contratadas = 0
    and visualizacoes_exibidas = 0
  );
create policy promocoes_update on public.promocoes for update to authenticated
  using (public.eh_dono_pdv(pdv_id)) with check (public.eh_dono_pdv(pdv_id));
create policy promocoes_delete on public.promocoes for delete to authenticated
  using (public.eh_dono_pdv(pdv_id));

-- Contadores e status só mudam por pagamento/visualização (funções).
revoke update on public.promocoes from authenticated;
grant update (loja_id, titulo, descricao, arte_path, origem_arte, raio_km, palavras_chave)
  on public.promocoes to authenticated;

-- pagamentos (gravados só pela Edge Function do Mercado Pago, com service_role)
create policy pagamentos_select on public.pagamentos for select to authenticated
  using (usuario_id = auth.uid() or public.is_admin());
revoke insert, update, delete on public.pagamentos from authenticated;

-- =============================================================================
-- Funções: por padrão o Postgres deixa qualquer um executar. Fechamos tudo e
-- liberamos só o que o app chama.
-- =============================================================================
revoke execute on all functions in schema public from public, anon, authenticated;
grant execute on all functions in schema public to service_role;

grant execute on function
  public.normalizar(text),
  public.distancia_km(double precision, double precision, double precision, double precision),
  public.competencia_atual(),
  public.hoje(),
  public.cota_gratis_mensal(),
  public.operacoes_por_pacote(),
  public.validade_maxima_dias(),
  public.validade_pacote_dias(),
  public.validade_nf_dias(),
  public.is_admin(),
  public.eh_dono_pdv(uuid),
  public.cota_status(uuid, uuid),
  public.pdv_salvar_precos(uuid, uuid, jsonb, public.fonte_cotacao, boolean),
  public.pdv_excluir_item(uuid),
  public.buscar_cotacoes(text, double precision, double precision, integer),
  public.enviar_nota_fiscal(text, uuid, text, text, jsonb),
  public.aprovar_encarte(uuid, jsonb, date),
  public.rejeitar_encarte(uuid, text),
  public.promocoes_para_busca(text, double precision, double precision, integer),
  public.registrar_visualizacao_promocao(uuid)
to authenticated;

-- confirmar_pagamento fica só com service_role (webhook do Mercado Pago).

-- >>>>>>>>>> 20260922000400_storage.sql
-- =============================================================================
-- Tabelapp — arquivos (Supabase Storage)
--
--   encartes/<usuario_id>/<arquivo>  foto do encarte enviada por usuário comum.
--                                    Privado: só quem enviou e o Admin veem.
--   promocoes/<pdv_id>/<arquivo>     arte do banner (própria, IA ou encarte do PDV).
--                                    Público para leitura; só o dono do PDV grava.
--
-- Protegido por "if exists" para o schema rodar também num Postgres puro
-- (testes locais em scripts/testar-banco.sh), onde não há schema storage.
-- =============================================================================
do $$
begin
  if not exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    raise notice 'schema storage ausente — pulando buckets (ambiente de teste local)';
    return;
  end if;

  insert into storage.buckets (id, name, public)
  values ('encartes', 'encartes', false), ('promocoes', 'promocoes', true)
  on conflict (id) do nothing;

  execute $p$
    create policy encartes_upload on storage.objects for insert to authenticated
      with check (bucket_id = 'encartes' and (storage.foldername(name))[1] = auth.uid()::text)
  $p$;
  execute $p$
    create policy encartes_leitura on storage.objects for select to authenticated
      using (bucket_id = 'encartes'
             and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()))
  $p$;

  execute $p$
    create policy promocoes_escrita on storage.objects for all to authenticated
      using (bucket_id = 'promocoes'
             and public.eh_dono_pdv(case when (storage.foldername(name))[1] ~ '^[0-9a-f-]{36}$'
                                         then ((storage.foldername(name))[1])::uuid end))
      with check (bucket_id = 'promocoes'
             and public.eh_dono_pdv(case when (storage.foldername(name))[1] ~ '^[0-9a-f-]{36}$'
                                         then ((storage.foldername(name))[1])::uuid end))
  $p$;
end;
$$;

-- >>>>>>>>>> 20260923000500_nf_qrcode.sql
-- =============================================================================
-- Envio de NF pelo QR Code
--
-- A chave de acesso traz o CNPJ do emitente (posições 7 a 20). Com ele o app
-- descobre se a nota é de um PDV cadastrado e liga os preços à loja certa
-- (aparece em negrito/clicável na busca, com endereço e telefone da loja).
-- =============================================================================

-- Lojas ativas do PDV com esse CNPJ (0, 1 ou várias — rede com várias lojas).
create function public.lojas_do_cnpj(p_cnpj text)
returns table (
  loja_id    uuid,
  pdv_nome   text,
  loja_nome  text,
  endereco   text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, p.nome_fantasia, l.nome,
         concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
  from public.pdvs p
  join public.lojas l on l.pdv_id = p.id and l.ativa
  where p.cnpj = regexp_replace(coalesce(p_cnpj, ''), '\D', '', 'g')
  order by l.nome nulls first, l.endereco
$$;

grant execute on function public.lojas_do_cnpj(text) to authenticated;

-- Antifraude simples: se a nota tem chave E foi ligada a uma loja cadastrada,
-- o CNPJ da chave precisa ser o do PDV dessa loja.
create function public._nf_confere_loja()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.fonte = 'usuario_nf' and new.chave_acesso_nf is not null and new.loja_id is not null
     and not exists (
       select 1 from public.lojas l join public.pdvs p on p.id = l.pdv_id
       where l.id = new.loja_id and p.cnpj = substring(new.chave_acesso_nf from 7 for 14)
     ) then
    raise exception 'loja_nao_confere';
  end if;
  return new;
end;
$$;

create trigger cotacoes_nf_confere_loja
  before insert on public.cotacoes
  for each row execute function public._nf_confere_loja();

-- >>>>>>>>>> 20260923000600_nf_data_validade.sql
-- =============================================================================
-- NF: data da compra e validade de 7 dias (decisão do fundador)
--
-- O preço de uma NF é o preço praticado NAQUELE dia (vale até a meia-noite
-- da data da nota). Na busca aparece "Preço praticado dia dd/mm/aaaa" e o
-- preço continua visível por 7 dias a partir da data da nota.
-- =============================================================================

alter table public.cotacoes add column data_nf date;

update public.cotacoes set data_nf = created_at::date
 where fonte = 'usuario_nf' and data_nf is null;

alter table public.cotacoes
  add constraint cotacoes_data_nf_so_em_nf check (
    (fonte = 'usuario_nf' and data_nf is not null) or (fonte <> 'usuario_nf' and data_nf is null)
  );

create or replace function public.validade_nf_dias() returns integer
language sql immutable as $$ select 7 $$;

-- Retorno ganha a coluna data_nf: precisa recriar.
drop function public.buscar_cotacoes(text, double precision, double precision, integer);

create function public.buscar_cotacoes(
  p_termo   text default null,
  p_lat     double precision default null,
  p_lng     double precision default null,
  p_limite  integer default 100
)
returns table (
  id              uuid,
  produto         text,
  preco_centavos  integer,
  validade        date,
  obs             text,
  fonte           public.fonte_cotacao,
  loja_id         uuid,
  pdv_id          uuid,
  pdv_nome        text,
  loja_nome       text,
  endereco        text,
  telefone        text,
  site            text,
  latitude        double precision,
  longitude       double precision,
  distancia_km    double precision,
  created_at      timestamptz,
  data_nf         date
)
language sql
stable
security invoker
set search_path = ''
as $$
  with termo as (
    select nullif(public.normalizar(p_termo), '') as t
  ),
  palavras as (
    select array_remove(string_to_array(t, ' '), '') as ps from termo
  )
  select c.id,
         c.produto,
         c.preco_centavos,
         c.validade,
         c.obs,
         c.fonte,
         c.loja_id,
         p.id,
         coalesce(p.nome_fantasia, c.pdv_nome_livre),
         l.nome,
         coalesce(
           concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf),
           c.pdv_endereco_livre
         ),
         l.telefone,
         p.site,
         l.latitude,
         l.longitude,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude),
         c.created_at,
         c.data_nf
  from public.cotacoes c
  left join public.lojas l on l.id = c.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  cross join termo
  cross join palavras
  where (l.id is null or l.ativa)
    and c.validade >= public.hoje()
    and (
      termo.t is null
      or not exists (
        select 1 from unnest(palavras.ps) w
        where strpos(c.produto_busca, w) = 0
      )
    )
  order by
    case when termo.t is null then c.created_at end desc,
    c.preco_centavos asc
  limit least(greatest(coalesce(p_limite, 100), 1), 500)
$$;


grant execute on function public.buscar_cotacoes(text, double precision, double precision, integer)
  to authenticated;

-- Novo parâmetro p_data_nf: precisa recriar.
drop function public.enviar_nota_fiscal(text, uuid, text, text, jsonb);

-- Uma NF -> vários produtos, enviados de uma vez após a tela de confirmação.
-- CPF do comprador NUNCA é pedido nem armazenado.
create function public.enviar_nota_fiscal(
  p_chave_acesso  text,
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_itens         jsonb,
  p_data_nf       date default null   -- data da compra (emissão da NF); padrão: hoje
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_chave  text := nullif(regexp_replace(coalesce(p_chave_acesso, ''), '\D', '', 'g'), '');
  v_lote   uuid := gen_random_uuid();
  v_n      integer;
  v_data   date := coalesce(p_data_nf, public.hoje());
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if v_chave is not null and length(v_chave) <> 44 then
    raise exception 'chave_acesso_invalida';
  end if;
  if v_data > public.hoje() then
    raise exception 'data_nf_futura';
  end if;
  -- Nota mais velha que a validade já nasceria fora da busca.
  if v_data < public.hoje() - public.validade_nf_dias() then
    raise exception 'nf_antiga';
  end if;
  -- A chave traz ano/mês de emissão (AAMM, posições 3 a 6).
  if v_chave is not null and substring(v_chave from 3 for 4) <> to_char(v_data, 'YYMM') then
    raise exception 'data_nao_confere';
  end if;
  if v_chave is not null and exists (select 1 from public.cotacoes where chave_acesso_nf = v_chave) then
    raise exception 'nf_ja_enviada';
  end if;
  if p_loja_id is null and length(btrim(coalesce(p_pdv_nome, ''))) = 0 then
    raise exception 'pdv_obrigatorio';
  end if;
  if p_loja_id is not null and not exists (select 1 from public.lojas where id = p_loja_id and ativa) then
    raise exception 'loja_invalida';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 500 then
    raise exception 'itens_demais';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where length(btrim(coalesce(e ->> 'produto', ''))) = 0
       or coalesce((e ->> 'preco_centavos')::integer, 0) <= 0
  ) then
    raise exception 'itens_invalidos';
  end if;

  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade, data_nf,
     fonte, chave_acesso_nf, enviado_por, lote_id)
  select p_loja_id,
         case when p_loja_id is null then btrim(p_pdv_nome) end,
         case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         v_data + public.validade_nf_dias(),
         v_data,
         'usuario_nf',
         v_chave,
         auth.uid(),
         v_lote
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  return jsonb_build_object('lote_id', v_lote, 'itens', v_n);
end;
$$;


revoke execute on function public.enviar_nota_fiscal(text, uuid, text, text, jsonb, date) from public, anon;
grant execute on function public.enviar_nota_fiscal(text, uuid, text, text, jsonb, date) to authenticated;

-- >>>>>>>>>> 20260923000700_enviar_encarte.sql
-- =============================================================================
-- Envio de encarte pelo usuário comum (briefing, seções 4 e 6)
--
-- O usuário fotografa o encarte (até 5 fotos, ex.: frente e verso), diz de qual
-- estabelecimento é e, se o encarte mostrar, até quando valem as ofertas.
-- Vai para a fila do Admin (encartes_pendentes); não vira preço até ser aprovado.
-- =============================================================================

alter table public.encartes_pendentes
  add column fotos text[] not null default '{}';

update public.encartes_pendentes set fotos = array[foto_path] where cardinality(fotos) = 0;

-- Quem grava só foto_path (versão anterior) continua funcionando: vira a única foto.
create function public._encarte_fotos_padrao()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if cardinality(new.fotos) = 0 then
    new.fotos := array[new.foto_path];
  end if;
  return new;
end;
$$;

create trigger encartes_fotos_padrao
  before insert on public.encartes_pendentes
  for each row execute function public._encarte_fotos_padrao();

alter table public.encartes_pendentes
  add constraint encartes_fotos_qtd check (cardinality(fotos) between 1 and 5);

create function public.limite_encartes_por_dia() returns integer
language sql immutable as $$ select 10 $$;

create function public.enviar_encarte(
  p_fotos         text[],
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_validade      date default null,   -- "válido até", se o encarte mostrar
  p_comentario    text default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id uuid;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if p_fotos is null or cardinality(p_fotos) = 0 then
    raise exception 'foto_obrigatoria';
  end if;
  if cardinality(p_fotos) > 5 then
    raise exception 'fotos_demais';
  end if;
  -- As fotos precisam estar na pasta do próprio usuário no bucket "encartes".
  if exists (select 1 from unnest(p_fotos) f where f not like auth.uid()::text || '/%') then
    raise exception 'foto_invalida';
  end if;
  if p_loja_id is null and length(btrim(coalesce(p_pdv_nome, ''))) = 0 then
    raise exception 'pdv_obrigatorio';
  end if;
  if p_loja_id is not null and not exists (select 1 from public.lojas where id = p_loja_id and ativa) then
    raise exception 'loja_invalida';
  end if;
  if p_validade is not null and p_validade < public.hoje() then
    raise exception 'validade_passada';
  end if;
  if (select count(*) from public.encartes_pendentes
      where enviado_por = auth.uid() and created_at > now() - interval '1 day')
     >= public.limite_encartes_por_dia() then
    raise exception 'limite_encartes';
  end if;

  insert into public.encartes_pendentes
    (enviado_por, foto_path, fotos, loja_id, pdv_nome, pdv_endereco, validade, comentario)
  values
    (auth.uid(), p_fotos[1], p_fotos, p_loja_id,
     case when p_loja_id is null then btrim(p_pdv_nome) end,
     case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
     p_validade, nullif(btrim(p_comentario), ''))
  returning id into v_id;

  return v_id;
end;
$$;

-- Estabelecimentos cadastrados, para o usuário escolher de qual é o encarte.
create function public.buscar_lojas(p_termo text, p_limite integer default 10)
returns table (
  loja_id    uuid,
  pdv_nome   text,
  loja_nome  text,
  endereco   text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, p.nome_fantasia, l.nome,
         concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
  from public.lojas l
  join public.pdvs p on p.id = l.pdv_id
  where l.ativa
    and length(public.normalizar(p_termo)) >= 2
    and strpos(public.normalizar(p.nome_fantasia || ' ' || coalesce(l.nome, '') || ' ' || coalesce(l.bairro, '')),
               public.normalizar(p_termo)) > 0
  order by p.nome_fantasia, l.nome nulls first
  limit least(greatest(coalesce(p_limite, 10), 1), 30)
$$;

-- "Meus encartes": o que o usuário enviou e em que pé está.
create function public.meus_encartes(p_limite integer default 20)
returns table (
  id               uuid,
  pdv_nome         text,
  status           public.status_encarte,
  validade         date,
  motivo_rejeicao  text,
  created_at       timestamptz
)
language sql
stable
security invoker
set search_path = ''
as $$
  select e.id,
         coalesce(concat_ws(' — ', p.nome_fantasia, l.nome), e.pdv_nome),
         e.status, e.validade, e.motivo_rejeicao, e.created_at
  from public.encartes_pendentes e
  left join public.lojas l on l.id = e.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  where e.enviado_por = auth.uid()
  order by e.created_at desc
  limit least(greatest(coalesce(p_limite, 20), 1), 100)
$$;

revoke execute on function public.meus_encartes(integer) from public, anon;
grant execute on function public.meus_encartes(integer) to authenticated, service_role;

revoke execute on function public.enviar_encarte(text[], uuid, text, text, date, text) from public, anon;
revoke execute on function public.buscar_lojas(text, integer) from public, anon;
revoke execute on function public.limite_encartes_por_dia() from public, anon;
grant execute on function public.enviar_encarte(text[], uuid, text, text, date, text) to authenticated, service_role;
grant execute on function public.buscar_lojas(text, integer) to authenticated, service_role;
grant execute on function public.limite_encartes_por_dia() to authenticated, service_role;

-- Fotos de encarte: até 5 MB, só imagem.
do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    update storage.buckets
       set file_size_limit = 5242880,
           allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp']
     where id = 'encartes';
  end if;
end;
$$;

-- >>>>>>>>>> 20260923000800_publicar_encarte.sql
-- =============================================================================
-- Encarte publicado direto pelo usuário (decisão do fundador — sem fila do Admin)
--
-- O app lê as fotos do encarte no próprio celular (OCR), mostra ao usuário os
-- produtos e preços encontrados e, com a confirmação dele, os preços vão direto
-- para a busca (fonte usuario_encarte), valendo até a data impressa no encarte.
-- As fotos continuam guardadas no bucket privado "encartes" para auditoria, e o
-- encarte fica registrado em encartes_pendentes já com status 'aprovado'.
-- =============================================================================

create function public.publicar_encarte(
  p_fotos         text[],
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_validade      date,    -- "válido até" impresso no encarte (obrigatório)
  p_itens         jsonb    -- [{produto, preco_centavos}] lidos das fotos e confirmados pelo usuário
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id  uuid;
  v_n   integer;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if p_fotos is null or cardinality(p_fotos) = 0 then
    raise exception 'foto_obrigatoria';
  end if;
  if cardinality(p_fotos) > 5 then
    raise exception 'fotos_demais';
  end if;
  -- As fotos precisam estar na pasta do próprio usuário no bucket "encartes".
  if exists (select 1 from unnest(p_fotos) f where f not like auth.uid()::text || '/%') then
    raise exception 'foto_invalida';
  end if;
  if p_loja_id is null and length(btrim(coalesce(p_pdv_nome, ''))) = 0 then
    raise exception 'pdv_obrigatorio';
  end if;
  if p_loja_id is not null and not exists (select 1 from public.lojas where id = p_loja_id and ativa) then
    raise exception 'loja_invalida';
  end if;
  if p_validade is null then
    raise exception 'validade_obrigatoria';
  end if;
  if p_validade < public.hoje() then
    raise exception 'validade_passada';
  end if;
  if p_validade > public.hoje() + public.validade_maxima_dias() then
    raise exception 'validade_longa';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 200 then
    raise exception 'itens_demais';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where length(btrim(coalesce(e ->> 'produto', ''))) not between 2 and 200
       or coalesce((e ->> 'preco_centavos')::integer, 0) <= 0
  ) then
    raise exception 'itens_invalidos';
  end if;
  if (select count(*) from public.encartes_pendentes
      where enviado_por = auth.uid() and created_at > now() - interval '1 day')
     >= public.limite_encartes_por_dia() then
    raise exception 'limite_encartes';
  end if;

  insert into public.encartes_pendentes
    (enviado_por, foto_path, fotos, loja_id, pdv_nome, pdv_endereco, validade, status, revisado_em)
  values
    (auth.uid(), p_fotos[1], p_fotos, p_loja_id,
     case when p_loja_id is null then btrim(p_pdv_nome) end,
     case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
     p_validade, 'aprovado', now())
  returning id into v_id;

  -- O mesmo produto lido duas vezes (ex.: em duas fotos) entra uma vez só.
  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade,
     fonte, enviado_por, encarte_id, lote_id)
  select distinct on (public.normalizar(btrim(e ->> 'produto')))
         p_loja_id,
         case when p_loja_id is null then btrim(p_pdv_nome) end,
         case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         p_validade,
         'usuario_encarte',
         auth.uid(),
         v_id,
         v_id
  from jsonb_array_elements(p_itens) with ordinality as t(e, ordem)
  order by public.normalizar(btrim(e ->> 'produto')), ordem;
  get diagnostics v_n = row_count;

  return v_n;
end;
$$;

revoke execute on function public.publicar_encarte(text[], uuid, text, text, date, jsonb) from public, anon;
grant execute on function public.publicar_encarte(text[], uuid, text, text, date, jsonb) to authenticated, service_role;

-- A fila do Admin para encartes de usuário deixa de existir no app.
drop function public.enviar_encarte(text[], uuid, text, text, date, text);

-- "Meus encartes" agora diz quantos preços cada encarte publicou.
drop function public.meus_encartes(integer);

create function public.meus_encartes(p_limite integer default 20)
returns table (
  id               uuid,
  pdv_nome         text,
  status           public.status_encarte,
  validade         date,
  motivo_rejeicao  text,
  created_at       timestamptz,
  itens            integer
)
language sql
stable
security invoker
set search_path = ''
as $$
  select e.id,
         coalesce(concat_ws(' — ', p.nome_fantasia, l.nome), e.pdv_nome),
         e.status, e.validade, e.motivo_rejeicao, e.created_at,
         (select count(*)::integer from public.cotacoes c where c.encarte_id = e.id)
  from public.encartes_pendentes e
  left join public.lojas l on l.id = e.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  where e.enviado_por = auth.uid()
  order by e.created_at desc
  limit least(greatest(coalesce(p_limite, 20), 1), 100)
$$;

revoke execute on function public.meus_encartes(integer) from public, anon;
grant execute on function public.meus_encartes(integer) to authenticated, service_role;

-- >>>>>>>>>> 20260924000900_encarte_suspenso.sql
-- =============================================================================
-- Envio de encarte por usuário: SUSPENSO (decisão do fundador)
--
-- A leitura automática das fotos deixava o envio vulnerável (preço errado ou
-- inventado indo direto para a busca). A aba saiu do app e a função deixa de
-- ser chamável pelo app/API. A função e os dados continuam no banco para
-- quando o envio de encarte voltar.
-- =============================================================================

revoke execute on function public.publicar_encarte(text[], uuid, text, text, date, jsonb) from authenticated;

-- >>>>>>>>>> 20260924001000_pdv_verificacao.sql
-- =============================================================================
-- Cadastro do PDV com comprovação pelo alvará (decisão do fundador)
--
-- Para evitar que alguém cadastre uma empresa que não é dele:
--   1. O CNPJ precisa ser válido; o app consulta a Receita (situação, nome,
--      endereço) e guarda o resultado em dados_receita, para o Admin conferir.
--   2. O dono envia a foto do ALVARÁ (bucket privado "alvaras"). O app lê a foto
--      e diz se achou o CNPJ nela (cnpj_conferido_no_alvara) — ajuda o Admin,
--      mas não aprova sozinho: o que roda no celular pode ser adulterado.
--   3. O PDV nasce 'pendente'. Só o Admin aprova ou rejeita (com motivo).
--   4. Enquanto não estiver aprovado, o PDV não publica preço nem promoção e
--      suas lojas não aparecem para ligar notas fiscais.
-- =============================================================================

create type public.status_verificacao as enum ('pendente', 'aprovado', 'rejeitado');

alter table public.pdvs
  add column status                    public.status_verificacao not null default 'pendente',
  add column alvara_path               text,
  add column cnpj_conferido_no_alvara  boolean not null default false,
  add column dados_receita             jsonb,
  add column motivo_rejeicao           text check (motivo_rejeicao is null or length(motivo_rejeicao) <= 300),
  add column verificado_em             timestamptz,
  add column verificado_por            uuid references public.usuarios (id) on delete set null;

-- PDVs que já existiam (piloto/demonstração) continuam valendo.
update public.pdvs set status = 'aprovado', verificado_em = now();

create index pdvs_pendentes_idx on public.pdvs (created_at) where status = 'pendente';

-- PDV só nasce pela função cadastrar_pdv. O dono edita só os dados de exibição;
-- CNPJ, status e dados de verificação ninguém muda pelo app.
drop policy pdvs_insert on public.pdvs;
revoke insert, update on public.pdvs from authenticated;
grant update (nome_fantasia, site, modo_rede) on public.pdvs to authenticated;
-- Alvará, dados da Receita e motivo de rejeição não são públicos (o dono vê por meus_pdvs()).
revoke select on public.pdvs from authenticated;
grant select (id, dono_id, cnpj, razao_social, nome_fantasia, site, modo_rede, status, created_at, updated_at)
  on public.pdvs to authenticated;

-- -----------------------------------------------------------------------------
-- Dígitos verificadores do CNPJ (mesma regra do app: core/Cnpj.kt)
-- -----------------------------------------------------------------------------
create function public._cnpj_valido(p_cnpj text)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_pesos1 constant int[] := array[5,4,3,2,9,8,7,6,5,4,3,2];
  v_pesos2 constant int[] := array[6,5,4,3,2,9,8,7,6,5,4,3,2];
  v_soma int;
  v_dv1 int;
  v_dv2 int;
begin
  if p_cnpj is null or p_cnpj !~ '^[0-9]{14}$' or p_cnpj ~ '^(.)\1{13}$' then
    return false;
  end if;
  v_soma := 0;
  for i in 1..12 loop v_soma := v_soma + substr(p_cnpj, i, 1)::int * v_pesos1[i]; end loop;
  v_dv1 := case when v_soma % 11 < 2 then 0 else 11 - v_soma % 11 end;
  v_soma := 0;
  for i in 1..13 loop v_soma := v_soma + substr(p_cnpj, i, 1)::int * v_pesos2[i]; end loop;
  v_dv2 := case when v_soma % 11 < 2 then 0 else 11 - v_soma % 11 end;
  return substr(p_cnpj, 13, 1)::int = v_dv1 and substr(p_cnpj, 14, 1)::int = v_dv2;
end;
$$;

-- -----------------------------------------------------------------------------
-- Cadastro (conta CNPJ)
-- -----------------------------------------------------------------------------
create function public.cadastrar_pdv(
  p_cnpj                 text,
  p_nome_fantasia        text,
  p_razao_social         text,
  p_endereco             text,
  p_bairro               text,
  p_cidade               text,
  p_uf                   text,
  p_cep                  text,
  p_telefone             text,
  p_alvara_path          text,
  p_cnpj_no_alvara       boolean default false,
  p_dados_receita        jsonb default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cnpj       text := regexp_replace(coalesce(p_cnpj, ''), '\D', '', 'g');
  v_existente  public.pdvs%rowtype;
  v_id         uuid;
  v_tem_foto   boolean;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if coalesce((select tipo from public.usuarios where id = auth.uid()), 'cpf') <> 'cnpj' then
    raise exception 'conta_nao_cnpj';
  end if;
  if not public._cnpj_valido(v_cnpj) then
    raise exception 'cnpj_invalido';
  end if;
  if length(btrim(coalesce(p_nome_fantasia, ''))) = 0 then
    raise exception 'nome_obrigatorio';
  end if;
  if length(btrim(coalesce(p_endereco, ''))) = 0 then
    raise exception 'endereco_obrigatorio';
  end if;
  if p_alvara_path is null or p_alvara_path not like auth.uid()::text || '/%' then
    raise exception 'alvara_obrigatorio';
  end if;
  -- No Supabase, a foto precisa ter sido enviada de fato.
  -- (SQL dinâmico: nos testes locais não existe o schema storage.)
  if to_regclass('storage.objects') is not null then
    execute 'select exists (select 1 from storage.objects where bucket_id = $1 and name = $2)'
      into v_tem_foto using 'alvaras', p_alvara_path;
    if not v_tem_foto then
      raise exception 'alvara_obrigatorio';
    end if;
  end if;

  select * into v_existente from public.pdvs where cnpj = v_cnpj for update;
  if found then
    if v_existente.status = 'aprovado' then
      raise exception 'cnpj_ja_cadastrado';
    elsif v_existente.status = 'pendente' and v_existente.dono_id <> auth.uid() then
      raise exception 'cnpj_em_analise';
    end if;
    -- Pedido rejeitado (de qualquer pessoa) ou o próprio pedido pendente: começa de novo.
    delete from public.pdvs where id = v_existente.id;
  end if;

  insert into public.pdvs
    (dono_id, cnpj, razao_social, nome_fantasia, modo_rede, status,
     alvara_path, cnpj_conferido_no_alvara, dados_receita)
  values
    (auth.uid(), v_cnpj, nullif(btrim(p_razao_social), ''), btrim(p_nome_fantasia), true, 'pendente',
     p_alvara_path, coalesce(p_cnpj_no_alvara, false), p_dados_receita)
  returning id into v_id;

  insert into public.lojas (pdv_id, endereco, bairro, cidade, uf, cep, telefone)
  values (v_id, btrim(p_endereco), nullif(btrim(p_bairro), ''),
          coalesce(nullif(btrim(p_cidade), ''), 'Petrópolis'),
          coalesce(left(nullif(upper(btrim(p_uf)), ''), 2), 'RJ'),
          nullif(btrim(p_cep), ''), nullif(btrim(p_telefone), ''));

  return v_id;
end;
$$;

-- Os PDVs do usuário logado, com a situação do cadastro.
create function public.meus_pdvs()
returns table (
  id                        uuid,
  cnpj                      text,
  razao_social              text,
  nome_fantasia             text,
  status                    public.status_verificacao,
  motivo_rejeicao           text,
  cnpj_conferido_no_alvara  boolean,
  created_at                timestamptz,
  verificado_em             timestamptz
)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.cnpj::text, p.razao_social, p.nome_fantasia, p.status, p.motivo_rejeicao,
         p.cnpj_conferido_no_alvara, p.created_at, p.verificado_em
  from public.pdvs p
  where p.dono_id = auth.uid()
  order by p.created_at
$$;

-- -----------------------------------------------------------------------------
-- Admin: fila de cadastros
-- -----------------------------------------------------------------------------
create function public.pdvs_pendentes()
returns table (
  id                        uuid,
  cnpj                      text,
  razao_social              text,
  nome_fantasia             text,
  endereco                  text,
  telefone                  text,
  alvara_path               text,
  cnpj_conferido_no_alvara  boolean,
  dados_receita             jsonb,
  dono_nome                 text,
  dono_email                text,
  created_at                timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  return query
    select p.id, p.cnpj::text, p.razao_social, p.nome_fantasia,
           (select concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
              from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1),
           (select l.telefone from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1),
           p.alvara_path, p.cnpj_conferido_no_alvara, p.dados_receita, u.nome, u.email, p.created_at
    from public.pdvs p
    left join public.usuarios u on u.id = p.dono_id
    where p.status = 'pendente'
    order by p.created_at;
end;
$$;

create function public.aprovar_pdv(p_pdv_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  update public.pdvs
     set status = 'aprovado', motivo_rejeicao = null, verificado_em = now(), verificado_por = auth.uid()
   where id = p_pdv_id and status = 'pendente';
  if not found then
    raise exception 'pdv_nao_encontrado';
  end if;
end;
$$;

-- Rejeita um pedido pendente, ou suspende um PDV já aprovado (fraude descoberta
-- depois): nesse caso os preços oficiais dele saem da busca.
create function public.rejeitar_pdv(p_pdv_id uuid, p_motivo text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  if length(btrim(coalesce(p_motivo, ''))) = 0 then
    raise exception 'motivo_obrigatorio';
  end if;
  update public.pdvs
     set status = 'rejeitado', motivo_rejeicao = left(btrim(p_motivo), 300),
         verificado_em = now(), verificado_por = auth.uid()
   where id = p_pdv_id and status <> 'rejeitado';
  if not found then
    raise exception 'pdv_nao_encontrado';
  end if;
  delete from public.cotacoes c
   using public.lojas l
   where c.loja_id = l.id and l.pdv_id = p_pdv_id and c.fonte in ('pdv_manual', 'pdv_excel');
end;
$$;

-- -----------------------------------------------------------------------------
-- PDV não aprovado não publica preço nem promoção
-- -----------------------------------------------------------------------------
create function public._pdv_precisa_estar_aprovado()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_status public.status_verificacao;
begin
  if tg_table_name = 'cotacoes' then
    if new.fonte not in ('pdv_manual', 'pdv_excel') then
      return new;
    end if;
    select p.status into v_status
      from public.lojas l join public.pdvs p on p.id = l.pdv_id
     where l.id = new.loja_id;
  else
    select p.status into v_status from public.pdvs p where p.id = new.pdv_id;
  end if;
  if v_status is distinct from 'aprovado' then
    raise exception 'pdv_nao_verificado';
  end if;
  return new;
end;
$$;

create trigger cotacoes_pdv_aprovado
  before insert or update on public.cotacoes
  for each row execute function public._pdv_precisa_estar_aprovado();

create trigger promocoes_pdv_aprovado
  before insert on public.promocoes
  for each row execute function public._pdv_precisa_estar_aprovado();

-- Lojas de PDV não aprovado não aparecem para ligar notas fiscais.
create or replace function public.lojas_do_cnpj(p_cnpj text)
returns table (
  loja_id    uuid,
  pdv_nome   text,
  loja_nome  text,
  endereco   text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, p.nome_fantasia, l.nome,
         concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
  from public.pdvs p
  join public.lojas l on l.pdv_id = p.id and l.ativa
  where p.cnpj = regexp_replace(coalesce(p_cnpj, ''), '\D', '', 'g')
    and p.status = 'aprovado'
  order by l.nome nulls first, l.endereco
$$;

create or replace function public.buscar_lojas(p_termo text, p_limite integer default 10)
returns table (
  loja_id    uuid,
  pdv_nome   text,
  loja_nome  text,
  endereco   text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, p.nome_fantasia, l.nome,
         concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
  from public.lojas l
  join public.pdvs p on p.id = l.pdv_id
  where l.ativa
    and p.status = 'aprovado'
    and length(public.normalizar(p_termo)) >= 2
    and strpos(public.normalizar(p.nome_fantasia || ' ' || coalesce(l.nome, '') || ' ' || coalesce(l.bairro, '')),
               public.normalizar(p_termo)) > 0
  order by p.nome_fantasia, l.nome nulls first
  limit least(greatest(coalesce(p_limite, 10), 1), 30)
$$;

revoke execute on function public._cnpj_valido(text) from public, anon, authenticated;
revoke execute on function public._pdv_precisa_estar_aprovado() from public, anon, authenticated;
revoke execute on function public.cadastrar_pdv(text, text, text, text, text, text, text, text, text, text, boolean, jsonb)
  from public, anon;
revoke execute on function public.meus_pdvs() from public, anon;
revoke execute on function public.pdvs_pendentes() from public, anon;
revoke execute on function public.aprovar_pdv(uuid) from public, anon;
revoke execute on function public.rejeitar_pdv(uuid, text) from public, anon;
grant execute on function public.cadastrar_pdv(text, text, text, text, text, text, text, text, text, text, boolean, jsonb)
  to authenticated, service_role;
grant execute on function public.meus_pdvs() to authenticated, service_role;
grant execute on function public.pdvs_pendentes() to authenticated, service_role;
grant execute on function public.aprovar_pdv(uuid) to authenticated, service_role;
grant execute on function public.rejeitar_pdv(uuid, text) to authenticated, service_role;
grant execute on function public._cnpj_valido(text) to service_role;

-- -----------------------------------------------------------------------------
-- Fotos de alvará: alvaras/<id-do-usuário>/<arquivo>. Privado: o dono e o Admin.
-- -----------------------------------------------------------------------------
do $$
begin
  if not exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    raise notice 'schema storage ausente — pulando bucket de alvarás (ambiente de teste local)';
    return;
  end if;

  insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  values ('alvaras', 'alvaras', false, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
  on conflict (id) do nothing;

  execute $p$
    create policy alvaras_upload on storage.objects for insert to authenticated
      with check (bucket_id = 'alvaras' and (storage.foldername(name))[1] = auth.uid()::text)
  $p$;
  execute $p$
    create policy alvaras_leitura on storage.objects for select to authenticated
      using (bucket_id = 'alvaras'
             and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()))
  $p$;
end;
$$;
