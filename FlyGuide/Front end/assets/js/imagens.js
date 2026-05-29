/* ================================================================
   FlyGuide - imagens.js
   Funções de imagem compartilhadas entre páginas:
   - Carrega imagens do backend (GET /imagens)
   - Renderiza seletor visual de imagens
================================================================ */

const IMG_FALLBACK = "https://images.unsplash.com/photo-1477959858617-67f85cf4f1df?w=800&q=75";

const IMAGENS_DEFAULT = [
  { idImagem: 1,  chave: "cidade",      nome: "Cidade",       emoji: "🏙️", url: "https://images.unsplash.com/photo-1477959858617-67f85cf4f1df?w=800&q=75" },
  { idImagem: 2,  chave: "praia",       nome: "Praia",        emoji: "🏖️", url: "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=800&q=75" },
  { idImagem: 3,  chave: "natureza",    nome: "Natureza",     emoji: "🌿", url: "https://images.unsplash.com/photo-1441974231531-c6227db76b6e?w=800&q=75" },
  { idImagem: 4,  chave: "montanha",    nome: "Montanha",     emoji: "🏔️", url: "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=800&q=75" },
  { idImagem: 5,  chave: "aventura",    nome: "Aventura",     emoji: "🧗", url: "https://images.unsplash.com/photo-1501555088652-021faa106b9b?w=800&q=75" },
  { idImagem: 6,  chave: "cultural",    nome: "Cultural",     emoji: "🏛️", url: "https://images.unsplash.com/photo-1533929736458-ca588d08c8be?w=800&q=75" },
  { idImagem: 7,  chave: "gastronomia", nome: "Gastronomia",  emoji: "🍽️", url: "https://images.unsplash.com/photo-1414235077428-338989a2e8c0?w=800&q=75" },
  { idImagem: 8,  chave: "luxo",        nome: "Luxo",         emoji: "✨", url: "https://images.unsplash.com/photo-1566073771259-6a8506099945?w=800&q=75" },
  { idImagem: 9,  chave: "neve",        nome: "Neve / Frio",  emoji: "❄️", url: "https://images.unsplash.com/photo-1491002052546-bf38f186af56?w=800&q=75" },
  { idImagem: 10, chave: "mochilao",    nome: "Mochilão",     emoji: "🎒", url: "https://images.unsplash.com/photo-1527631746610-bca00a040d60?w=800&q=75" },
  { idImagem: 11, chave: "deserto",    nome: "Deserto",      emoji: "🏜️", url: "https://images.unsplash.com/photo-1509316785289-025f5b846b35?w=800&q=75" },
  { idImagem: 12, chave: "fazenda",    nome: "Fazenda",      emoji: "🌾", url: "https://images.unsplash.com/photo-1500382017468-9049fed747ef?w=800&q=75" },
  { idImagem: 13, chave: "cruzeiro",   nome: "Cruzeiro",     emoji: "🚢", url: "https://images.unsplash.com/photo-1548574505-5e239809ee19?w=800&q=75" },
  { idImagem: 14, chave: "festival",   nome: "Festival",     emoji: "🎪", url: "https://images.unsplash.com/photo-1506157786151-b8491531f063?w=800&q=75" },
  { idImagem: 15, chave: "spa",        nome: "Spa",          emoji: "💆", url: "https://images.unsplash.com/photo-1540555700478-4be289fbecef?w=800&q=75" },
];

// Pré-populado para renderizar imediatamente sem esperar o backend
let imagensCache = [...IMAGENS_DEFAULT];

// Renderiza o seletor assim que o DOM estiver pronto (sem esperar fetch)
document.addEventListener("DOMContentLoaded", () => {
  const container = document.getElementById("imgSelector");
  if (container) renderSeletorImagens("imgSelector", "itImagem", IMAGENS_DEFAULT[0].idImagem);
});

function carregarImagens() {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);

  return fetch("https://tcc-2025-1-e-2-flyguide-production.up.railway.app/imagens", { signal: controller.signal })
    .then(r => r.json())
    .then(data => {
      clearTimeout(timeout);
      if (Array.isArray(data) && data.length > 0) {
        const backendChaves = new Set(data.map(img => img.chave));
        const extras = IMAGENS_DEFAULT.filter(img => !backendChaves.has(img.chave));
        imagensCache = [...data, ...extras];
      } else {
        imagensCache = IMAGENS_DEFAULT;
      }
      return imagensCache;
    })
    .catch(() => {
      clearTimeout(timeout);
      imagensCache = IMAGENS_DEFAULT;
      return IMAGENS_DEFAULT;
    });
}

function renderSeletorImagens(containerId, hiddenId, idSelecionado) {
  const container = document.getElementById(containerId);
  if (!container || imagensCache.length === 0) return;

  container.innerHTML = imagensCache.map(img => `
    <div class="img-option ${img.idImagem === idSelecionado ? "selected" : ""}"
         data-id="${img.idImagem}"
         data-chave="${img.chave}"
         style="position:relative;border-radius:14px;overflow:hidden;cursor:pointer;
                border:3px solid ${img.idImagem === idSelecionado ? "#f97316" : "transparent"};
                transition:border-color .2s,transform .15s;aspect-ratio:16/9;">
      <img src="${img.url.replace("w=800", "w=300")}" alt="${img.nome}"
           style="width:100%;height:100%;object-fit:cover;display:block;">
      <div class="chk-icon"
           style="position:absolute;top:8px;right:8px;background:#f97316;color:#fff;
                  border-radius:50%;width:22px;height:22px;
                  display:${img.idImagem === idSelecionado ? "flex" : "none"};
                  align-items:center;justify-content:center;font-size:.75rem;">
        <i class="bi bi-check"></i>
      </div>
    </div>`).join("");

  container.querySelectorAll(".img-option").forEach(opt => {
    opt.addEventListener("click", () => {
      container.querySelectorAll(".img-option").forEach(o => {
        o.style.borderColor = "transparent";
        o.style.boxShadow   = "";
        o.classList.remove("selected");
        const chk = o.querySelector(".chk-icon");
        if (chk) chk.style.display = "none";
      });
      opt.style.borderColor = "#f97316";
      opt.style.boxShadow   = "0 0 0 3px rgba(249,115,22,.25)";
      opt.classList.add("selected");
      const chk = opt.querySelector(".chk-icon");
      if (chk) chk.style.display = "flex";
      const hidden = document.getElementById(hiddenId);
      if (hidden) hidden.value = opt.getAttribute("data-id");
    });
  });
}



