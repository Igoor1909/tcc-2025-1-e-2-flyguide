/* ================================================================
   FlyGuide - editar-roteiro.js
   Página de edição completa do roteiro:
   - Mapa com filtro por dia
   - Lista dia a dia com drag-and-drop, editar e excluir por local
   - Formulário de informações básicas + salvar
================================================================ */
(function () {
  const URL_API = "https://tcc-2025-1-e-2-flyguide-production.up.railway.app";
  const params    = new URLSearchParams(window.location.search);
  const roteiroId = params.get("id");

  if (!roteiroId) { window.location.href = "meus-roteiros.html"; return; }

  const userId = getUserIdFromToken();
  if (!userId) { window.location.href = "login.html"; return; }

  let roteiro  = null;
  let locais   = [];
  let mapaObj  = null;
  let marcadores = [];
  let diaFiltro  = "todos";
  let modalLocal = null;

  const PERIODOS = [
    { key: "manha", label: "Manhã",  icon: "bi-sunrise-fill",   cor: "#f59e0b" },
    { key: "tarde", label: "Tarde",  icon: "bi-sun-fill",        cor: "#f97316" },
    { key: "noite", label: "Noite",  icon: "bi-moon-stars-fill", cor: "#6366f1" },
  ];

  const CORES_DIAS = [
    "#f97316","#3b82f6","#22c55e","#a855f7",
    "#ef4444","#0891b2","#84cc16","#f59e0b",
  ];

  function horarioParaPeriodo(h) {
    if (!h) return "manha";
    const s = String(h).slice(0, 5);
    if (s < "12:00") return "manha";
    if (s < "18:00") return "tarde";
    return "noite";
  }

  function periodoParaHorario(p) {
    return { manha: "09:00", tarde: "14:00", noite: "19:00" }[p] || "09:00";
  }

  function corDia(dia) {
    return CORES_DIAS[((Number(dia) - 1) % CORES_DIAS.length)] || "#f97316";
  }

  // ── Carregar dados ────────────────────────────────────────────
  Promise.all([
    authFetch(`${URL_API}/roteiros/${roteiroId}`).then(r => r.json()),
    authFetch(`${URL_API}/roteiros/${roteiroId}/locais`).then(r => r.json()),
  ])
    .then(function ([r, ls]) {
      roteiro = r;
      locais  = Array.isArray(ls) ? ls : [];

      renderHero();
      renderFiltrosDias();
      renderListaDias();
      renderInfoForm();

      document.getElementById("erLoading").style.display  = "none";
      document.getElementById("erConteudo").style.display = "";

      if (locais.length > 0) {
        document.getElementById("erSecaoMapa").style.display = "";
      }
      if (window._erMapaReady) renderMapa();

      modalLocal = new bootstrap.Modal(document.getElementById("erModalLocal"));
      configurarModalLocal();
      configurarSalvar();
    })
    .catch(function () {
      document.getElementById("erLoading").style.display = "none";
      document.getElementById("erErro").style.display    = "";
    });

  // ── Hero ──────────────────────────────────────────────────────
  function renderHero() {
    document.getElementById("erTitulo").textContent = roteiro.titulo || "—";
    document.getElementById("erCidade").textContent = roteiro.cidade || "—";
    document.getElementById("erTipo").textContent   = roteiro.tipoRoteiro || "Viagem";
    document.getElementById("erVis").textContent    = roteiro.visibilidadeRoteiro === "Público" ? "Público" : "Privado";

    const hero = document.getElementById("erHero");
    if (roteiro.imagemUrl && hero) {
      hero.style.backgroundImage = "url('" + roteiro.imagemUrl + "')";
    }
  }

  // ── Filtros por dia ───────────────────────────────────────────
  function renderFiltrosDias() {
    const dias = [...new Set(locais.map(l => Number(l.dia || 0)).filter(d => d > 0))].sort((a, b) => a - b);
    const el   = document.getElementById("erFiltrosDias");
    if (!el) return;

    el.innerHTML = [
      `<button onclick="window._erFiltrarDia('todos')"
               style="border:none;border-radius:999px;padding:5px 14px;font-size:.82rem;font-weight:700;cursor:pointer;
                      background:${diaFiltro === 'todos' ? '#f97316' : '#f1f5f9'};
                      color:${diaFiltro === 'todos' ? '#fff' : '#64748b'};">
         Todos
       </button>`,
      ...dias.map(d => {
        const ativo = String(diaFiltro) === String(d);
        const cor   = corDia(d);
        return `<button onclick="window._erFiltrarDia(${d})"
                        style="border:none;border-radius:999px;padding:5px 14px;font-size:.82rem;font-weight:700;cursor:pointer;
                               background:${ativo ? cor : '#f1f5f9'};
                               color:${ativo ? '#fff' : '#64748b'};">
                  Dia ${d}
                </button>`;
      }),
    ].join("");
  }

  window._erFiltrarDia = function (dia) {
    diaFiltro = dia;
    renderFiltrosDias();
    renderListaDias();
    if (mapaObj) renderMapa();
  };

  // ── Lista Dia a Dia ──────────────────────────────────────────
  function renderListaDias() {
    const container = document.getElementById("erListaDias");
    if (!container) return;

    if (locais.length === 0) {
      container.innerHTML = '<div class="text-center text-secondary py-4"><i class="bi bi-map" style="font-size:2rem;color:#cbd5e1;display:block;margin-bottom:8px;"></i>Nenhum local adicionado.</div>';
      return;
    }

    const diasSet   = [...new Set(locais.map(l => Number(l.dia || 0)))].sort((a, b) => a - b);
    const diasFiltrados = diaFiltro === "todos" ? diasSet : [Number(diaFiltro)].filter(d => diasSet.includes(d));

    container.innerHTML = diasFiltrados.map(function (dia) {
      const locaisDia = locais.filter(l => Number(l.dia || 0) === dia);
      const cor       = corDia(dia);

      // Agrupar por período
      const grupos = { manha: [], tarde: [], noite: [] };
      locaisDia.forEach(function (l) {
        const per = horarioParaPeriodo(l.horario);
        grupos[per].push(l);
      });

      const periodosHtml = PERIODOS.map(function (pc) {
        const items = grupos[pc.key] || [];
        if (!items.length) return "";

        const itensHtml = items.map(function (l, idx) {
          return `
            <div class="er-item" data-id="${l.idLocal}" data-dia="${dia}" data-periodo="${pc.key}" draggable="true">
              <span class="er-drag-handle"><i class="bi bi-grip-vertical"></i></span>
              <span class="er-num" style="background:${pc.cor}20;color:${pc.cor};">${idx + 1}</span>
              <div class="er-info">
                <div class="nome">${escapeHtml(l.nome || "Local")}</div>
                ${l.endereco ? `<div class="end"><i class="bi bi-geo-alt me-1" style="color:#94a3b8;"></i>${escapeHtml(l.endereco)}</div>` : ""}
                ${l.observacoes ? `<div class="obs"><i class="bi bi-pencil-fill me-1" style="color:#cbd5e1;"></i>${escapeHtml(l.observacoes)}</div>` : ""}
              </div>
              <div class="er-btns">
                <button class="er-btn-edit"   onclick="window._erAbrirLocal('${l.idLocal}')" title="Editar"><i class="bi bi-pencil"></i></button>
                <button class="er-btn-delete" onclick="window._erExcluirLocal('${l.idLocal}', '${escapeHtml(l.nome || 'este local')}')" title="Excluir"><i class="bi bi-trash3"></i></button>
              </div>
            </div>`;
        }).join("");

        return `
          <div style="margin-bottom:6px;">
            <div class="er-periodo-header">
              <i class="bi ${pc.icon}" style="color:${pc.cor};font-size:.85rem;"></i>
              <span style="color:${pc.cor};font-weight:700;font-size:.8rem;">${pc.label}</span>
              <span style="font-size:.72rem;color:${pc.cor};opacity:.7;">${items.length} ${items.length === 1 ? "local" : "locais"}</span>
            </div>
            <div class="er-drop-zone" data-drop-zona data-dia="${dia}" data-periodo="${pc.key}">
              ${itensHtml}
            </div>
          </div>`;
      }).join("");

      return `
        <div style="margin-bottom:24px;">
          <div class="er-dia-header">
            <span class="er-dia-dot" style="background:${cor};"></span>
            <span class="fw-bold">Dia ${dia}</span>
            <span class="text-secondary" style="font-size:.82rem;">${locaisDia.length} ${locaisDia.length === 1 ? "local" : "locais"}</span>
          </div>
          ${periodosHtml || '<div class="text-secondary py-2 px-2" style="font-size:.85rem;">Sem locais neste dia.</div>'}
        </div>`;
    }).join("");

    initDragDrop();
  }

  // ── Drag & Drop ───────────────────────────────────────────────
  function initDragDrop() {
    let dragging = null;

    document.querySelectorAll(".er-item").forEach(function (item) {
      item.addEventListener("dragstart", function (e) {
        dragging = item;
        setTimeout(function () { item.classList.add("dragging"); }, 0);
        e.dataTransfer.effectAllowed = "move";
      });

      item.addEventListener("dragend", function () {
        item.classList.remove("dragging");
        document.querySelectorAll(".er-item.drag-over").forEach(function (el) { el.classList.remove("drag-over"); });
        dragging = null;
        salvarOrdem();
      });

      item.addEventListener("dragover", function (e) {
        e.preventDefault();
        if (!dragging || dragging === item) return;
        item.classList.add("drag-over");
        const rect  = item.getBoundingClientRect();
        const after = e.clientY > rect.top + rect.height / 2;
        const zona  = item.closest("[data-drop-zona]");
        if (!zona) return;
        if (after) item.after(dragging);
        else       item.before(dragging);
      });

      item.addEventListener("dragleave", function () {
        item.classList.remove("drag-over");
      });

      item.addEventListener("drop", function (e) {
        e.preventDefault();
        item.classList.remove("drag-over");
      });
    });

    // Permitir drop em zonas vazias
    document.querySelectorAll("[data-drop-zona]").forEach(function (zona) {
      zona.addEventListener("dragover", function (e) { e.preventDefault(); });
      zona.addEventListener("drop",     function (e) { e.preventDefault(); });
    });
  }

  function salvarOrdem() {
    document.querySelectorAll("[data-drop-zona]").forEach(function (zona) {
      const dia    = Number(zona.dataset.dia);
      const per    = zona.dataset.periodo;
      zona.querySelectorAll(".er-item").forEach(function (item, idx) {
        const id    = item.dataset.id;
        const local = locais.find(function (l) { return String(l.idLocal) === String(id); });
        if (!local) return;

        const novoPer = per;
        const novoH   = periodoParaHorario(novoPer);
        local.dia     = dia;
        local.horario = novoH;
        local.ordem   = idx + 1;

        item.dataset.dia     = dia;
        item.dataset.periodo = novoPer;

        authFetch(`${URL_API}/roteiros/${roteiroId}/locais/${id}`, {
          method:  "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            idLocal:     Number(id),
            status:      local.status      || "PENDENTE",
            observacoes: local.observacoes || null,
            dia:         dia,
            ordem:       idx + 1,
            horario:     novoH,
          }),
        }).catch(function () {});
      });
    });

    // Renumerar badges sem re-renderizar tudo
    document.querySelectorAll("[data-drop-zona]").forEach(function (zona) {
      zona.querySelectorAll(".er-num").forEach(function (badge, idx) {
        badge.textContent = idx + 1;
      });
    });
  }

  // ── Editar Local (modal) ──────────────────────────────────────
  window._erAbrirLocal = function (idLocal) {
    const local = locais.find(function (l) { return String(l.idLocal) === String(idLocal); });
    if (!local) return;

    document.getElementById("erLocalId").value      = idLocal;
    document.getElementById("erLocalNome").textContent    = local.nome || "";
    document.getElementById("erLocalEndereco").textContent = local.endereco || "";
    document.getElementById("erLocalObs").value     = local.observacoes || "";
    document.getElementById("erLocalPeriodo").value = horarioParaPeriodo(local.horario);
    document.getElementById("erLocalErro").style.display = "none";

    // Preencher select de dias
    const diasSet = [...new Set(locais.map(function (l) { return Number(l.dia || 0); }).filter(function (d) { return d > 0; }))].sort(function (a, b) { return a - b; });
    const diaEl = document.getElementById("erLocalDia");
    diaEl.innerHTML = diasSet.map(function (d) {
      return `<option value="${d}" ${Number(local.dia) === d ? "selected" : ""}>Dia ${d}</option>`;
    }).join("");

    if (modalLocal) modalLocal.show();
  };

  function configurarModalLocal() {
    document.getElementById("btnErSalvarLocal")?.addEventListener("click", async function () {
      const id  = document.getElementById("erLocalId").value;
      const obs = document.getElementById("erLocalObs").value.trim();
      const per = document.getElementById("erLocalPeriodo").value;
      const dia = parseInt(document.getElementById("erLocalDia").value);
      const erroEl = document.getElementById("erLocalErro");

      const local = locais.find(function (l) { return String(l.idLocal) === String(id); });
      if (!local) return;

      const btn = document.getElementById("btnErSalvarLocal");
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Salvando...';
      erroEl.style.display = "none";

      try {
        const res = await authFetch(`${URL_API}/roteiros/${roteiroId}/locais/${id}`, {
          method:  "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            idLocal:     Number(id),
            status:      local.status || "PENDENTE",
            observacoes: obs || null,
            dia:         dia,
            ordem:       local.ordem  || 1,
            horario:     periodoParaHorario(per),
          }),
        });

        if (!res.ok) throw new Error();

        local.observacoes = obs || null;
        local.dia         = dia;
        local.horario     = periodoParaHorario(per);

        modalLocal.hide();
        renderFiltrosDias();
        renderListaDias();
        if (mapaObj) renderMapa();
      } catch {
        erroEl.textContent   = "Erro ao salvar. Tente novamente.";
        erroEl.style.display = "";
      } finally {
        btn.disabled  = false;
        btn.innerHTML = '<i class="bi bi-check-lg me-1"></i>Salvar';
      }
    });
  }

  // ── Excluir Local ─────────────────────────────────────────────
  window._erExcluirLocal = async function (idLocal, nome) {
    if (!confirm(`Excluir "${nome}" do roteiro?`)) return;

    try {
      await authFetch(`${URL_API}/roteiros/${roteiroId}/locais/${idLocal}`, { method: "DELETE" });
      locais = locais.filter(function (l) { return String(l.idLocal) !== String(idLocal); });
      renderFiltrosDias();
      renderListaDias();
      if (mapaObj) renderMapa();
      if (locais.length === 0) document.getElementById("erSecaoMapa").style.display = "none";
    } catch {
      alert("Não foi possível excluir. Tente novamente.");
    }
  };

  // ── Formulário de Informações ─────────────────────────────────
  function renderInfoForm() {
    document.getElementById("erInfoTitulo").value    = roteiro.titulo      || "";
    document.getElementById("erInfoCidade").value    = roteiro.cidade      || "";
    document.getElementById("erInfoDuracao").value   = roteiro.diasTotais  || "";
    document.getElementById("erInfoDescricao").value = roteiro.observacoes || "";
    document.getElementById("erInfoTipo").value      = roteiro.tipoRoteiro || "Cidade";
  }

  function configurarSalvar() {
    document.getElementById("btnErSalvar")?.addEventListener("click", async function () {
      const titulo = document.getElementById("erInfoTitulo").value.trim();
      const erroEl = document.getElementById("erSalvarErro");
      erroEl.style.display = "none";

      if (!titulo) {
        erroEl.textContent   = "O título é obrigatório.";
        erroEl.style.display = "";
        return;
      }

      const btn = document.getElementById("btnErSalvar");
      btn.disabled  = true;
      btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Salvando...';

      try {
        const res = await authFetch(`${URL_API}/roteiros/${roteiroId}`, {
          method:  "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            idRoteiro:           Number(roteiroId),
            idUsuario:           parseInt(userId),
            titulo:              titulo,
            pais:                roteiro.pais               || "",
            cidade:              document.getElementById("erInfoCidade").value.trim()  || roteiro.cidade,
            diasTotais:          parseInt(document.getElementById("erInfoDuracao").value) || roteiro.diasTotais,
            observacoes:         document.getElementById("erInfoDescricao").value.trim() || null,
            tipoRoteiro:         document.getElementById("erInfoTipo").value,
            statusRoteiro:       roteiro.statusRoteiro      || "PLANEJADO",
            visibilidadeRoteiro: roteiro.visibilidadeRoteiro || "Público",
            imagemUrl:           roteiro.imagemUrl          || null,
            sugestoes:           roteiro.sugestoes          || null,
          }),
        });

        if (!res.ok) throw new Error();

        btn.innerHTML    = '<i class="bi bi-check-circle-fill me-2"></i>Salvo com sucesso!';
        btn.style.background = "#22c55e";
        setTimeout(function () { window.location.href = "meus-roteiros.html"; }, 1200);
      } catch {
        erroEl.textContent   = "Erro ao salvar. Tente novamente.";
        erroEl.style.display = "";
        btn.disabled         = false;
        btn.innerHTML        = '<i class="bi bi-check-circle-fill me-2"></i>Salvar Roteiro';
        btn.style.background = "";
      }
    });
  }

  // ── Mapa ──────────────────────────────────────────────────────
  window._erMapaReady = false;

  window.initMapaEditRoteiro = function () {
    window._erMapaReady = true;
    if (roteiro && locais) renderMapa();
  };

  function renderMapa() {
    if (!window.google || !window.google.maps) return;

    const filtrados  = diaFiltro === "todos" ? locais : locais.filter(function (l) { return String(l.dia) === String(diaFiltro); });
    const comCoords  = filtrados.filter(function (l) { return l.latitude && l.longitude; });

    if (!mapaObj) {
      const centro = comCoords.length > 0
        ? { lat: parseFloat(comCoords[0].latitude), lng: parseFloat(comCoords[0].longitude) }
        : { lat: -15.78, lng: -47.93 };

      mapaObj = new google.maps.Map(document.getElementById("erMapa"), {
        center: centro,
        zoom:   12,
      });
    }

    marcadores.forEach(function (m) { m.setMap(null); });
    marcadores = [];

    comCoords.forEach(function (l, idx) {
      const cor = corDia(l.dia || 1);
      const marker = new google.maps.Marker({
        position: { lat: parseFloat(l.latitude), lng: parseFloat(l.longitude) },
        map:      mapaObj,
        title:    l.nome,
        label: {
          text:       String(idx + 1),
          color:      "#fff",
          fontWeight: "700",
          fontSize:   "11px",
        },
        icon: {
          path:          google.maps.SymbolPath.CIRCLE,
          scale:         14,
          fillColor:     cor,
          fillOpacity:   1,
          strokeColor:   "#fff",
          strokeWeight:  2,
        },
      });

      const info = new google.maps.InfoWindow({
        content: `<div style="font-family:Inter,sans-serif;min-width:160px;">
          <div style="font-weight:700;font-size:.9rem;margin-bottom:2px;">${escapeHtml(l.nome || "Local")}</div>
          ${l.endereco ? `<div style="font-size:.78rem;color:#64748b;">${escapeHtml(l.endereco)}</div>` : ""}
          <div style="font-size:.75rem;color:#94a3b8;margin-top:4px;">Dia ${l.dia}</div>
        </div>`,
      });

      marker.addListener("click", function () { info.open(mapaObj, marker); });
      marcadores.push(marker);
    });

    if (comCoords.length > 1) {
      const bounds = new google.maps.LatLngBounds();
      comCoords.forEach(function (l) { bounds.extend({ lat: parseFloat(l.latitude), lng: parseFloat(l.longitude) }); });
      mapaObj.fitBounds(bounds);
    } else if (comCoords.length === 1) {
      mapaObj.setCenter({ lat: parseFloat(comCoords[0].latitude), lng: parseFloat(comCoords[0].longitude) });
      mapaObj.setZoom(14);
    }
  }
})();
