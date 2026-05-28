package com.TCC.FlyGuide.services;

import com.TCC.FlyGuide.DTO.GerarRoteiroRequestDTO;
import com.TCC.FlyGuide.DTO.GerarRoteiroResponseDTO;
import com.TCC.FlyGuide.DTO.LocalBuscaDTO;
import com.TCC.FlyGuide.entities.Imagem;
import com.TCC.FlyGuide.repositories.ImagemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GeradorRoteiroService {

    private static final Logger logger = LoggerFactory.getLogger(GeradorRoteiroService.class);

    @Value("${anthropic.api.key:}")
    private String anthropicApiKey;

    @Autowired
    private ImagemRepository imagemRepository;

    @Autowired
    private GooglePlacesService googlePlacesService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String[] PERIODOS = {"manha", "tarde", "noite"};

    public GerarRoteiroResponseDTO gerar(GerarRoteiroRequestDTO req) {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            logger.warn("FALLBACK ativado para: {} — anthropic.api.key está vazio ou não configurado", req.getCidade());
            return fallback(req);
        }

        try {
            String prompt = buildPrompt(req);

            String systemMsg = "Você é um guia turístico profissional. Responda SOMENTE com JSON válido, sem texto adicional, sem markdown. "
                    + "Siga TODAS as instruções do usuário sem exceção. "
                    + "Nunca ignore itens marcados como OBRIGATÓRIO ou CRÍTICO. "
                    + "REGRA ABSOLUTA: cada local gerado DEVE ser um lugar REAL com nome próprio pesquisável no Google Maps. "
                    + "NUNCA use descrições genéricas como: 'Passeio pelo centro histórico', 'Galeria de arte local', "
                    + "'Bairro turístico a pé', 'Jantar ao ar livre', 'Bar temático', 'Noite cultural local', "
                    + "'Fogueira e confraternização', 'Restaurante típico regional', 'Mercado gastronômico'. "
                    + "Se não souber o nome real de um local, OMITA a atividade — nunca invente descrições. "
                    + "O destino escolhido pelo turista é \"" + req.getCidade() + "\". "
                    + "Se for um local específico (estádio, parque, museu, atração), "
                    + "use exatamente \"" + req.getCidade() + "\" como um dos itens do roteiro.";

            Map<String, Object> reqMap = new HashMap<>();
            reqMap.put("model",       "claude-haiku-4-5-20251001");
            reqMap.put("max_tokens",  4096);
            reqMap.put("temperature", 1.0);
            reqMap.put("system",      systemMsg);
            reqMap.put("messages",    List.of(Map.of("role", "user", "content", prompt)));
            String requestBody = objectMapper.writeValueAsString(reqMap);

            logger.info("Chamando IA para: {}, modelo: claude-haiku-4-5-20251001", req.getCidade());

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicApiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

            logger.info("Status HTTP da Anthropic: {}", response.statusCode());

            if (response.statusCode() != 200) {
                logger.error("Anthropic retornou status {} para {}. Body: {}", response.statusCode(), req.getCidade(), response.body());
                return fallback(req);
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("content").get(0).path("text").asText("");
            text = stripMarkdown(text);

            JsonNode aiJson;
            try {
                aiJson = objectMapper.readTree(text);
            } catch (Exception parseEx) {
                int len = text.length();
                String trecho = len > 200 ? text.substring(0, 200) + "...[" + len + " chars total]" : text;
                logger.error("Falha ao parsear JSON da IA para {}. Trecho: [{}] | Erro: {}", req.getCidade(), trecho, parseEx.getMessage());
                return fallback(req);
            }

            String titulo = aiJson.path("titulo").asText("").trim();
            if (titulo.isBlank()) titulo = "Roteiro em " + req.getCidade();

            String descricao   = aiJson.path("descricao").asText("").trim();
            double orcamento   = aiJson.path("orcamentoEstimado").asDouble(0);
            String imagemChave = aiJson.path("imagemChave").asText("cidade").trim();

            List<Map<String, Object>> sugestoes = new ArrayList<>();
            JsonNode sugestoesNode = aiJson.path("sugestoes");
            if (sugestoesNode.isArray()) {
                // Deduplicação global: nenhum local pode aparecer duas vezes no roteiro inteiro
                Set<String> usedNomesAI = new HashSet<>();
                boolean isPOIAI   = req.isDestinoPontoTuristico();
                int     diasTotal = req.getDiasTotais() != null ? req.getDiasTotais() : 1;
                String  cidadeNorm = req.getCidade() != null ? req.getCidade().toLowerCase().trim() : "";

                for (JsonNode diaNode : sugestoesNode) {
                    Map<String, Object> diaMap = new HashMap<>();
                    int diaNum = diaNode.path("dia").asInt();
                    diaMap.put("dia", diaNum);

                    JsonNode periodosNode = diaNode.path("periodos");
                    if (periodosNode.isObject()) {
                        Map<String, Object> periodosMap = new HashMap<>();
                        for (String periodo : PERIODOS) {
                            JsonNode periodoNode = periodosNode.path(periodo);
                            if (periodoNode.isArray()) {
                                List<Map<String, Object>> locaisPeriodo = new ArrayList<>();
                                for (JsonNode localNode : periodoNode) {
                                    String nome = localNode.path("nome").asText("").trim();
                                    if (nome.isEmpty()) continue;
                                    String nomeLow = nome.toLowerCase();
                                    // Remove duplicatas globais (mesmo nome em qualquer dia/período)
                                    if (usedNomesAI.contains(nomeLow)) continue;
                                    // Para POI multi-dia: bloqueia variantes do destino no Dia 1
                                    if (isPOIAI && diasTotal > 1 && diaNum == 1
                                            && !cidadeNorm.isEmpty()
                                            && (nomeLow.startsWith(cidadeNorm) || cidadeNorm.startsWith(nomeLow))) {
                                        continue;
                                    }
                                    usedNomesAI.add(nomeLow);
                                    Map<String, Object> localMap = new HashMap<>();
                                    localMap.put("nome", nome);
                                    String custo = localNode.path("custo").asText("").trim();
                                    if (!custo.isEmpty()) localMap.put("custo", custo);
                                    locaisPeriodo.add(localMap);
                                }
                                periodosMap.put(periodo, locaisPeriodo);
                            }
                        }
                        diaMap.put("periodos", periodosMap);
                    } else {
                        // Fallback: parser antigo com locais flat
                        List<Map<String, Object>> locais = new ArrayList<>();
                        for (JsonNode localNode : diaNode.path("locais")) {
                            String nome = localNode.isTextual()
                                    ? localNode.asText().trim()
                                    : localNode.path("nome").asText("").trim();
                            if (nome.isEmpty() || usedNomesAI.contains(nome.toLowerCase())) continue;
                            usedNomesAI.add(nome.toLowerCase());
                            Map<String, Object> localMap = new HashMap<>();
                            localMap.put("nome", nome);
                            if (!localNode.isTextual()) {
                                String custo = localNode.path("custo").asText("").trim();
                                if (!custo.isEmpty()) localMap.put("custo", custo);
                            }
                            locais.add(localMap);
                        }
                        diaMap.put("locais", locais);
                    }
                    sugestoes.add(diaMap);
                }
            }

            if (!sugestoes.isEmpty()) addCheckinCheckoutMarkers(sugestoes, req);
            GerarRoteiroResponseDTO resp = montarResposta(titulo, descricao, BigDecimal.valueOf(orcamento), imagemChave);
            if (!sugestoes.isEmpty()) resp.setSugestoes(sugestoes);
            return resp;

        } catch (Exception e) {
            logger.error("Erro ao chamar IA para {}: {} | Stack trace:", req.getCidade(), e.getMessage(), e);
            return fallback(req);
        }
    }

    private GerarRoteiroResponseDTO montarResposta(String titulo, String descricao,
                                                    BigDecimal orcamento, String imagemChave) {
        GerarRoteiroResponseDTO resp = new GerarRoteiroResponseDTO();
        resp.setTitulo(titulo);
        resp.setDescricao(descricao);
        resp.setOrcamentoEstimado(orcamento);

        Imagem imagem = imagemRepository.findByChave(imagemChave)
                .orElseGet(() -> imagemRepository.findByChave("cidade").orElse(null));

        if (imagem != null) {
            resp.setIdImagem(imagem.getIdImagem());
            resp.setImagemChave(imagem.getChave());
            resp.setImagemUrl(imagem.getUrl());
        }

        return resp;
    }

    private GerarRoteiroResponseDTO fallback(GerarRoteiroRequestDTO req) {
        logger.warn("FALLBACK ativado para: {}", req.getCidade());
        String chave = tipoParaChave(req.getTipoRoteiro());
        String titulo = "Roteiro em " + req.getCidade();
        String descricao = "Uma viagem inesquecível para " + req.getCidade()
                + (req.getPais() != null && !req.getPais().isBlank() ? ", " + req.getPais() : "") + ".";
        GerarRoteiroResponseDTO resp = montarResposta(titulo, descricao, BigDecimal.ZERO, chave);
        resp.setSugestoes(gerarSugestoesFallback(req));
        return resp;
    }

    private List<Map<String, Object>> gerarSugestoesFallback(GerarRoteiroRequestDTO req) {
        int dias = req.getDiasTotais() != null ? req.getDiasTotais() : 1;
        String tipo   = req.getTipoRoteiro() != null ? req.getTipoRoteiro() : "Cidade";
        String cidade = req.getCidade()      != null ? req.getCidade()      : "destino";
        boolean isPOI = req.isDestinoPontoTuristico();

        String localidade = cidade + (req.getEstado() != null && !req.getEstado().isBlank()
                ? ", " + req.getEstado() : "");

        // Solicita o suficiente para cobrir todos os dias sem repetição
        int needed = dias * 9 + 10;
        int raio   = req.temCoordenadas() ? 10_000 : 0;

        List<String> manhaPool = buscarNomesReaisComFallback(queriesManha(tipo, localidade), req.getLatitude(), req.getLongitude(), raio, needed);
        List<String> tardePool = buscarNomesReaisComFallback(queriesTarde(tipo, localidade), req.getLatitude(), req.getLongitude(), raio, needed);
        List<String> noitePool = buscarNomesReaisComFallback(queriesNoite(tipo, localidade), req.getLatitude(), req.getLongitude(), raio, needed);

        logger.info("Fallback Google Places — manhã: {}, tarde: {}, noite: {} resultados para {}",
                manhaPool.size(), tardePool.size(), noitePool.size(), cidade);

        int manhaIdx = 0, tardeIdx = 0, noiteIdx = 0;
        Set<String> usedNomes = new HashSet<>();
        if (isPOI) usedNomes.add(cidade.toLowerCase());

        List<Map<String, Object>> sugestoes = new ArrayList<>();
        for (int dia = 1; dia <= dias; dia++) {
            Map<String, Object> diaMap = new HashMap<>();
            diaMap.put("dia", dia);
            Map<String, Object> periodosMap = new HashMap<>();

            // Manhã: exclusivamente resultados do Google Places
            List<Map<String, Object>> locaisManha = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String nome = null;
                while (nome == null && manhaIdx < manhaPool.size()) {
                    String c = manhaPool.get(manhaIdx++);
                    if (!usedNomes.contains(c.toLowerCase())) nome = c;
                }
                if (nome != null) {
                    usedNomes.add(nome.toLowerCase());
                    Map<String, Object> item = new HashMap<>();
                    item.put("nome", nome);
                    item.put("custo", "Preço varia");
                    item.put("_replace", Boolean.TRUE);
                    locaisManha.add(item);
                }
            }
            periodosMap.put("manha", locaisManha);

            // Tarde: POI no dia correto + resultados do Google Places
            List<Map<String, Object>> locaisTarde = new ArrayList<>();
            boolean poiDay = isPOI && ((dias == 1 && dia == 1) || (dias > 1 && dia == 2));
            if (poiDay) {
                Map<String, Object> poiItem = new HashMap<>();
                poiItem.put("nome", cidade);
                poiItem.put("custo", "Preço varia");
                locaisTarde.add(poiItem);
            }
            for (int i = 0; i < 3; i++) {
                String nome = null;
                while (nome == null && tardeIdx < tardePool.size()) {
                    String c = tardePool.get(tardeIdx++);
                    if (!usedNomes.contains(c.toLowerCase())) nome = c;
                }
                if (nome != null) {
                    usedNomes.add(nome.toLowerCase());
                    Map<String, Object> item = new HashMap<>();
                    item.put("nome", nome);
                    item.put("custo", "Preço varia");
                    item.put("_replace", Boolean.TRUE);
                    locaisTarde.add(item);
                }
            }
            periodosMap.put("tarde", locaisTarde);

            // Noite: exclusivamente resultados do Google Places
            List<Map<String, Object>> locaisNoite = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String nome = null;
                while (nome == null && noiteIdx < noitePool.size()) {
                    String c = noitePool.get(noiteIdx++);
                    if (!usedNomes.contains(c.toLowerCase())) nome = c;
                }
                if (nome != null) {
                    usedNomes.add(nome.toLowerCase());
                    Map<String, Object> item = new HashMap<>();
                    item.put("nome", nome);
                    item.put("custo", "Preço varia");
                    item.put("_replace", Boolean.TRUE);
                    locaisNoite.add(item);
                }
            }
            periodosMap.put("noite", locaisNoite);

            diaMap.put("periodos", periodosMap);
            sugestoes.add(diaMap);
        }

        addCheckinCheckoutMarkers(sugestoes, req);
        return sugestoes;
    }

    private List<String> buscarNomesReais(String query, int max) {
        return buscarNomesReaisProximos(query, null, null, 0, max);
    }

    private List<String> buscarNomesReaisProximos(String query, Double lat, Double lng, int raioMetros, int max) {
        try {
            List<LocalBuscaDTO> locais = (lat != null && lng != null && raioMetros > 0)
                    ? googlePlacesService.buscarLocaisProximos(query, lat, lng, raioMetros)
                    : googlePlacesService.buscarLocais(query);
            List<String> nomes = new ArrayList<>();
            for (LocalBuscaDTO local : locais) {
                String nome = local.getNome();
                if (nome != null && !nome.isBlank()) {
                    nomes.add(nome);
                    if (nomes.size() >= max) break;
                }
            }
            return nomes;
        } catch (Exception e) {
            logger.warn("Falha ao buscar locais reais para '{}': {}", query, e.getMessage());
            return new ArrayList<>();
        }
    }

    // Tenta cada query em sequência, acumulando até atingir `needed` sem repetir nomes
    private List<String> buscarNomesReaisComFallback(List<String> queries, Double lat, Double lng, int raio, int needed) {
        List<String> resultado = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String query : queries) {
            if (resultado.size() >= needed) break;
            List<String> parcial = buscarNomesReaisProximos(query, lat, lng, raio, needed);
            for (String nome : parcial) {
                if (!seen.contains(nome.toLowerCase())) {
                    seen.add(nome.toLowerCase());
                    resultado.add(nome);
                    if (resultado.size() >= needed) break;
                }
            }
        }
        logger.info("buscarNomesReaisComFallback: {} resultados via {} queries (primeira: {})",
                resultado.size(), queries.size(), queries.isEmpty() ? "-" : queries.get(0));
        return resultado;
    }

    private List<String> queriesManha(String tipo, String localidade) {
        String especifica = switch (tipo) {
            case "Praia"       -> "praias e orla " + localidade;
            case "Natureza"    -> "parques naturais e trilhas " + localidade;
            case "Aventura"    -> "esportes e aventura " + localidade;
            case "Cultural"    -> "museus e patrimônio histórico " + localidade;
            case "Gastronomia" -> "cafés e padarias " + localidade;
            case "Mochilão"    -> "pontos turísticos gratuitos " + localidade;
            case "Luxo"        -> "spas e hotéis de luxo " + localidade;
            case "Família"     -> "parques infantis e zoológico " + localidade;
            default            -> "pontos turísticos " + localidade;
        };
        return List.of(
            especifica,
            "pontos turísticos " + localidade,
            "museus e atrações " + localidade,
            "parques e praças " + localidade,
            "igrejas e monumentos " + localidade,
            "atrações turísticas " + localidade,
            "mercado e feiras " + localidade,
            "pontos de interesse " + localidade
        );
    }

    private List<String> queriesTarde(String tipo, String localidade) {
        String especifica = switch (tipo) {
            case "Praia"       -> "passeios de barco e mergulho " + localidade;
            case "Natureza"    -> "ecoturismo e reservas naturais " + localidade;
            case "Aventura"    -> "esportes radicais e escalada " + localidade;
            case "Cultural"    -> "centros culturais e galerias " + localidade;
            case "Gastronomia" -> "restaurantes e gastronomia " + localidade;
            case "Mochilão"    -> "praças e centros históricos " + localidade;
            case "Luxo"        -> "boutiques e lojas premium " + localidade;
            case "Família"     -> "parques aquáticos e diversão " + localidade;
            default            -> "atrações e passeios " + localidade;
        };
        return List.of(
            especifica,
            "restaurantes " + localidade,
            "atrações turísticas " + localidade,
            "lojas e comércio " + localidade,
            "centros culturais " + localidade,
            "praias e parques " + localidade,
            "pontos turísticos " + localidade,
            "estabelecimentos " + localidade
        );
    }

    private List<String> queriesNoite(String tipo, String localidade) {
        String especifica = switch (tipo) {
            case "Praia"       -> "restaurantes e bares beira-mar " + localidade;
            case "Natureza"    -> "pousadas e restaurantes regionais " + localidade;
            case "Aventura"    -> "bares e pubs " + localidade;
            case "Cultural"    -> "teatros e shows " + localidade;
            case "Gastronomia" -> "restaurantes e jantar " + localidade;
            case "Mochilão"    -> "bares e cervejarias " + localidade;
            case "Luxo"        -> "restaurantes sofisticados " + localidade;
            case "Família"     -> "restaurantes familiares " + localidade;
            default            -> "restaurantes e vida noturna " + localidade;
        };
        return List.of(
            especifica,
            "restaurantes " + localidade,
            "bares e restaurantes " + localidade,
            "gastronomia " + localidade,
            "pizzarias e lanchonetes " + localidade,
            "churrascarías e grelhados " + localidade,
            "cafés e sobremesas " + localidade,
            "estabelecimentos " + localidade
        );
    }

    private static final String[] PERIOD_ORDER = {"manha", "tarde", "noite"};

    @SuppressWarnings("unchecked")
    private void addCheckinCheckoutMarkers(List<Map<String, Object>> sugestoes, GerarRoteiroRequestDTO req) {
        String checkin  = req.getPeriodoCheckin();
        String checkout = req.getPeriodoCheckout();
        int    lastDay  = req.getDiasTotais() != null ? req.getDiasTotais() : 1;

        if (temCheckin(checkin)) {
            sugestoes.stream()
                .filter(d -> Integer.valueOf(1).equals(d.get("dia")))
                .findFirst()
                .ifPresent(dia -> {
                    Map<String, Object> periodos = (Map<String, Object>) dia.computeIfAbsent("periodos", k -> new HashMap<>());

                    // Clear periods that come before check-in (user hasn't arrived yet)
                    boolean reached = false;
                    for (String per : PERIOD_ORDER) {
                        if (per.equals(checkin)) { reached = true; }
                        if (!reached) { periodos.put(per, new ArrayList<>()); }
                    }

                    // Insert check-in marker as FIRST item of its period
                    List<Map<String, Object>> list = (List<Map<String, Object>>) periodos.computeIfAbsent(checkin, k -> new ArrayList<>());
                    Map<String, Object> marker = new HashMap<>();
                    marker.put("nome", "Check-in");
                    marker.put("_checkin", Boolean.TRUE);
                    list.add(0, marker);
                });
        }

        if (temCheckin(checkout)) {
            sugestoes.stream()
                .filter(d -> Integer.valueOf(lastDay).equals(d.get("dia")))
                .findFirst()
                .ifPresent(dia -> {
                    Map<String, Object> periodos = (Map<String, Object>) dia.computeIfAbsent("periodos", k -> new HashMap<>());

                    // Add checkout marker as LAST item of its period
                    List<Map<String, Object>> list = (List<Map<String, Object>>) periodos.computeIfAbsent(checkout, k -> new ArrayList<>());
                    Map<String, Object> marker = new HashMap<>();
                    marker.put("nome", "Checkout");
                    marker.put("_checkout", Boolean.TRUE);
                    list.add(marker);

                    // Clear periods that come after checkout (user has already left)
                    boolean passed = false;
                    for (String per : PERIOD_ORDER) {
                        if (passed) { periodos.put(per, new ArrayList<>()); }
                        if (per.equals(checkout)) { passed = true; }
                    }
                });
        }
    }

    private String tipoParaChave(String tipo) {
        if (tipo == null) return "cidade";
        return switch (tipo) {
            case "Aventura"    -> "aventura";
            case "Cultural"    -> "cultural";
            case "Praia"       -> "praia";
            case "Natureza"    -> "natureza";
            case "Gastronomia" -> "gastronomia";
            case "Mochilão"    -> "mochilao";
            case "Luxo"        -> "luxo";
            case "Família"     -> "familia";
            default            -> "cidade";
        };
    }

    private static boolean temCheckin(String p) { return p != null && !p.isBlank() && !p.equals("sem"); }

    private String buildPrompt(GerarRoteiroRequestDTO req) {
        int    dias     = req.getDiasTotais();
        String cidade   = req.getCidade()      != null ? req.getCidade()      : "";
        String estado   = req.getEstado()      != null ? req.getEstado()      : "";
        String pais     = req.getPais()        != null ? req.getPais()        : "";
        String tipo     = req.getTipoRoteiro() != null ? req.getTipoRoteiro() : "Cidade";
        int    seed     = (int)(System.currentTimeMillis() % 9000) + 1000;
        String checkinP  = req.getPeriodoCheckin();
        String checkoutP = req.getPeriodoCheckout();

        // Instruções condicionais de check-in / checkout para o Dia 1 e último dia
        String instrucaoCheckin = "";
        if (temCheckin(checkinP)) {
            instrucaoCheckin = switch (checkinP) {
                case "tarde" -> "\nATENÇÃO — CHECK-IN: O turista chegará no período da TARDE do Dia 1. "
                        + "NÃO gere NENHUMA atividade para a MANHÃ do Dia 1. "
                        + "O Dia 1 deve conter atividades SOMENTE na tarde e noite.\n";
                case "noite" -> "\nATENÇÃO — CHECK-IN: O turista chegará no período da NOITE do Dia 1. "
                        + "NÃO gere NENHUMA atividade para a MANHÃ nem para a TARDE do Dia 1. "
                        + "O Dia 1 deve conter atividades SOMENTE na noite.\n";
                default -> "";
            };
        }
        String instrucaoCheckout = "";
        if (temCheckin(checkoutP)) {
            instrucaoCheckout = switch (checkoutP) {
                case "manha" -> "\nATENÇÃO — CHECKOUT: O turista partirá de manhã no Dia " + dias + ". "
                        + "NÃO gere NENHUMA atividade para a TARDE nem para a NOITE do Dia " + dias + ". "
                        + "O Dia " + dias + " deve conter atividades SOMENTE na manhã.\n";
                case "tarde" -> "\nATENÇÃO — CHECKOUT: O turista partirá à tarde no Dia " + dias + ". "
                        + "NÃO gere NENHUMA atividade para a NOITE do Dia " + dias + ". "
                        + "O Dia " + dias + " deve conter atividades SOMENTE na manhã e tarde.\n";
                default -> "";
            };
        }

        boolean isPOI = req.isDestinoPontoTuristico();
        String endereco = req.getEnderecoDestino() != null && !req.getEnderecoDestino().isBlank()
                ? req.getEnderecoDestino() : null;
        String todosLocais = isPOI
            ? (!estado.isBlank() ? estado + ", " + pais : pais)
            : cidade + (!estado.isBlank() ? ", " + estado : "") + ", " + pais;

        String poiExtra = isPOI
            ? "=== DESTINO PRINCIPAL CONFIRMADO ===\n"
            + "O destino \"" + cidade + "\" foi identificado como atração específica.\n"
            + (endereco != null ? "Endereço exato: " + endereco + "\n"
                    + "Use EXATAMENTE este nome e endereço — não gere variantes nem locais similares.\n" : "")
            + (dias == 1
                ? "1. Roteiro de 1 dia: \"" + cidade + "\" DEVE aparecer no Dia 1 (use exatamente: \"" + cidade + "\").\n"
                + "2. Demais atividades: mesmo bairro ou bairros vizinhos acessíveis.\n"
                : "1. \"" + cidade + "\" DEVE aparecer como item do roteiro a partir do Dia 2 "
                + "(use exatamente: \"" + cidade + "\").\n"
                + "2. Demais atividades: mesmo bairro ou bairros vizinhos acessíveis.\n"
                + "3. Dia 1: chegada na região — NÃO inclua \"" + cidade + "\" no Dia 1.\n")
            + "=====================================\n\n"
            : "";

        String regiaoBase = cidade + (!estado.isBlank() ? ", " + estado : "") + ", " + pais;
        String estadoRestricao = !estado.isBlank()
                ? "ESTADO OBRIGATÓRIO: " + estado + ". "
                : "";

        return "Você é um especialista em turismo, roteiros urbanos e experiência de viagem.\n\n"
             + "╔══════════════════════════════════════════════════════════════╗\n"
             + "║  REGRA GEOGRÁFICA ABSOLUTA — PROIBIÇÃO TOTAL               ║\n"
             + "╚══════════════════════════════════════════════════════════════╝\n\n"
             + "TODOS os locais gerados DEVEM seguir OBRIGATORIAMENTE estas regras:\n\n"
             + "1. Raio: TODOS os locais devem estar entre 5 km e 10 km de \""
             + cidade + "\" (" + regiaoBase + "). Locais abaixo de 5 km ou acima de 10 km são PROIBIDOS.\n"
             + "2. Distância entre locais do mesmo dia: cada local deve estar a no máximo 10 km do anterior — agrupe atrações próximas no mesmo dia.\n"
             + "3. " + estadoRestricao + "NUNCA inclua locais de outros estados.\n"
             + "4. NUNCA inclua locais de outros países.\n"
             + "5. Se não souber locais suficientes dentro do raio, gere menos atividades — nunca ultrapasse 10 km.\n\n"
             + "EXEMPLO DO QUE É PROIBIDO:\n"
             + "- Destino: Sydney, NSW, Austrália → PROIBIDO incluir Melbourne (>700km)\n"
             + "- Destino: São Paulo, SP, Brasil → PROIBIDO incluir Rio de Janeiro (>400km)\n"
             + "- Destino: Fortaleza, CE, Brasil → PROIBIDO incluir Natal, RN (>500km)\n\n"
             + "Esta é a regra mais importante. Nenhuma outra instrução pode sobrepô-la.\n\n"
             + "Sua tarefa é gerar um roteiro turístico REALISTA, OTIMIZADO, PERSONALIZADO e INTELIGENTE para o destino informado.\n\n"
             + "O roteiro deve parecer planejado por um guia turístico profissional:\n"
             + "- atividades próximas geograficamente no mesmo dia\n"
             + "- horários coerentes com funcionamento dos locais\n"
             + "- boa distribuição entre cultura, gastronomia, lazer e turismo\n"
             + "- o principal tipo de atrações deve ser baseado no filtro de viagem escolhido\n"
             + "- fluxo natural entre manhã, tarde e noite\n"
             + "- evitar deslocamentos longos e sem sentido\n"
             + "- experiências variadas e não repetitivas\n\n"
             + "OBJETIVO:\n"
             + "Crie um roteiro que maximize:\n"
             + "- experiência do turista\n"
             + "- eficiência de deslocamento\n"
             + "- diversidade cultural\n"
             + "- autenticidade local\n\n"
             + "Destino:\n"
             + "- Cidade: " + cidade + "\n"
             + (!estado.isBlank() ? "- Estado: " + estado + "\n" : "")
             + "- País: " + pais + "\n\n"
             + "DESTINO PRINCIPAL OBRIGATÓRIO:\n\n"
             + "O destino informado pelo usuário é o elemento MAIS IMPORTANTE do roteiro.\n\n"
             + "REGRAS OBRIGATÓRIAS:\n"
             + "- O destino informado DEVE aparecer explicitamente em pelo menos uma atividade do roteiro\n"
             + "- O destino informado NÃO pode ser ignorado\n"
             + "- A atividade relacionada ao destino deve usar exatamente o nome informado ou sua variação oficial mais conhecida\n"
             + "- A atividade principal relacionada ao destino deve ter destaque natural no roteiro\n"
             + "- O restante das atividades deve complementar o destino escolhido\n\n"
             + "Exemplo:\n"
             + "Se o usuário informar:\n"
             + "- MorumBIS\n"
             + "→ o roteiro DEVE incluir MorumBIS em alguma atividade\n\n"
             + "- MASP\n"
             + "→ o roteiro DEVE incluir MASP\n\n"
             + "- Beto Carrero World\n"
             + "→ o roteiro DEVE incluir Beto Carrero World\n\n"
             + "- Cristo Redentor\n"
             + "→ o roteiro DEVE incluir Cristo Redentor\n\n"
             + "É PROIBIDO gerar um roteiro sem incluir o destino principal informado.\n\n"
             + "CLASSIFICAÇÃO DO DESTINO:\n\n"
             + "O destino informado pode ser:\n"
             + "- uma cidade\n"
             + "- um estado\n"
             + "- um país\n"
             + "- um bairro\n"
             + "- ou uma atração turística específica\n\n"
             + "REGRAS IMPORTANTES:\n\n"
             + "1. Quando o destino for uma CIDADE, ESTADO, PAÍS ou BAIRRO:\n"
             + "- o roteiro deve explorar diferentes atrações dentro da região\n"
             + "- o destino funciona como área principal da viagem\n\n"
             + "2. Quando o destino for uma ATRAÇÃO TURÍSTICA específica:\n\n"
             + "Exemplos:\n"
             + "- MorumBIS\n"
             + "- MASP\n"
             + "- Cristo Redentor\n"
             + "- Beto Carrero World\n"
             + "- Torre Eiffel\n\n"
             + "Nesse caso:\n"
             + "- o roteiro NÃO deve acontecer apenas dentro da atração\n"
             + "- a atração deve ser incluída como uma das atividades principais\n"
             + "- o restante do roteiro deve explorar atrações próximas e relevantes da cidade/região\n"
             + "- distribua experiências complementares antes e depois da atração principal\n"
             + "- evite repetir a atração em múltiplos períodos\n"
             + "- considere a atração como um ponto importante da viagem, e não como o único local visitado\n\n"
             + "Duração:\n"
             + "- " + dias + " dias\n\n"
             + "Tipo de viagem:\n"
             + "- " + tipo + "\n\n"
             + "IMPORTANTE:\n"
             + "- Todos os locais DEVEM existir de verdade\n"
             + "- Todos os nomes devem ser pesquisáveis no Google Maps\n"
             + "- TODOS os locais devem estar EXCLUSIVAMENTE em: " + todosLocais + "\n"
             + "- RAIO: locais devem estar entre 5 km e 10 km de " + cidade + " — fora deste intervalo são PROIBIDOS\n"
             + "- DISTÂNCIA ENTRE LOCAIS: no mesmo dia, cada local deve estar a no máximo 10 km do anterior\n"
             + (!estado.isBlank() ? "- ESTADO OBRIGATÓRIO: apenas locais do estado \"" + estado + "\" são permitidos\n" : "")
             + "- PAÍS OBRIGATÓRIO: apenas locais em \"" + pais + "\" são permitidos\n"
             + "- NÃO invente atrações\n"
             + "- NÃO use descrições genéricas — use o NOME REAL do local pesquisável no Google Maps\n"
             + "NOMES GENÉRICOS PROIBIDOS (exemplos do que NUNCA gerar):\n"
             + "  ✗ \"Passeio pelo centro histórico\" → ✓ \"Praça da Sé\", \"Largo do Pelourinho\"\n"
             + "  ✗ \"Galeria de arte local\" → ✓ \"MASP\", \"Pinacoteca do Estado de São Paulo\"\n"
             + "  ✗ \"Bairro turístico a pé\" → ✓ \"Vila Madalena\", \"Bairro da Liberdade\"\n"
             + "  ✗ \"Jantar ao ar livre\" → ✓ nome real do restaurante (ex: \"Mocotó\", \"D.O.M.\")\n"
             + "  ✗ \"Bar temático de aventura\" → ✓ nome real do bar (ex: \"Skye Bar\", \"Frank Bar\")\n"
             + "  ✗ \"Noite cultural local\" → ✓ nome real do teatro ou show (ex: \"Teatro Municipal de SP\")\n"
             + "  ✗ \"Fogueira e confraternização\" → ✓ nome de um local de camping ou espaço ao ar livre real\n"
             + "  ✗ \"Restaurante típico regional\" → ✓ nome real do restaurante\n"
             + "  ✗ \"Mercado gastronômico\" → ✓ \"Mercado Municipal de São Paulo\", \"Feira da Liberdade\"\n"
             + "  ✗ \"Trilha de mountain bike\" → ✓ nome real da trilha (ex: \"Trilha da Pedra Grande - Atibaia\")\n"
             + "  ✗ \"Escalada em rocha\" → ✓ nome real do local (ex: \"Pedra do Baú\", \"Parede de Escalada Wap\")\n"
             + "Se não souber o nome real de um local, OMITA a atividade — NUNCA gere descrições genéricas.\n"
             + "- PROIBIDO REPETIR locais: cada local deve aparecer UMA ÚNICA VEZ em todo o roteiro (em nenhum dia, período, manhã, tarde ou noite)\n"
             + "- Se o roteiro tiver " + dias + " dias, use " + dias + " grupos DISTINTOS de locais — NUNCA repita o mesmo nome\n"
             + (isPOI && dias > 1
                 ? "- O destino \"" + cidade + "\" deve aparecer APENAS no Dia 2 (tarde) — NÃO inclua no Dia 1\n"
                 + (endereco != null
                     ? "- NÃO gere variantes de \"" + cidade + "\" (ex: \"" + cidade + " 2\", \"" + cidade + " Filial\") — use SOMENTE o nome exato\n"
                     : "")
                 : "")
             + "- NÃO inclua hospedagem como atividade\n"
             + "- O roteiro pode considerar momentos de chegada e acomodação de forma implícita, mas nunca deve listar check-in, checkout ou hospedagem como atividade principal\n"
             + "- NÃO explique nada fora do JSON\n"
             + "- O JSON deve ser válido para parse diretamente\n"
             + "- NÃO utilize markdown\n"
             + "- NÃO use comentários\n\n"
             + "REGRAS DE QUALIDADE DO ROTEIRO:\n\n"
             + "1. ORGANIZAÇÃO GEOGRÁFICA\n\n"
             + "Agrupe atrações próximas no mesmo dia para reduzir deslocamentos desnecessários.\n\n"
             + "Exemplos:\n"
             + "- Paulista + MASP + Japan House\n"
             + "- Ibirapuera + MAC + Museu Afro\n"
             + "- Centro Histórico + Mercado Municipal + Farol Santander\n\n"
             + "2. LÓGICA DE HORÁRIOS\n\n"
             + "Manhã:\n"
             + "- museus\n"
             + "- parques\n"
             + "- cafés\n"
             + "- centros culturais\n"
             + "- mercados\n"
             + "- atrações históricas\n\n"
             + "Tarde:\n"
             + "- bairros turísticos\n"
             + "- experiências culturais\n"
             + "- galerias\n"
             + "- atrações principais\n"
             + "- passeios urbanos\n\n"
             + "Noite:\n"
             + "- restaurantes famosos\n"
             + "- bares tradicionais\n"
             + "- experiências gastronômicas\n"
             + "- atrações noturnas\n"
             + "- shows ou experiências culturais\n\n"
             + "3. EXPERIÊNCIA BASEADA NO TIPO DE VIAGEM\n\n"
             + "Adapte fortemente o roteiro ao tipo de viagem informado.\n\n"
             + "O tipo de viagem deve influenciar:\n"
             + "- escolha das atrações\n"
             + "- ritmo do roteiro\n"
             + "- perfil dos restaurantes\n"
             + "- experiências sugeridas\n"
             + "- distribuição dos horários\n"
             + "- orçamento médio\n\n"
             + "Regras por tipo:\n\n"
             + "- Cidade: priorize pontos turísticos famosos, bairros icônicos, mirantes, centros urbanos, cafés conhecidos, vida urbana e experiências clássicas da cidade\n\n"
             + "- Praia: priorize praias, quiosques, passeios costeiros, restaurantes à beira-mar, mirantes e experiências relaxantes\n\n"
             + "- Natureza: priorize parques naturais, trilhas, cachoeiras, reservas ecológicas, jardins botânicos, mirantes e experiências contemplativas\n\n"
             + "- Aventura: priorize atividades radicais, trilhas intensas, esportes, parques de aventura e experiências de adrenalina\n\n"
             + "- Cultural: priorize museus, centros históricos, arquitetura, arte urbana, centros culturais, experiências históricas e gastronomia típica local\n\n"
             + "- Gastronomia: priorize restaurantes famosos, mercados municipais, cafeterias, bares tradicionais, culinária típica e experiências gastronômicas locais\n\n"
             + "- Mochilão: priorize atividades acessíveis, atrações gratuitas, experiências locais autênticas, mobilidade urbana e passeios com ótimo custo-benefício\n\n"
             + "- Luxo: priorize restaurantes sofisticados, rooftops, experiências premium, atrações exclusivas, bairros nobres e atividades de alto padrão\n\n"
             + "- Família: priorize atrações leves, parques, zoológicos, aquários, experiências interativas, segurança, conforto e atividades adequadas para todas as idades\n\n"
             + "4. VARIEDADE\n\n"
             + "O roteiro deve misturar:\n"
             + "- atrações famosas\n"
             + "- experiências locais autênticas\n"
             + "- gastronomia\n"
             + "- cultura\n"
             + "- lazer urbano\n\n"
             + "5. COERÊNCIA DE CHEGADA E ENCERRAMENTO\n"
             + "- O primeiro dia deve ter uma programação mais leve e flexível\n"
             + "- O último dia deve evitar atividades muito longas ou distantes\n"
             + "- Considere que o turista pode chegar cansado no início da viagem e precisar de maior flexibilidade no encerramento\n"
             + (instrucaoCheckin.isBlank()
                 ? "- O Dia 1 deve conter atividades reais em todos os períodos disponíveis\n"
                   + "- Nunca deixe um período do Dia 1 sem nenhuma atividade\n"
                 : instrucaoCheckin)
             + "- Atividades de chegada devem ser leves, próximas ao centro/hotel, mas devem existir\n\n"
             + "6. DESTINOS QUE SÃO ATRAÇÕES PRINCIPAIS\n"
             + "Quando o destino informado for uma atração turística específica:\n"
             + "- inclua obrigatoriamente a atração no roteiro ao menos uma vez\n"
             + "- a atração deve aparecer no período mais adequado do dia\n"
             + "- o restante do roteiro deve explorar a cidade/região ao redor\n"
             + "- combine atrações complementares próximas geograficamente\n"
             + "- nunca gere todos os dias apenas dentro da atração\n"
             + "- o roteiro deve parecer uma viagem completa pela região, e não apenas uma visita isolada\n\n"
             + "7. CUSTOS\n\n"
             + "Para cada atividade:\n"
             + "- utilize valores realistas\n"
             + "- utilize um dos formatos:\n"
             + "  \"Gratuito\"\n"
             + "  \"R$ 20 a R$ 50\"\n"
             + "  \"R$ 80 a R$ 150\"\n"
             + "  \"R$ 150 a R$ 300\"\n\n"
             + "8. ORÇAMENTO\n\n"
             + "O orçamento deve considerar:\n"
             + "- alimentação\n"
             + "- entradas\n"
             + "- transporte urbano\n"
             + "- perfil do tipo de viagem\n"
             + "- duração total da viagem\n\n"
             + "9. IMAGEMCHAVE\n\n"
             + "Escolha apenas UMA opção:\n"
             + "cidade, praia, natureza, montanha, aventura, cultural, gastronomia, luxo, neve, mochilao, familia\n\n"
             + "10. DESCRIÇÃO\n\n"
             + "A descrição deve:\n"
             + "- ter entre 2 e 3 frases\n"
             + "- ser envolvente\n"
             + "- destacar a essência do destino\n"
             + "- soar natural e inspiradora\n\n"
             + "11. TÍTULO\n\n"
             + "O título deve:\n"
             + "- ser criativo\n"
             + "- soar turístico/profissional\n"
             + "- ter no máximo 70 caracteres\n\n"
             + "12. QUALIDADE E DIVERSIDADE DAS ATIVIDADES\n\n"
             + "- Priorize locais bem avaliados e conhecidos\n"
             + "- Misture pontos turísticos clássicos com experiências menos óbvias\n"
             + "- Evite listar apenas museus ou apenas restaurantes\n"
             + "- Crie uma progressão lógica ao longo do dia\n"
             + "- Considere horários realistas de funcionamento\n\n"
             + "REGRAS ANTI-REPETIÇÃO:\n"
             + "- NÃO reutilize sempre os mesmos pontos turísticos famosos\n"
             + "- NÃO gere roteiros genéricos\n"
             + "- Evite depender excessivamente de:\n"
             + "  - MASP\n"
             + "  - Parque Ibirapuera\n"
             + "  - Mercado Municipal\n"
             + "  - Avenida Paulista\n"
             + "  - Farol Santander\n"
             + "  quando existirem outras opções relevantes\n\n"
             + "- Explore atrações variadas da cidade/região\n"
             + "- Utilize bairros diferentes quando fizer sentido\n"
             + "- Priorize diversidade cultural e descoberta local\n"
             + "- Gere roteiros menos previsíveis e mais personalizados\n\n"
             + "13. DISTRIBUIÇÃO DOS DIAS\n"
             + "- Evite concentrar todas as atrações famosas em um único dia\n"
             + "- Distribua os bairros e regiões de forma equilibrada\n"
             + "- Evite repetir o mesmo tipo de experiência várias vezes seguidas\n\n"
             + "14. COERÊNCIA LOGÍSTICA\n"
             + "- Evite deslocamentos longos entre atividades consecutivas\n"
             + "- Priorize bairros e regiões próximas dentro do mesmo período\n"
             + "- Considere trânsito urbano e tempo médio de deslocamento\n"
             + "- Evite roteiros cansativos ou inviáveis\n\n"
             + "15. QUALIDADE GERAL DO ROTEIRO\n\n"
             + "O roteiro deve parecer:\n"
             + "- natural\n"
             + "- humano\n"
             + "- organizado\n"
             + "- turístico\n"
             + "- realista\n"
             + "- agradável de seguir\n\n"
             + "Nunca gere um roteiro que pareça aleatório ou artificial.\n\n"
             + "16. PERSONALIZAÇÃO AVANÇADA\n\n"
             + "O roteiro deve parecer personalizado especificamente para o destino informado.\n\n"
             + "NUNCA gere um roteiro \"template\".\n\n"
             + "Antes de definir as atividades:\n"
             + "- interprete o contexto do destino\n"
             + "- identifique atrações relacionadas\n"
             + "- entenda o perfil do local\n"
             + "- adapte os bairros visitados\n"
             + "- adapte restaurantes e experiências próximas\n\n"
             + "Exemplo:\n"
             + "- MorumBIS → futebol, bares esportivos, região do estádio, experiências esportivas\n"
             + "- Liberdade → cultura japonesa, restaurantes orientais, karaokês, feiras\n"
             + "- Beco do Batman → arte urbana, cafés, Vila Madalena, galerias\n"
             + "- Allianz Parque → experiências esportivas, bares, vida noturna próxima\n\n"
             + "O roteiro deve parecer criado especificamente para aquele destino e não reutilizado de outro usuário.\n\n"
             + "Identificador de variação único: #" + seed + " — use-o para garantir que este roteiro seja diferente de outros gerados para o mesmo destino.\n\n"
             + poiExtra
             + "ESTRUTURA OBRIGATÓRIA:\n\n"
             + "Responda SOMENTE com JSON válido:\n\n"
             + "{\"titulo\":\"\",\"descricao\":\"\",\"orcamentoEstimado\":0,\"imagemChave\":\"\","
             + "\"sugestoes\":[{\"dia\":1,\"periodos\":{"
             + "\"manha\":[{\"nome\":\"\",\"custo\":\"\"}],"
             + "\"tarde\":[{\"nome\":\"\",\"custo\":\"\"}],"
             + "\"noite\":[{\"nome\":\"\",\"custo\":\"\"}]"
             + "}}]}\n\n"
             + "REGRAS FINAIS OBRIGATÓRIAS:\n\n"
             + "- sugestoes deve conter EXATAMENTE " + dias + " dias (dia 1 até dia " + dias + ")\n"
             + "- Cada dia deve conter:\n"
             + "  - manha\n"
             + "  - tarde\n"
             + "  - noite\n"
             + "- Cada período ATIVO deve conter EXATAMENTE 3 atividades\n"
             + instrucaoCheckin
             + instrucaoCheckout
             + (instrucaoCheckin.isBlank() && instrucaoCheckout.isBlank()
                 ? "- NUNCA deixe um período sem nenhuma atividade em QUALQUER dia\n"
                 : "- Períodos indicados como ausentes acima devem ter array VAZIO []\n"
                   + "- Todos os outros períodos devem ter EXATAMENTE 3 atividades\n")
             + "- Use apenas locais reais\n"
             + "- O destino principal informado deve aparecer obrigatoriamente em pelo menos uma atividade\n"
             + "- TODOS os locais devem estar entre 5 km e 10 km de " + cidade + " e no " + (!pais.isBlank() ? "país: " + pais : "mesmo país") + (!estado.isBlank() ? ", estado: " + estado : "") + "\n"
             + "- NÃO adicione texto fora do JSON\n"
             + "- NÃO utilize markdown\n"
             + "- NÃO use comentários\n"
             + "- O JSON deve ser válido para parse diretamente\n";
    }

    private String stripMarkdown(String text) {
        String s = text == null ? "" : text.trim();
        if (s.startsWith("```json")) s = s.substring(7);
        else if (s.startsWith("```"))  s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        // Remove BOM e caracteres de controle inválidos em JSON (exceto \t \n \r)
        s = s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return s.trim();
    }
}
