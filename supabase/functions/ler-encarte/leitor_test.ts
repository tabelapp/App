import { type Dependencias, inicioDoDiaBrasilia, tratarPedido } from "./leitor.ts";
import { validarEntrada, validarSaida } from "./esquema.ts";

function igual(a: unknown, b: unknown, msg = "") {
  if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(`${msg} esperado ${JSON.stringify(b)}, veio ${JSON.stringify(a)}`);
}

const SAIDA_OK = {
  loja: { nome: "Assaí", validade: "2026-11-15" },
  itens: [
    { produto: "Leite Italac 1L", preco_centavos: 479, preco_normal_centavos: 529, preco_app: true, unidade: "unidade",
      validade: null, obs: "Limite 6 un. por CPF", alcoolico: false },
    { produto: "Cerveja X 350ml", preco_centavos: 399, preco_normal_centavos: null, preco_app: false, unidade: "unidade",
      validade: "2026-11-12", obs: null, alcoolico: true },
    { produto: "Sem preço", preco_centavos: 0, preco_normal_centavos: null, preco_app: false, unidade: "unidade",
      validade: null, obs: null, alcoolico: false },
  ],
  ignorados: [{ texto: "Biscoito", motivo: "preço ilegível" }],
};

function deps(extra: Partial<Dependencias> = {}): Dependencias & { registros: unknown[] } {
  const registros: unknown[] = [];
  return {
    registros,
    usuarioDoToken: async (t) => (t === "token-admin" ? "admin-1" : t === "token-cpf" ? "cpf-1" : null),
    ehAdmin: async (id) => id === "admin-1",
    leiturasHoje: async () => 0,
    registrar: async (_id, r) => { registros.push(r); },
    lerComModelo: async () => SAIDA_OK,
    chaveApi: () => "chave-secreta-de-teste",
    limiteDiario: 30,
    ...extra,
  };
}

function pedido(token: string | null, corpo: unknown = { imagem_base64: "QUJD", tipo: "image/jpeg", loja: "Assaí" }) {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  return new Request("http://local/ler-encarte", { method: "POST", headers, body: JSON.stringify(corpo) });
}

Deno.test("sem token ou token inválido: nao_autenticado", async () => {
  igual((await tratarPedido(pedido(null), deps())).status, 401);
  igual((await tratarPedido(pedido("x"), deps())).status, 401);
});

Deno.test("usuário que não é Admin: negado, e o modelo nem é chamado", async () => {
  let chamou = false;
  const r = await tratarPedido(pedido("token-cpf"), deps({ lerComModelo: async () => { chamou = true; return SAIDA_OK; } }));
  igual(r.status, 403);
  igual((await r.json()).erro, "sem_permissao");
  igual(chamou, false);
});

Deno.test("sem a chave da API: mensagem calma de não configurado", async () => {
  const r = await tratarPedido(pedido("token-admin"), deps({ chaveApi: () => undefined }));
  igual(r.status, 503);
  igual((await r.json()).mensagem, "leitor de encartes ainda não configurado");
});

Deno.test("limite diário", async () => {
  const r = await tratarPedido(pedido("token-admin"), deps({ leiturasHoje: async () => 30 }));
  igual(r.status, 429);
});

Deno.test("imagem inválida", async () => {
  igual((await tratarPedido(pedido("token-admin", { imagem_base64: "QUJD", tipo: "image/gif" }), deps())).status, 400);
  igual((await tratarPedido(pedido("token-admin", { tipo: "image/jpeg" }), deps())).status, 400);
  igual((await tratarPedido(pedido("token-admin", { imagem_base64: "não é base64!", tipo: "image/png" }), deps())).status, 400);
});

Deno.test("falha do modelo: resposta sem detalhes e leitura registrada como erro", async () => {
  const d = deps({ lerComModelo: async () => { throw new Error("401 chave-secreta-de-teste inválida"); } });
  const r = await tratarPedido(pedido("token-admin"), d);
  const texto = await r.text();
  igual(r.status, 502);
  igual(texto.includes("chave-secreta"), false, "nunca devolve a chave");
  igual(texto.includes("401"), false, "nunca devolve o detalhe técnico");
  igual(d.registros, [{ sucesso: false, itens: 0, ignorados: 0, erro: "falha_leitura" }]);
});

Deno.test("sucesso: JSON conferido, item ruim vai para ignorados, leitura registrada", async () => {
  const d = deps();
  const r = await tratarPedido(pedido("token-admin"), d);
  igual(r.status, 200);
  const s = await r.json();
  igual(s.itens.length, 2);
  igual(s.itens[0].validade, "2026-11-15", "sem validade no item, vale a do encarte");
  igual(s.itens[1].alcoolico, true);
  igual(s.ignorados.length, 2);
  igual(d.registros, [{ sucesso: true, itens: 2, ignorados: 2, erro: null }]);
});

Deno.test("validarSaida: formato errado e limites", () => {
  igual(validarSaida(null), null);
  igual(validarSaida({ itens: "x", ignorados: [] }), null);
  const longa = validarSaida({
    loja: { nome: " ", validade: "15/11/2026" },
    itens: [{ produto: "  Café   Pilão ", preco_centavos: 1899, preco_normal_centavos: null, preco_app: false,
      unidade: "tonelada", validade: null, obs: null, alcoolico: false },
      { produto: "Óleo", preco_centavos: 799, preco_normal_centavos: null, preco_app: false, unidade: "unidade",
        validade: "15/11/2026", obs: "x".repeat(300), alcoolico: false }],
    ignorados: [],
  })!;
  igual(longa.loja.nome, null);
  igual(longa.itens.length, 1);
  igual(longa.itens[0].obs!.length, 140);
  igual(longa.itens[0].validade, null, "data fora do formato ISO não passa");
  igual(longa.ignorados[0].motivo, "unidade desconhecida");
});

Deno.test("validarEntrada: tipos aceitos", () => {
  igual(validarEntrada({ imagem_base64: "QUJD", tipo: "application/pdf" })?.tipo, "application/pdf");
  igual(validarEntrada({ imagem_base64: "", tipo: "image/png" }), null);
});

Deno.test("início do dia em Brasília", () => {
  igual(inicioDoDiaBrasilia(new Date("2026-11-09T02:30:00Z")), "2026-11-08T00:00:00-03:00");
  igual(inicioDoDiaBrasilia(new Date("2026-11-09T12:00:00Z")), "2026-11-09T00:00:00-03:00");
});
