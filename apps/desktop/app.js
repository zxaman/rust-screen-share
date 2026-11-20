const $ = (s) => document.querySelector(s),
  I = (n) =>
    `<svg class="ic" aria-hidden="true"><use href="assets/icons.svg#${n}"/></svg>`,
  esc = (v) =>
    String(v).replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
document
  .querySelectorAll("[data-icon]")
  .forEach((el) => (el.outerHTML = I(el.dataset.icon)));
const seed = {
  devices: [
    {
      id: "think",
      name: "ThinkPad X1 Carbon",
      os: "Windows 11 Pro",
      type: "laptop",
      net: "Local Wi-Fi",
      online: true,
    },
    {
      id: "pixel",
      name: "Pixel 8 Pro",
      os: "Android 15",
      type: "phone",
      net: "Local Wi-Fi",
      online: true,
    },
    {
      id: "studio",
      name: "Studio Desktop",
      os: "Ubuntu 24.04",
      type: "desktop",
      net: "Ethernet",
      online: false,
    },
  ],
  sessions: [{ id: "think", caps: ["Screen viewing"], since: "10:42 AM" }],
  events: [
    {
      text: "Pixel 8 Pro connected",
      detail: "Local Wi-Fi",
      time: "2 min ago",
      icon: "wifi",
    },
    {
      text: "File transfer completed",
      detail: "Project-brief.pdf",
      time: "18 min ago",
      icon: "file-check-2",
    },
    {
      text: "Studio Desktop went offline",
      detail: "Ethernet",
      time: "1 hr ago",
      icon: "monitor",
    },
  ],
  transfers: [
    { name: "Project-brief.pdf", device: "Pixel 8 Pro", size: "2.4 MB" },
  ],
};
const nearby = [
  ...seed.devices.filter((d) => d.online),
  {
    id: "air",
    name: "MacBook Air",
    os: "macOS Sequoia",
    type: "laptop",
    net: "Local Wi-Fi",
    online: true,
  },
  {
    id: "tab",
    name: "Galaxy Tab S9",
    os: "Android 15",
    type: "phone",
    net: "Local Wi-Fi",
    online: true,
  },
];
let state;
try {
  state =
    JSON.parse(localStorage.getItem("nodeport-v1")) || JSON.parse(JSON.stringify(seed));
} catch {
  state = JSON.parse(JSON.stringify(seed));
}
const groups = {
  Workspace: [
    ["Dashboard", "layout-dashboard"],
    ["My devices", "monitor-smartphone"],
    ["Nearby devices", "radar"],
  ],
  Management: [
    ["Active sessions", "radio"],
    ["File transfers", "arrow-left-right"],
    ["Device monitoring", "activity"],
    ["Connection history", "history"],
  ],
  Preferences: [["Security settings", "shield-check"]],
};
const pages = Object.values(groups)
  .flat()
  .map((a) => a[0]);
function currentPage() {
  return (
    pages.find((p) => encodeURIComponent(p) === location.hash.slice(1)) ||
    "Dashboard"
  );
}
let page = currentPage(),
  layout = "grid",
  filter = "All devices",
  filters = false,
  toastTimer;
const dev = (id) => state.devices.find((d) => d.id === id),
  session = (id) => state.sessions.find((s) => s.id === id),
  act = (a, label, cls = "btn", id = "") =>
    `<button class="${cls}" data-act="${a}" data-id="${esc(id)}">${label}</button>`,
  more = (a, label, id) =>
    `<button class="icon more" aria-label="${esc(label)}" data-act="${a}" data-id="${id}">${I("ellipsis")}</button>`;
function save() {
  localStorage.setItem("nodeport-v1", JSON.stringify(state));
}
function event(text, detail, icon) {
  state.events.unshift({
    text,
    detail,
    time: new Date().toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
    }),
    icon,
  });
  save();
}
function toast(t) {
  $("#toast").textContent = t;
  $("#toast").style.display = "block";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($("#toast").style.display = "none"), 3200);
}
function go(p) {
  location.hash = encodeURIComponent(p);
  page = p;
  filters = false;
  filter = "All devices";
  closeMenu();
  render();
}
function closeMenu() {
  document.body.classList.remove("open");
  $(".menu-overlay")?.remove();
}
function nav() {
  return Object.entries(groups)
    .map(
      ([g, arr]) =>
        `<div class="navhead">${g}</div>${arr.map(([p, i]) => act("nav", I(i) + p + (p === "Active sessions" ? `<em>${state.sessions.length}</em>` : ""), "navbtn " + (p === page ? "sel" : ""), p)).join("")}`,
    )
    .join("");
}
function title(t, desc, action = "", over = "Device workspace") {
  return `<div class="titleline"><div><div class="eyebrow">${over}</div><h1>${t}</h1><p>${desc}</p></div>${action}</div>`;
}
function banner() {
  return `<div class="banner"><div class="label"><span class="dot"></span> Network status</div><h2>Your network is online</h2><div class="metrics">${[
    [state.devices.length, "Paired devices"],
    [state.devices.filter((d) => d.online).length, "Online now"],
    [state.sessions.length, "Active sessions"],
  ]
    .map(
      ([n, l]) =>
        `<div class="metric"><strong>${String(n).padStart(2, "0")}</strong><small>${l}</small></div>`,
    )
    .join("")}</div></div>`;
}
function card(d) {
  return `<article class="card"><div class="visual ${d.type}"><span class="badge ${d.online ? "" : "off"}"><span class="dot"></span>${d.online ? "Online" : "Offline"}</span>${more("device", "Options for " + d.name, d.id)}<img src="assets/${d.type}.svg" alt="${d.type} illustration"></div><div class="details"><h3>${esc(d.name)}</h3><div class="subrow">${I(d.type === "phone" ? "smartphone" : d.type === "laptop" ? "laptop" : "monitor")} ${esc(d.os)}</div><div class="cardfoot"><div class="subrow">${I(d.net === "Ethernet" ? "cable" : "wifi")} ${d.net}</div>${act("device", `Manage ${I("arrow-up-right")}`, "link", d.id)}</div></div></article>`;
}
function devices() {
  const ds = state.devices.filter(
    (d) => filter === "All devices" || (filter === "Online") === d.online,
  );
  return `<section><div class="section"><h2>My devices <span>${state.devices.length}</span></h2><div class="actions">${act("filter", I("list-filter") + " " + (filter === "All devices" ? "Filter" : filter))}<div class="switch">${["grid", "list"].map((v, i) => `<button aria-label="${v} view" class="${layout === v ? "on" : ""}" data-act="layout" data-id="${v}">${I(i ? "list" : "layout-grid")}</button>`).join("")}</div></div></div>${filters ? `<div class="boardfilter">${["All devices", "Online", "Offline"].map((s) => act("setfilter", s, "btn " + (s === filter ? "on" : ""), s)).join("")}</div>` : ""}<div class="cards ${layout}">${ds.map(card).join("") || '<p class="empty">No matching devices.</p>'}</div></section>`;
}
function item(icon, color, t, sub, right = "") {
  return `<div class="item"><div class="tile ${color}">${I(icon)}</div><div class="text"><strong>${esc(t)}</strong><small>${esc(sub)}</small></div>${right}</div>`;
}
function sessions(full = false) {
  return (
    state.sessions
      .map((s) =>
        item(
          "screen-share",
          "purple",
          dev(s.id).name,
          s.caps.join(" · "),
          full
            ? act("end", "End session", "btn", s.id)
            : '<span class="badge"><span class="dot"></span>Active</span>',
        ),
      )
      .join("") || '<p class="empty">No active sessions.</p>'
  );
}
function events(n = 99) {
  return state.events
    .slice(0, n)
    .map((e, i) =>
      item(
        e.icon,
        i % 2 ? "blue" : "",
        e.text,
        e.detail,
        `<span class="time">${esc(e.time)}</span>`,
      ),
    )
    .join("");
}
function panel(t, i, content, to) {
  return `<section class="panel"><div class="panelhead"><h2>${I(i)} ${t}</h2>${act("nav", "View all " + I("arrow-right"), "link", to)}</div>${content}</section>`;
}
const add = () => act("add", I("plus") + " Add device", "primary");
function render() {
  if (!pages.includes(page)) page = "Dashboard";
  $("#crumb").textContent = page;
  $("#nav").innerHTML = nav();
  let h = "";
  if (page === "Dashboard")
    h =
      title(
        "Workspace overview",
        "A live overview of your devices, connections, and activity.",
        add(),
        "Command center",
      ) +
      banner() +
      devices() +
      `<div class="lower">${panel("Active sessions", "radio", sessions(), "Active sessions")}${panel("Recent activity", "history", events(2), "Connection history")}</div>`;
  if (page === "My devices")
    h =
      title(page, "Manage paired devices in your workspace.", add()) +
      `<div class="viewpane">${devices()}</div>`;
  if (page === "Nearby devices")
    h =
      title(
        page,
        "Available devices on the simulated local network.",
        act("scan", I("refresh-cw") + " Scan again"),
      ) + `<div class="surface">${available()}</div>`;
  if (page === "Active sessions")
    h =
      title(
        page,
        "Review granted capabilities and terminate sessions at any time.",
      ) + `<div class="surface">${sessions(true)}</div>`;
  if (page === "Connection history")
    h =
      title(page, "A local record of workspace activity.") +
      `<div class="surface">${events()}</div>`;
  if (page === "File transfers")
    h =
      title(page, "Review completed transfers in your demo workspace.") +
      `<div class="surface">${state.transfers.map((t) => item("file-check-2", "blue", t.name, t.device + " · " + t.size, '<span class="badge">Completed</span>')).join("")}</div>`;
  if (page === "Device monitoring")
    h =
      title(page, "Sample telemetry from your registered devices.") +
      `<div class="surface">${state.devices.map((d, i) => item("activity", "", d.name, d.online ? `CPU ${[18, 9, 12][i % 3]}% · Memory ${[46, 28, 34][i % 3]}%` : "Telemetry unavailable", `<span class="badge ${d.online ? "" : "off"}">${d.online ? "Online" : "Offline"}</span>`)).join("")}</div>`;
  if (page === "Security settings")
    h =
      title(page, "Review device grants and manage trusted peers.") +
      `<div class="surface"><h3>Session permissions</h3><p>Access is limited to the capabilities approved for each demo session.</p>${state.devices.map((d) => item("shield-check", "", d.name, session(d.id)?.caps.join(" · ") || "No active permissions", act("device", "Review", "btn", d.id))).join("")}</div>`;
  $("#main").innerHTML =
    h + '<div class="footnote">Nodeport / Local demo workspace</div>';
}
function available() {
  const arr = nearby.filter((d) => !dev(d.id));
  return (
    arr
      .map((d) =>
        item(
          d.type === "laptop" ? "laptop" : "tablet",
          "",
          d.name,
          d.os + " · " + d.net,
          act("pair", "Pair", "btn", d.id),
        ),
      )
      .join("") || '<p class="empty">All nearby demo devices are paired.</p>'
  );
}
function modal(t, body) {
  $("#modal").innerHTML =
    `<div class="panelhead" style="padding:0 0 12px"><h2 id="dialog-title">${esc(t)}</h2><button class="icon" aria-label="Close dialog" data-act="close">${I("x")}</button></div>${body}`;
  if (!$("#dialog").open) $("#dialog").showModal();
}
function device(id) {
  const d = dev(id);
  if (!d) return;
  modal(
    d.name,
    `<p>${esc(d.os)} · ${esc(d.net)} · ${d.online ? "Online" : "Offline"}</p><div class="notice">${session(id) ? "Active demo session: " + esc(session(id).caps.join(", ")) : d.online ? "No session is active. Select capabilities before starting access." : "This device is offline. Access is unavailable until it reconnects."}</div><div class="footeractions">${act("unpair", "Unpair device", "btn", id)}${d.online ? (session(id) ? act("end", "End session", "primary", id) : act("consent", "Start session", "primary", id)) : ""}</div>`,
  );
}
const capabilities = ["Screen viewing", "Device information", "File access"];
document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-act]");
  if (!b) return;
  const a = b.dataset.act,
    id = b.dataset.id,
    d = dev(id);
  switch (a) {
    case "menu":
      document.body.classList.add("open");
      document.body.insertAdjacentHTML(
        "beforeend",
        '<div class="menu-overlay" data-act="hide"></div>',
      );
      break;
    case "hide":
      closeMenu();
      break;
    case "nav":
      go(id);
      break;
    case "layout":
      layout = id;
      render();
      break;
    case "filter":
      filters = !filters;
      render();
      break;
    case "setfilter":
      filter = id;
      filters = false;
      render();
      break;
    case "device":
      device(id);
      break;
    case "add":
      modal(
        "Add a device",
        "<p>Choose a nearby device to pair with your demo workspace.</p>" +
          available(),
      );
      break;
    case "scan":
      render();
      toast(
        "Scan complete · " +
          nearby.filter((n) => !dev(n.id)).length +
          " nearby demo devices",
      );
      break;
    case "pair": {
      const n = nearby.find((v) => v.id === id);
      modal(
        "Confirm pairing",
        `<p>Add <strong>${n.name}</strong> to this local demo workspace? Pairing does not grant screen, input, or file access.</p><div class="footeractions">${act("close", "Cancel")}${act("paired", "Confirm pairing", "primary", id)}</div>`,
      );
      break;
    }
    case "paired": {
      const n = nearby.find((v) => v.id === id);
      if (n && !d) {
        state.devices.push({ ...n });
        event(n.name + " paired", "Local demo pairing", "link");
      }
      $("#dialog").close();
      render();
      toast("Device paired");
      break;
    }
    case "unpair":
      modal(
        "Remove trusted device?",
        `<p>Unpair ${esc(d.name)} and end its active session. All session grants will be revoked.</p><div class="footeractions">${act("close", "Cancel")}${act("remove", "Unpair", "primary", id)}</div>`,
      );
      break;
    case "remove":
      state.sessions = state.sessions.filter((s) => s.id !== id);
      state.devices = state.devices.filter((v) => v.id !== id);
      event(d.name + " unpaired", "Session grants revoked", "shield-check");
      $("#dialog").close();
      render();
      toast("Device unpaired");
      break;
    case "consent":
      modal(
        "Approve session",
        `<p>Choose the capabilities to grant <strong>${esc(d.name)}</strong> for this simulated session.</p>${capabilities.map((c) => `<label class="check"><input type="checkbox" value="${c}">${c}</label>`).join("")}<div class="footeractions">${act("close", "Cancel")}${act("start", "Approve access", "primary", id)}</div>`,
      );
      break;
    case "start": {
      let caps = [...document.querySelectorAll("#modal input:checked")].map(
        (i) => i.value,
      );
      if (!caps.length) {
        $("#modal input").focus();
        $("#modal input").setCustomValidity("Choose at least one capability.");
        $("#modal input").reportValidity();
        return;
      }
      if (d?.online && !session(id)) {
        state.sessions.push({
          id,
          caps,
          since: new Date().toLocaleTimeString(),
        });
        event(d.name + " session started", caps.join(", "), "radio");
      }
      render();
      device(id);
      break;
    }
    case "end":
      state.sessions = state.sessions.filter((s) => s.id !== id);
      event(d.name + " session ended", "Access revoked", "shield-check");
      $("#dialog").close();
      render();
      toast("Session ended · permissions revoked");
      break;
    case "alerts":
      modal("Workspace notifications", events(3));
      break;
    case "profile":
      modal(
        "Workspace profile",
        '<p><strong>Jamie Davis</strong><br>Local workspace owner</p><div class="notice">This interactive prototype uses simulated peers and stores workspace changes only in this browser.</div>',
      );
      break;
    case "close":
      $("#dialog").close();
      break;
  }
});
window.addEventListener("hashchange", () => {
  page = currentPage();
  render();
});
$("#dialog").addEventListener("click", (e) => {
  if (e.target === $("#dialog")) $("#dialog").close();
});
render();
