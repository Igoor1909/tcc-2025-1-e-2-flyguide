/* ================================================================
   FlyGuide - editar-roteiro.js
   Carrega o roteiro existente e abre o editor de locais
   usando a mesma infra de maps-edit.js do passo 3 de criar-roteiro.
================================================================ */
(function () {
  const URL_API   = "https://tcc-2025-1-e-2-flyguide-production.up.railway.app";
  const params    = new URLSearchParams(window.location.search);
  const roteiroId = params.get("id");

  if (!roteiroId) { window.location.href = "meus-roteiros.html"; return; }

  const userId = getUserIdFromToken();
  if (!userId) { window.location.href = "login.html"; return; }

  // Carrega roteiro da API
  authFetch(URL_API + "/roteiros/" + roteiroId)
    .then(function (r) { return r.json(); })
    .then(function (roteiro) {
      // Preenche o card de destino
      var destEl  = document.getElementById("p3Destino");
      var infoEl  = document.getElementById("p3Info");
      if (destEl) destEl.textContent = [roteiro.cidade, roteiro.pais].filter(Boolean).join(", ") + (roteiro.cidade || roteiro.pais ? "" : "—");
      if (infoEl) infoEl.textContent = (roteiro.diasTotais ? roteiro.diasTotais + " dia" + (roteiro.diasTotais > 1 ? "s" : "") : "") + (roteiro.tipoRoteiro ? " • " + roteiro.tipoRoteiro : "");

      // Mostra conteúdo
      document.getElementById("erLoading").style.display  = "none";
      document.getElementById("erConteudo").style.display = "";

      // Abre o editor de locais (mesmo mecanismo do modal de edição em meus-roteiros)
      var optsLocais = {
        diasTotais:              roteiro.diasTotais  || 0,
        userId:                  parseInt(userId),
        roteiro:                 roteiro,
        pais:                    roteiro.pais        || null,
        stateCode:               roteiro.stateCode   || null,
        latDestino:              roteiro.latDestino  || null,
        lngDestino:              roteiro.lngDestino  || null,
        ocultarBtnSalvarSugestoes: true,
      };
      if (typeof window.abrirLocaisEdit === "function") {
        window.abrirLocaisEdit(roteiroId, roteiro.cidade, optsLocais);
      } else {
        // maps-edit.js ainda não carregou — aguarda
        var _check = setInterval(function () {
          if (typeof window.abrirLocaisEdit === "function") {
            clearInterval(_check);
            window.abrirLocaisEdit(roteiroId, roteiro.cidade, optsLocais);
          }
        }, 100);
      }

      // Botão "Salvar Roteiro" — apenas redireciona (locais já são salvos em tempo real pelo maps-edit.js)
      var btnSalvar = document.getElementById("btnSalvarRoteiro");
      if (btnSalvar) {
        btnSalvar.addEventListener("click", function () {
          btnSalvar.disabled  = true;
          btnSalvar.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Salvando...';
          // Os locais já foram persistidos pelo maps-edit.js — apenas redireciona
          setTimeout(function () {
            window.location.href = "meus-roteiros.html";
          }, 600);
        });
      }
    })
    .catch(function () {
      document.getElementById("erLoading").style.display = "none";
      document.getElementById("erErro").style.display   = "";
    });
})();
