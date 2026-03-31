(function (global) {
  "use strict";

  var _gatewayUrl = "";
  var _refreshing = null;
  var _sessionExpiredCb = null;

  function refreshToken() {
    if (_refreshing) return _refreshing;
    _refreshing = fetch(_gatewayUrl + "/auth/refresh", {
      method: "POST",
      credentials: "same-origin",
      headers: { Accept: "application/json", "X-Requested-With": "XMLHttpRequest" },
    })
      .then(function (res) {
        if (!res.ok) throw new Error("refresh_failed");
        return res.json();
      })
      .finally(function () {
        _refreshing = null;
      });
    return _refreshing;
  }

  function voltaFetch(url, options) {
    options = options || {};
    options.credentials = options.credentials || "same-origin";
    options.headers = options.headers || {};
    options.headers.Accept = options.headers.Accept || "application/json";
    options.headers["X-Requested-With"] = "XMLHttpRequest";

    return fetch(url, options).then(function (res) {
      if (res.status !== 401) return res;
      if (options._retried) {
        var returnTo = encodeURIComponent(window.location.href);
        if (_sessionExpiredCb) _sessionExpiredCb();
        else window.location.href = _gatewayUrl + "/login?return_to=" + returnTo;
        throw new Error("session_expired");
      }
      return refreshToken()
        .then(function () {
          options._retried = true;
          return fetch(url, options);
        })
        .catch(function () {
          var returnTo = encodeURIComponent(window.location.href);
          if (_sessionExpiredCb) _sessionExpiredCb();
          else window.location.href = _gatewayUrl + "/login?return_to=" + returnTo;
          throw new Error("session_expired");
        });
    });
  }

  function switchTenant(tenantId) {
    return voltaFetch(_gatewayUrl + "/auth/switch-tenant", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tenantId: tenantId }),
    }).then(function (res) {
      if (res.ok) window.location.reload();
      return res;
    });
  }

  function logout() {
    return fetch(_gatewayUrl + "/auth/logout", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-Requested-With": "XMLHttpRequest", Accept: "application/json" },
    }).then(function () {
      window.location.href = _gatewayUrl + "/login";
    });
  }

  function getSession() {
    return voltaFetch(_gatewayUrl + "/auth/refresh", { method: "POST" }).then(function (res) {
      return res.json();
    });
  }

  function revokeSession(sessionId) {
    return voltaFetch(_gatewayUrl + "/auth/sessions/" + sessionId, { method: "DELETE" });
  }

  function revokeAllSessions() {
    return voltaFetch(_gatewayUrl + "/auth/sessions/revoke-all", { method: "POST" });
  }

  function json(res) {
    return res.json().catch(function () {
      return {};
    });
  }

  function initCallback() {
    var data = document.getElementById("callback-data");
    if (!data) return;
    var status = document.getElementById("callback-status");
    voltaFetch(_gatewayUrl + "/auth/callback/complete", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: data.dataset.code || "", state: data.dataset.state || "" }),
    })
      .then(json)
      .then(function (payload) {
        status.textContent = "完了しました。リダイレクトします...";
        window.location.replace(payload.redirect_to || "/select-tenant");
      })
      .catch(function () {
        status.textContent = "認証に失敗しました。再ログインします。";
        setTimeout(function () {
          window.location.href = _gatewayUrl + "/login";
        }, 1000);
      });
  }

  function initTenantSelect() {
    if (!document.getElementById("tenant-list")) return;
    document.querySelectorAll(".tenant-card").forEach(function (el) {
      el.addEventListener("click", function () {
        switchTenant(el.dataset.tenantId);
      });
    });
    var search = document.getElementById("tenant-search");
    if (search) {
      search.addEventListener("input", function () {
        var q = search.value.toLowerCase();
        document.querySelectorAll(".tenant-card").forEach(function (card) {
          card.style.display = card.textContent.toLowerCase().indexOf(q) >= 0 ? "" : "none";
        });
      });
    }
  }

  function initSessionPage() {
    var revokeAllBtn = document.getElementById("revoke-all");
    if (revokeAllBtn) {
      revokeAllBtn.addEventListener("click", function () {
        revokeAllSessions().then(function () {
          window.location.reload();
        });
      });
    }
    document.querySelectorAll("[data-revoke-id]").forEach(function (el) {
      el.addEventListener("click", function () {
        revokeSession(el.dataset.revokeId).then(function () {
          window.location.reload();
        });
      });
    });
  }

  function initAdminPages() {
    if (global.__voltaMembers) {
      var tid = global.__voltaMembers.tenantId;
      document.querySelectorAll(".member-role").forEach(function (sel) {
        sel.addEventListener("change", function () {
          voltaFetch(_gatewayUrl + "/api/v1/tenants/" + tid + "/members/" + sel.dataset.memberId, {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ role: sel.value }),
          }).then(function () { window.location.reload(); });
        });
      });
      document.querySelectorAll(".member-remove").forEach(function (btn) {
        btn.addEventListener("click", function () {
          if (!window.confirm("このメンバーを削除しますか？")) return;
          voltaFetch(_gatewayUrl + "/api/v1/tenants/" + tid + "/members/" + btn.dataset.memberId, { method: "DELETE" })
            .then(function () { window.location.reload(); });
        });
      });
    }
    if (global.__voltaInvitations) {
      var itid = global.__voltaInvitations.tenantId;
      document.querySelectorAll(".invite-cancel").forEach(function (btn) {
        btn.addEventListener("click", function () {
          voltaFetch(_gatewayUrl + "/api/v1/tenants/" + itid + "/invitations/" + btn.dataset.invitationId, { method: "DELETE" })
            .then(function () { window.location.reload(); });
        });
      });
      var form = document.getElementById("invite-create-form");
      if (form) {
        form.addEventListener("submit", function (e) {
          e.preventDefault();
          var body = {
            email: form.email.value || null,
            role: form.role.value,
          };
          voltaFetch(_gatewayUrl + "/api/v1/tenants/" + itid + "/invitations", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          }).then(json).then(function (payload) {
            var line = document.getElementById("invite-result");
            line.textContent = payload.link || "";
          });
        });
      }
    }
  }

  global.Volta = {
    init: function (opts) {
      _gatewayUrl = (opts && opts.gatewayUrl) || "";
    },
    fetch: voltaFetch,
    switchTenant: switchTenant,
    logout: logout,
    getSession: getSession,
    revokeSession: revokeSession,
    revokeAllSessions: revokeAllSessions,
    onSessionExpired: function (cb) {
      _sessionExpiredCb = cb;
    },
  };

  global.Volta.init({ gatewayUrl: "" });
  initCallback();
  initTenantSelect();
  initSessionPage();
  initAdminPages();
})(window);
