// Remote-control navigation for Zylo inside the Android TV app. The D-pad arrows move focus to the nearest
// control in that direction; OK (Enter) presses it. On the video: left/right seek 10 s, OK plays/pauses.
(function () {
  if (window.__zyloRemote) return;
  window.__zyloRemote = true;
  var css = document.createElement("style");
  css.textContent = ":focus{outline:3px solid #52c4b3!important;outline-offset:3px!important;border-radius:8px}";
  document.head.appendChild(css);

  var SEL = "a[href],button:not([disabled]),input:not([type=hidden]):not([disabled]),select,textarea,video,[tabindex]:not([tabindex='-1'])";
  var DIR = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] };

  function visible(el) {
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== "hidden";
  }
  function centre(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function move(d) {
    var cur = document.activeElement && document.activeElement !== document.body ? document.activeElement : null;
    var all = Array.prototype.filter.call(document.querySelectorAll(SEL), visible);
    if (!cur) { var first = all.filter(function (el) { var r = el.getBoundingClientRect(); return r.top >= 0 && r.top < innerHeight; })[0] || all[0]; if (first) go(first); return; }
    var a = cur.getBoundingClientRect(), ca = centre(a), best = null, bestScore = Infinity;
    all.forEach(function (el) {
      if (el === cur || cur.contains(el)) return;
      var b = el.getBoundingClientRect(), cb = centre(b);
      var along = (cb.x - ca.x) * d[0] + (cb.y - ca.y) * d[1]; // distance in the pressed direction
      if (along <= 1) return;
      var across = Math.abs((cb.x - ca.x) * d[1] + (cb.y - ca.y) * d[0]); // sideways drift
      var score = along + across * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    if (best) go(best); else scrollBy(d[0] * innerWidth * 0.6, d[1] * innerHeight * 0.6);
  }
  function go(el) {
    el.focus({ preventScroll: true });
    el.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });
  }

  document.addEventListener("keydown", function (e) {
    var t = e.target, d = DIR[e.key];
    if (t && t.tagName === "VIDEO") {
      if (e.key === "Enter" || e.key === " ") { t.paused ? t.play() : t.pause(); e.preventDefault(); return; }
      if (e.key === "ArrowLeft" || e.key === "ArrowRight") { t.currentTime = Math.max(0, t.currentTime + (e.key === "ArrowLeft" ? -10 : 10)); e.preventDefault(); return; }
    }
    if (!d) return;
    // In a text box, left/right move the cursor; up/down still leave it.
    var typing = t && (t.tagName === "TEXTAREA" || (t.tagName === "INPUT" && /^(text|email|password|search|url|number|tel)$/.test(t.type)));
    if (typing && d[1] === 0) return;
    if (t && t.tagName === "SELECT") return;
    e.preventDefault();
    move(d);
  }, true);
})();
