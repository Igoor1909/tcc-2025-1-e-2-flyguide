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

            String systemMsg = "Você é um guia turístico profissional especializado em roteiros personalizados. "
                    + "Sua única saída deve ser JSON válido, sem markdown, sem texto adicional, sem comentários. "
                    + "Use SOMENTE nomes reais de locais pesquisáveis no Google Maps. "
                    + "Nunca use descrições genéricas como 'Passeio pelo centro', 'Restaurante típico' ou 'Galeria local'. "
                    + "Se não souber o nome real de um local, omita-o. "
                    + "O destino principal da viagem é \"" + req.getCidade() + "\" — ele deve aparecer em pelo menos uma atividade do roteiro.";

            Map<String, Object> reqMap = new HashMap<>();
            reqMap.put("model",       "claude-sonnet-4-6");
            reqMap.put("max_tokens",  4096);
            reqMap.put("temperature", 0.7);
            reqMap.put("system",      systemMsg);
            reqMap.put("messages",    List.of(Map.of("role", "user", "content", prompt)));
            String requestBody = objectMapper.writeValueAsString(reqMap);

            logger.info("Chamando IA para: {}, modelo: claude-sonnet-4-6", req.getCidade());

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicApiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
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
        int needed = dias * 12 + 10;
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
            for (int i = 0; i < 4; i++) {
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
            for (int i = 0; i < 4; i++) {
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
            for (int i = 0; i < 4; i++) {
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
        boolean isPOI   = req.isDestinoPontoTuristico();
        String endereco = req.getEnderecoDestino() != null && !req.getEnderecoDestino().isBlank()
                ? req.getEnderecoDestino() : null;

        String localizacao = cidade
                + (!estado.isBlank() ? ", " + estado : "")
                + (!pais.isBlank()   ? ", " + pais   : "");

        // Períodos bloqueados por checkin/checkout
        String bloqueioCheckin = "";
        if (temCheckin(checkinP)) {
            bloqueioCheckin = switch (checkinP) {
                case "tarde" -> "CHECK-IN NO DIA 1: turista chega na TARDE. Dia 1: manhã=[]. Dia 1 tarde e noite: de 3 a 4 atividades cada.\n";
                case "noite" -> "CHECK-IN NO DIA 1: turista chega à NOITE. Dia 1: manhã=[], tarde=[]. Dia 1 noite: de 3 a 4 atividades.\n";
                default -> "";
            };
        }
        String bloqueioCheckout = "";
        if (temCheckin(checkoutP)) {
            bloqueioCheckout = switch (checkoutP) {
                case "manha" -> "CHECKOUT NO DIA " + dias + ": turista parte de manhã. Dia " + dias + ": tarde=[], noite=[]. Manhã: de 3 a 4 atividades.\n";
                case "tarde" -> "CHECKOUT NO DIA " + dias + ": turista parte à tarde. Dia " + dias + ": noite=[]. Manhã e tarde: de 3 a 4 atividades cada.\n";
                default -> "";
            };
        }

        // Instrução específica para POI (ponto turístico como destino)
        String instrucaoPOI = "";
        if (isPOI) {
            instrucaoPOI = "\nDESTINO É UMA ATRAÇÃO ESPECÍFICA: \"" + cidade + "\""
                    + (endereco != null ? " (endereço: " + endereco + ")" : "") + ".\n"
                    + (dias == 1
                        ? "- Inclua \"" + cidade + "\" no Dia 1 (tarde). Demais atividades: região ao redor.\n"
                        : "- NÃO inclua \"" + cidade + "\" no Dia 1 (dia de chegada/ambientação).\n"
                        + "- Inclua \"" + cidade + "\" no Dia 2 (tarde). Use exatamente este nome.\n"
                        + "- Outros dias: atrações da região ao redor.\n")
                    + "- Nunca repita esta atração em outros períodos ou dias.\n";
        }

        // Perfil do tipo de viagem
        String perfilTipo = switch (tipo) {
            case "Praia"       -> "PRAIA: priorize praias, orla, quiosques, passeios costeiros, restaurantes beira-mar, mergulho.";
            case "Natureza"    -> "NATUREZA: priorize parques, trilhas, cachoeiras, reservas ecológicas, jardins botânicos, mirantes.";
            case "Aventura"    -> "AVENTURA: priorize esportes radicais, trilhas intensas, escalada, parques de aventura, adrenalina.";
            case "Cultural"    -> "CULTURAL: priorize museus, patrimônio histórico, arquitetura, arte urbana, centros culturais, gastronomia típica.";
            case "Gastronomia" -> "GASTRONOMIA: priorize restaurantes renomados, mercados, cafeterias, feiras gastronômicas, culinária típica local.";
            case "Mochilão"    -> "MOCHILÃO: priorize atrações gratuitas, experiências autênticas e locais, ótimo custo-benefício, mobilidade urbana.";
            case "Luxo"        -> "LUXO: priorize restaurantes sofisticados, rooftops, spas, experiências premium, bairros nobres, boutiques.";
            case "Família"     -> "FAMÍLIA: priorize parques, zoológicos, aquários, atrações interativas, segurança e conforto para todas as idades.";
            default            -> "CIDADE: priorize pontos turísticos famosos, bairros icônicos, mirantes, cafés, vida urbana, experiências clássicas.";
        };

        return "Gere um roteiro turístico em JSON para o destino abaixo. Responda SOMENTE com JSON válido, sem texto adicional.\n\n"
             + "=== DESTINO ===\n"
             + "- Local: " + localizacao + "\n"
             + "- Tipo de viagem: " + tipo + "\n"
             + "- Duração: " + dias + " dia" + (dias > 1 ? "s" : "") + "\n"
             + "- Variação #" + seed + "\n\n"
             + "=== PERFIL DA VIAGEM ===\n"
             + perfilTipo + "\n\n"
             + "=== REGRAS GEOGRÁFICAS ===\n"
             + "- Todos os locais devem estar na área de " + localizacao + " e arredores (até ~15 km).\n"
             + (!estado.isBlank() ? "- Apenas locais do estado \"" + estado + "\". Nunca inclua outros estados.\n" : "")
             + (!pais.isBlank()   ? "- Apenas locais em \"" + pais + "\". Nunca inclua outros países.\n" : "")
             + "- Agrupe atrações próximas no mesmo dia para minimizar deslocamentos.\n\n"
             + "=== REGRAS DE QUALIDADE ===\n"
             + "- Use SOMENTE nomes reais e pesquisáveis no Google Maps. Se não souber o nome real, OMITA — nunca invente.\n"
             + "- Para destinos internacionais, use o nome oficial do local (pode ser em outro idioma), ex: Tóquio → \"Senso-ji\", \"Shibuya Crossing\", \"TeamLab Borderless\".\n"
             + "- NOMES GENÉRICOS SÃO PROIBIDOS. Exemplos do que nunca gerar:\n"
             + "  ✗ \"Passeio pelo centro histórico\"  →  ✓ \"Praça da Sé\", \"Largo do Pelourinho\"\n"
             + "  ✗ \"Museu local\" / \"Galeria de arte\"  →  ✓ \"MASP\", \"Museu do Ipiranga\"\n"
             + "  ✗ \"Restaurante típico\" / \"Jantar regional\"  →  ✓ \"Mocotó\", \"D.O.M.\", \"Sushi Saito\"\n"
             + "  ✗ \"Bar temático\" / \"Vida noturna local\"  →  ✓ \"Skye Bar\", \"Frank Bar\"\n"
             + "  ✗ \"Trilha ecológica\" / \"Parque natural\"  →  ✓ \"Trilha da Pedra Grande\", \"Parque Estadual Serra do Mar\"\n"
             + "  ✗ \"Mercado gastronômico\"  →  ✓ \"Mercado Municipal de São Paulo\", \"Tsukiji Outer Market\"\n"
             + "  ✗ \"Bairro turístico a pé\"  →  ✓ \"Vila Madalena\", \"Bairro da Liberdade\", \"Shinjuku\"\n"
             + "- Cada local deve aparecer UMA ÚNICA VEZ em todo o roteiro.\n"
             + "- Não inclua hospedagem, check-in ou checkout como atividade.\n"
             + "- O destino principal (\"" + cidade + "\") deve aparecer em pelo menos uma atividade.\n"
             + instrucaoPOI + "\n"
             + "=== LÓGICA DE HORÁRIOS ===\n"
             + "- Manhã: museus, parques, cafés, atrações históricas, mercados.\n"
             + "- Tarde: bairros turísticos, galerias, atrações principais, passeios urbanos.\n"
             + "- Noite: restaurantes famosos, bares, experiências gastronômicas, shows, vida noturna.\n\n"
             + "=== CHECKIN / CHECKOUT ===\n"
             + (bloqueioCheckin.isBlank() && bloqueioCheckout.isBlank()
                 ? "Todos os períodos de todos os dias devem ter de 3 a 4 atividades.\n"
                 : bloqueioCheckin + bloqueioCheckout)
             + "\n"
             + "=== ESTRUTURA JSON OBRIGATÓRIA ===\n"
             + "{\"titulo\":\"\",\"descricao\":\"\",\"imagemChave\":\"\","
             + "\"sugestoes\":[{\"dia\":1,\"periodos\":{"
             + "\"manha\":[{\"nome\":\"\"}],"
             + "\"tarde\":[{\"nome\":\"\"}],"
             + "\"noite\":[{\"nome\":\"\"}]"
             + "}}]}\n\n"
             + "- titulo: criativo, turístico, máx. 70 caracteres.\n"
             + "- descricao: 2-3 frases inspiradoras sobre o destino.\n"
             + "- imagemChave: UMA de: cidade, praia, natureza, montanha, aventura, cultural, gastronomia, luxo, neve, mochilao, familia.\n"
             + "- sugestoes: EXATAMENTE " + dias + " dia(s), numerados de 1 a " + dias + ".\n"
             + "- Cada período ATIVO: de 3 a 4 atividades. Período bloqueado: array vazio [].\n";
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
