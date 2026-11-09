-- =============================================================================
-- Remove o leitor de encartes da aba Admin (adiado em 09/11/2026).
-- Desfaz o que a versão anterior da migração 20261109003400 criou em produção:
-- a tabela leituras_encarte (nunca usada: a função de leitura não foi publicada)
-- e as funções admin_lancar_encarte, admin_desfazer_lote e admin_lojas_encarte
-- (nenhum app publicado as chama). Não mexe em nenhum preço lançado. Idempotente.
-- =============================================================================
drop function if exists public.admin_lancar_encarte(text, text, jsonb);
drop function if exists public.admin_desfazer_lote(uuid);
drop function if exists public.admin_lojas_encarte();
drop table if exists public.leituras_encarte;
