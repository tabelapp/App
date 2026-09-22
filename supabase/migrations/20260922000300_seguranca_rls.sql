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
