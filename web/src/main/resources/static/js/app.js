/*
 * Eye toggles for sensitive amounts (reception balance, safe, bank), chart tooltips, and a
 * few small form helpers.
 *
 * Amounts render as "•••••" and carry their value in data-value; pressing the eye for a
 * group reveals every amount of that group on the page. Hidden by default; the choice is
 * remembered per browser. This protects against shoulder-surfing at the desk — access
 * control itself is enforced on the server.
 */
(function () {
  'use strict';

  var HIDDEN = '•••••';

  function isShown(group) {
    try {
      return window.localStorage.getItem('cslsm.show.' + group) === '1';
    } catch (e) {
      return false;
    }
  }

  function remember(group, show) {
    try {
      window.localStorage.setItem('cslsm.show.' + group, show ? '1' : '0');
    } catch (e) {
      /* storage unavailable: the amount simply stays hidden next time */
    }
  }

  function apply(group, show) {
    document.querySelectorAll('[data-secret="' + group + '"]').forEach(function (el) {
      el.textContent = show ? el.getAttribute('data-value') : HIDDEN;
      el.classList.toggle('revealed', show);
    });
    document.querySelectorAll('[data-eye="' + group + '"]').forEach(function (btn) {
      btn.setAttribute('aria-pressed', show ? 'true' : 'false');
      btn.title = show ? 'Hide amount' : 'Show amount';
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    var groups = {};
    document.querySelectorAll('[data-secret]').forEach(function (el) {
      groups[el.getAttribute('data-secret')] = true;
    });
    Object.keys(groups).forEach(function (group) {
      apply(group, isShown(group));
    });

    document.querySelectorAll('[data-eye]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var group = btn.getAttribute('data-eye');
        var show = btn.getAttribute('aria-pressed') !== 'true';
        remember(group, show);
        apply(group, show);
      });
    });

    // Forms that change or delete data ask first (inline onclick is blocked by the CSP).
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
      form.addEventListener('submit', function (ev) {
        if (!window.confirm(form.getAttribute('data-confirm'))) {
          ev.preventDefault();
        }
      });
    });

    // "Select all" checkbox for bulk approval.
    document.querySelectorAll('input[data-select-all]').forEach(function (master) {
      master.addEventListener('change', function () {
        var name = master.getAttribute('data-select-all');
        document.querySelectorAll('input[type="checkbox"][name="' + name + '"]').forEach(function (box) {
          box.checked = master.checked;
        });
      });
    });

    // Chart tooltips: every chart band carries its figures in data-tip (lines separated by
    // "|"); one shared box follows the pointer, and works with a tap on touch screens.
    var tip = document.createElement('div');
    tip.className = 'chart-tip';
    tip.hidden = true;
    document.body.appendChild(tip);

    function showTip(band, x, y) {
      var lines = band.getAttribute('data-tip').split('|');
      tip.textContent = '';
      lines.forEach(function (line, i) {
        var row = document.createElement('div');
        row.textContent = line;
        if (i === 0) { row.className = 'chart-tip-head'; }
        tip.appendChild(row);
      });
      tip.hidden = false;
      var w = tip.offsetWidth, h = tip.offsetHeight;
      var left = x + 14, top = y + 14;
      if (left + w > window.innerWidth - 8) { left = x - w - 14; }
      if (top + h > window.innerHeight - 8) { top = y - h - 14; }
      tip.style.left = Math.max(4, left) + 'px';
      tip.style.top = Math.max(4, top) + 'px';
    }

    function hideTip() {
      tip.hidden = true;
    }

    document.querySelectorAll('.chart-box').forEach(function (box) {
      box.addEventListener('mousemove', function (ev) {
        var band = ev.target.closest ? ev.target.closest('[data-tip]') : null;
        if (band) { showTip(band, ev.clientX, ev.clientY); } else { hideTip(); }
      });
      box.addEventListener('mouseleave', hideTip);
      box.addEventListener('click', function (ev) {
        var band = ev.target.closest ? ev.target.closest('[data-tip]') : null;
        if (band) { showTip(band, ev.clientX, ev.clientY); }
      });
    });
    document.addEventListener('scroll', hideTip, true);

    // Refuse oversized files before uploading them: the server would reject them anyway,
    // but only after the whole upload, and with a less helpful page.
    document.querySelectorAll('input[type="file"][data-max-mb]').forEach(function (input) {
      input.addEventListener('change', function () {
        var max = parseFloat(input.getAttribute('data-max-mb')) * 1024 * 1024;
        var tooBig = Array.prototype.filter.call(input.files, function (f) { return f.size > max; });
        if (tooBig.length > 0) {
          window.alert(tooBig.map(function (f) { return f.name; }).join(', ')
            + ' is too large (max ' + input.getAttribute('data-max-mb') + ' MB).');
          input.value = '';
        }
      });
    });
  });
})();
