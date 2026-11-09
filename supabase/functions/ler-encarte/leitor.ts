/**
 * Regras do pedido "ler encarte", separadas das dependências (Supabase e API da Anthropic)
 * para poderem ser testadas: quem pode chamar, limite diário, conferência da entrada e da saída,
 * e respostas sem nenhum detalhe interno (nunca a chave, nunca a mensagem técnica).
 */
import { type Entrada, type SaidaEncarte, validarEntrada, validarSaida } from "./esquema.ts";

export interface Dependencias {
  /** Id do usuário dono do token (null = token inválido). */
  usuarioDoToken(token: string): Promise<string | null>;
  /** O usuário é Admin? (confere `usuarios.tipo` no banco) */
  ehAdmin(usuarioId: string): Promise<boolean>;
  /** Quantas leituras o Admin já fez hoje (horário de Brasília). */
  leiturasHoje(usuarioId: string): Promise<number>;
  /** Registra a leitura (para controle de custo). Nunca guarda a imagem. */
  registrar(usuarioId: string, r: { sucesso: boolean; itens: number; ignorados: number; erro: string | null }): Promise<void>;
  /** Chama o modelo de visão e devolve o JSON (já como objeto) ou lança erro. */
  lerComModelo(entrada: Entrada, chave: string): Promise<unknown>;
  /** Chave da API (secret ANTHROPIC_API_KEY do Supabase); undefined = não configurado. */
  chaveApi(): string | undefined;
  limiteDiario: number;
}

export type Erro =
  | "metodo_invalido" | "nao_autenticado" | "sem_permissao" | "nao_configurado"
  | "pedido_invalido" | "limite_diario" | "falha_leitura";

const MENSAGENS: Record<Erro, string> = {
  metodo_invalido: "Use POST.",
  nao_autenticado: "Entre de novo no app.",
  sem_permissao: "Só o Admin pode ler encartes.",
  nao_configurado: "leitor de encartes ainda não configurado",
  pedido_invalido: "Imagem inválida. Envie JPEG, PNG ou PDF.",
  limite_diario: "Limite de leituras de hoje atingido. Tente amanhã.",
  falha_leitura: "Não deu para ler este encarte agora. Tente de novo ou use outra imagem.",
};
const STATUS: Record<Erro, number> = {
  metodo_invalido: 405, nao_autenticado: 401, sem_permissao: 403, nao_configurado: 503,
  pedido_invalido: 400, limite_diario: 429, falha_leitura: 502,
};

function json(corpo: unknown, status = 200): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
}

export function erro(codigo: Erro): Response {
  return json({ erro: codigo, mensagem: MENSAGENS[codigo] }, STATUS[codigo]);
}

export async function tratarPedido(req: Request, d: Dependencias): Promise<Response> {
  if (req.method !== "POST") return erro("metodo_invalido");
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!token) return erro("nao_autenticado");
  const usuario = await d.usuarioDoToken(token).catch(() => null);
  if (!usuario) return erro("nao_autenticado");
  if (!(await d.ehAdmin(usuario).catch(() => false))) return erro("sem_permissao");

  const chave = d.chaveApi();
  if (!chave) return erro("nao_configurado");

  const entrada = validarEntrada(await req.json().catch(() => null));
  if (!entrada) return erro("pedido_invalido");

  if ((await d.leiturasHoje(usuario)) >= d.limiteDiario) return erro("limite_diario");

  let saida: SaidaEncarte | null = null;
  try {
    saida = validarSaida(await d.lerComModelo(entrada, chave));
  } catch (_e) {
    saida = null; // detalhe técnico fica fora da resposta
  }
  if (!saida) {
    await d.registrar(usuario, { sucesso: false, itens: 0, ignorados: 0, erro: "falha_leitura" }).catch(() => {});
    return erro("falha_leitura");
  }
  await d.registrar(usuario, { sucesso: true, itens: saida.itens.length, ignorados: saida.ignorados.length, erro: null })
    .catch(() => {});
  return json(saida);
}

/** Início do dia de hoje em Brasília (sem horário de verão desde 2019: UTC-3). */
export function inicioDoDiaBrasilia(agora = new Date()): string {
  const dia = agora.toLocaleDateString("en-CA", { timeZone: "America/Sao_Paulo" });
  return `${dia}T00:00:00-03:00`;
}
