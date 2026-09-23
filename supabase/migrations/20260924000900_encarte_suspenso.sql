-- =============================================================================
-- Envio de encarte por usuário: SUSPENSO (decisão do fundador)
--
-- A leitura automática das fotos deixava o envio vulnerável (preço errado ou
-- inventado indo direto para a busca). A aba saiu do app e a função deixa de
-- ser chamável pelo app/API. A função e os dados continuam no banco para
-- quando o envio de encarte voltar.
-- =============================================================================

revoke execute on function public.publicar_encarte(text[], uuid, text, text, date, jsonb) from authenticated;
