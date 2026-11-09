/**
 * Edge Function "ler-encarte" (só Admin): recebe a imagem (JPEG/PNG) ou PDF de um encarte, lê
 * produtos e preços com o modelo de visão e devolve JSON conferido. A imagem não é guardada.
 * Chave da API: secret ANTHROPIC_API_KEY do Supabase (nunca no app nem no repositório).
 */
import Anthropic from "npm:@anthropic-ai/sdk@1.6.0";
import { createClient } from "npm:@supabase/supabase-js@2.119.0";
import { ESQUEMA_SAIDA, type Entrada } from "./esquema.ts";
import { inicioDoDiaBrasilia, tratarPedido } from "./leitor.ts";
import { PROMPT_ENCARTE } from "./prompt.ts";

const MODELO = "claude-sonnet-5-5";

const url = Deno.env.get("SUPABASE_URL") ?? "";
const chaveAnon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const chaveServico = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
// Chave de serviço: só aqui dentro do servidor (lê `usuarios.tipo` e grava `leituras_encarte`).
const servico = createClient(url, chaveServico, { auth: { persistSession: false } });

async function lerComModelo(entrada: Entrada, chave: string): Promise<unknown> {
  const cliente = new Anthropic({ apiKey: chave });
  const arquivo = entrada.tipo === "application/pdf"
    ? { type: "document" as const, source: { type: "base64" as const, media_type: "application/pdf" as const, data: entrada.imagem_base64 } }
    : { type: "image" as const, source: { type: "base64" as const, media_type: entrada.tipo, data: entrada.imagem_base64 } };
  const hoje = new Date().toLocaleDateString("pt-BR", { timeZone: "America/Sao_Paulo" });
  const resposta = await cliente.beta.messages.create({
    model: MODELO,
    max_tokens: 16000,
    // Se o modelo recusar por engano, o servidor da Anthropic tenta outro modelo recomendado.
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    system: PROMPT_ENCARTE,
    output_config: { format: { type: "json_schema", schema: ESQUEMA_SAIDA } },
    messages: [{
      role: "user",
      content: [
        arquivo,
        { type: "text", text: `Hoje é ${hoje}. Loja informada pelo Admin: ${entrada.loja || "(não informada)"}. Leia o encarte.` },
      ],
    }],
  });
  if (resposta.stop_reason !== "end_turn") throw new Error(`parada: ${resposta.stop_reason}`);
  const texto = resposta.content.find((b) => b.type === "text");
  if (!texto || texto.type !== "text") throw new Error("sem texto");
  return JSON.parse(texto.text);
}

Deno.serve((req) =>
  tratarPedido(req, {
    async usuarioDoToken(token) {
      const cliente = createClient(url, chaveAnon, { auth: { persistSession: false } });
      const { data, error } = await cliente.auth.getUser(token);
      return error || !data.user ? null : data.user.id;
    },
    async ehAdmin(id) {
      const { data } = await servico.from("usuarios").select("tipo").eq("id", id).maybeSingle();
      return data?.tipo === "admin";
    },
    async leiturasHoje(id) {
      const { count } = await servico.from("leituras_encarte").select("id", { count: "exact", head: true })
        .eq("admin_id", id).gte("criado_em", inicioDoDiaBrasilia());
      return count ?? 0;
    },
    async registrar(id, r) {
      await servico.from("leituras_encarte").insert({
        admin_id: id, sucesso: r.sucesso, itens: r.itens, ignorados: r.ignorados, erro: r.erro,
      });
    },
    lerComModelo: async (entrada, chave) => {
      try {
        return await lerComModelo(entrada, chave);
      } catch (e) {
        // Só o tipo do erro vai para o log (nunca a chave, nunca a imagem).
        console.error("ler-encarte: falha na leitura", e instanceof Error ? e.name : typeof e);
        throw e;
      }
    },
    chaveApi: () => Deno.env.get("ANTHROPIC_API_KEY") || undefined,
    limiteDiario: Number(Deno.env.get("LIMITE_LEITURAS_DIA") ?? "30") || 30,
  })
);
