/** Formato da resposta do leitor de encartes e a conferência dela antes de devolver ao app. */

export const UNIDADES = ["unidade", "kg", "litro", "metro", "m2", "hora", "visita", "diaria", "servico"] as const;
export const OBS_MAX = 140;
export const MAX_ITENS = 300;
/** Imagem/PDF em base64: no máximo ~7,5 MB. */
export const MAX_BASE64 = 10_000_000;
export const TIPOS_ACEITOS = ["image/jpeg", "image/png", "application/pdf"] as const;

const textoOuNulo = { type: ["string", "null"] };

/** JSON Schema da saída estruturada (só tipos e enums; faixas e tamanhos são conferidos em validarSaida). */
export const ESQUEMA_SAIDA = {
  type: "object",
  additionalProperties: false,
  required: ["loja", "itens", "ignorados"],
  properties: {
    loja: {
      type: "object",
      additionalProperties: false,
      required: ["nome", "validade"],
      properties: { nome: textoOuNulo, validade: textoOuNulo },
    },
    itens: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["produto", "preco_centavos", "preco_normal_centavos", "preco_app", "unidade", "validade", "obs", "alcoolico"],
        properties: {
          produto: { type: "string" },
          preco_centavos: { type: "integer" },
          preco_normal_centavos: { type: ["integer", "null"] },
          preco_app: { type: "boolean" },
          unidade: { type: "string", enum: [...UNIDADES] },
          validade: textoOuNulo,
          obs: textoOuNulo,
          alcoolico: { type: "boolean" },
        },
      },
    },
    ignorados: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["texto", "motivo"],
        properties: { texto: { type: "string" }, motivo: { type: "string" } },
      },
    },
  },
} as const;

export interface ItemEncarte {
  produto: string;
  preco_centavos: number;
  preco_normal_centavos: number | null;
  preco_app: boolean;
  unidade: (typeof UNIDADES)[number];
  validade: string | null;
  obs: string | null;
  alcoolico: boolean;
}

export interface Ignorado {
  texto: string;
  motivo: string;
}

export interface SaidaEncarte {
  loja: { nome: string | null };
  itens: ItemEncarte[];
  ignorados: Ignorado[];
}

export interface Entrada {
  imagem_base64: string;
  tipo: (typeof TIPOS_ACEITOS)[number];
  loja: string;
}

/** Confere o corpo do pedido do app. Devolve null se estiver inválido. */
export function validarEntrada(corpo: unknown): Entrada | null {
  if (typeof corpo !== "object" || corpo === null) return null;
  const c = corpo as Record<string, unknown>;
  const imagem = c.imagem_base64;
  const tipo = c.tipo;
  const loja = typeof c.loja === "string" ? c.loja.trim().slice(0, 120) : "";
  if (typeof imagem !== "string" || imagem.length === 0 || imagem.length > MAX_BASE64) return null;
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(imagem)) return null;
  if (typeof tipo !== "string" || !(TIPOS_ACEITOS as readonly string[]).includes(tipo)) return null;
  return { imagem_base64: imagem, tipo: tipo as Entrada["tipo"], loja };
}

const dataIso = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Confere a resposta do modelo. Item com campo fora da regra não derruba a leitura: vai para
 * "ignorados" com o motivo. Devolve null se o formato geral estiver errado.
 */
export function validarSaida(bruto: unknown): SaidaEncarte | null {
  if (typeof bruto !== "object" || bruto === null) return null;
  const r = bruto as Record<string, unknown>;
  if (!Array.isArray(r.itens) || !Array.isArray(r.ignorados)) return null;
  const lojaBruta = (typeof r.loja === "object" && r.loja !== null) ? r.loja as Record<string, unknown> : {};
  const validadeGeral = typeof lojaBruta.validade === "string" && dataIso.test(lojaBruta.validade) ? lojaBruta.validade : null;
  const ignorados: Ignorado[] = [];
  for (const i of r.ignorados) {
    if (typeof i === "object" && i !== null) {
      const o = i as Record<string, unknown>;
      ignorados.push({ texto: String(o.texto ?? "").slice(0, 200), motivo: String(o.motivo ?? "").slice(0, 200) });
    }
  }
  const itens: ItemEncarte[] = [];
  for (const i of r.itens) {
    const motivo = problemaDoItem(i);
    if (motivo) {
      const texto = typeof i === "object" && i !== null ? String((i as Record<string, unknown>).produto ?? "") : "";
      ignorados.push({ texto: texto.slice(0, 200), motivo });
      continue;
    }
    const o = i as Record<string, unknown>;
    const normal = typeof o.preco_normal_centavos === "number" && o.preco_normal_centavos > 0 ? o.preco_normal_centavos : null;
    const obs = typeof o.obs === "string" ? o.obs.trim().slice(0, OBS_MAX) : null;
    itens.push({
      produto: (o.produto as string).trim().replace(/\s+/g, " "),
      preco_centavos: o.preco_centavos as number,
      preco_normal_centavos: normal,
      preco_app: o.preco_app === true,
      unidade: o.unidade as ItemEncarte["unidade"],
      validade: typeof o.validade === "string" && dataIso.test(o.validade) ? o.validade : validadeGeral,
      obs: obs ? obs : null,
      alcoolico: o.alcoolico === true,
    });
  }
  if (itens.length > MAX_ITENS) {
    for (const extra of itens.splice(MAX_ITENS)) ignorados.push({ texto: extra.produto, motivo: "mais de 300 itens" });
  }
  const nome = typeof lojaBruta.nome === "string" && lojaBruta.nome.trim() ? lojaBruta.nome.trim().slice(0, 120) : null;
  return { loja: { nome }, itens, ignorados };
}

function problemaDoItem(i: unknown): string | null {
  if (typeof i !== "object" || i === null) return "item inválido";
  const o = i as Record<string, unknown>;
  if (typeof o.produto !== "string" || !o.produto.trim()) return "sem nome do produto";
  if (o.produto.length > 200) return "nome muito longo";
  if (!Number.isInteger(o.preco_centavos) || (o.preco_centavos as number) <= 0 || (o.preco_centavos as number) > 99_999_999) {
    return "preço ilegível";
  }
  if (o.preco_normal_centavos !== null && o.preco_normal_centavos !== undefined && !Number.isInteger(o.preco_normal_centavos)) {
    return "preço normal ilegível";
  }
  if (typeof o.unidade !== "string" || !(UNIDADES as readonly string[]).includes(o.unidade)) return "unidade desconhecida";
  return null;
}
