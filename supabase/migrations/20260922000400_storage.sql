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
