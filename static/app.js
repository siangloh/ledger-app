function formatMoney(value, symbol) {
  const n = Number(value) || 0;
  const sym = symbol || (typeof window !== 'undefined' && window.LEDGER_CURRENCY_SYMBOL) || 'RM';
  return sym + ' ' + n.toLocaleString('en-MY', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// ===========================================================================
// 金额隐私遮罩模式 (Privacy Masking Mode)
// ===========================================================================
function updatePrivacyModeUI(isPrivacy) {
  const icon = document.getElementById('privacyIcon');
  const mIcon = document.getElementById('mobileSheetPrivacyIcon');
  const mDesc = document.getElementById('mobileSheetPrivacyDesc');
  const mBadge = document.getElementById('mobileSheetPrivacyBadge');
  const btn = document.getElementById('privacyToggleBtn');
  if (icon) icon.textContent = isPrivacy ? '🙈' : '👁';
  if (mIcon) mIcon.textContent = isPrivacy ? '🙈' : '👁';
  if (mDesc) {
    mDesc.textContent = isPrivacy
      ? (window.t ? window.t('nav.privacy_on_title', '已开启隐私遮罩') : '已开启隐私遮罩')
      : (window.t ? window.t('nav.sheet_privacy_desc', '一键隐藏金额与资产') : '一键隐藏金额与资产');
  }
  if (mBadge) {
    mBadge.textContent = isPrivacy
      ? (window.t ? window.t('common.on', '开启') : '开启')
      : (window.t ? window.t('common.off', '关闭') : '关闭');
    mBadge.className = 'sheet-pill-badge ' + (isPrivacy ? 'badge-on' : 'badge-off');
  }
  if (btn) {
    btn.title = isPrivacy
      ? (window.t ? window.t('nav.privacy_on_title', '隐私遮罩已开启 (点击或 Ctrl+Shift+P 恢复)') : '隐私遮罩已开启 (点击或 Ctrl+Shift+P 恢复)')
      : (window.t ? window.t('nav.privacy_off_title', '隐私遮罩 (快捷键: Ctrl+Shift+P)') : '隐私遮罩 (快捷键: Ctrl+Shift+P)');
    btn.classList.toggle('active', isPrivacy);
  }
}

function refreshAllChartsPrivacy() {
  var chartList = [
    typeof _monthlyIncomeChart !== 'undefined' ? _monthlyIncomeChart : null,
    typeof _monthlyExpenseChart !== 'undefined' ? _monthlyExpenseChart : null,
    typeof _ovTrendChart !== 'undefined' ? _ovTrendChart : null,
    typeof _ovExpenseChart !== 'undefined' ? _ovExpenseChart : null,
    typeof _ovIncomeChart !== 'undefined' ? _ovIncomeChart : null
  ];
  chartList.forEach(function (c) {
    if (c && typeof c.update === 'function') {
      try {
        c.update('none');
      } catch (e) {}
    }
  });
  if (typeof window !== 'undefined' && window._catChartInstances) {
    Object.values(window._catChartInstances).forEach(function (c) {
      if (c && typeof c.update === 'function') {
        try { c.update('none'); } catch (e) {}
      }
    });
  }
}

function togglePrivacyMode(forceState) {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  const isPrivacy = typeof forceState === 'boolean' ? forceState : !root.classList.contains('privacy-mode');
  root.classList.toggle('privacy-mode', isPrivacy);
  try {
    localStorage.setItem('ledger_privacy_mode', isPrivacy ? 'true' : 'false');
  } catch (e) {}
  updatePrivacyModeUI(isPrivacy);
  refreshAllChartsPrivacy();
}

if (typeof window !== 'undefined') {
  window.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'P' || e.key === 'p')) {
      e.preventDefault();
      togglePrivacyMode();
    }
  });
  window.addEventListener('DOMContentLoaded', function () {
    const isPrivacy = document.documentElement.classList.contains('privacy-mode');
    updatePrivacyModeUI(isPrivacy);
  });
}

function syncSegStyles() {
  document.querySelectorAll('.seg').forEach(function (label) {
    const input = label.querySelector('input');
    label.classList.toggle('seg-checked', !!(input && input.checked));
  });
}

// 手机/系统原生通知管理器
function requestPhoneNotificationPermission() {
  if (typeof window !== 'undefined' && 'Notification' in window && Notification.permission === 'default') {
    Notification.requestPermission().catch(() => {});
  }
}

function sendPhoneNotification(title, body, options = {}) {
  if (typeof window === 'undefined' || !('Notification' in window)) return;

  const notify = () => {
    const opts = {
      body: body || '新交易入账成功',
      icon: '/static/icons/icon-192.png',
      badge: '/static/icons/icon-192.png',
      vibrate: [200, 100, 200],
      tag: 'ledger-tx-' + Date.now(),
      renotify: true,
      ...options
    };
    if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
      navigator.serviceWorker.ready.then(reg => {
        reg.showNotification(title, opts);
      }).catch(() => {
        try { new Notification(title, opts); } catch (e) {}
      });
    } else {
      try { new Notification(title, opts); } catch (e) {}
    }
  };

  if (Notification.permission === 'granted') {
    notify();
  } else if (Notification.permission === 'default') {
    Notification.requestPermission().then(perm => {
      if (perm === 'granted') notify();
    }).catch(() => {});
  }
}

function onExpenseModeChange() {
  const typeRadio = document.querySelector('input[name="type"]:checked');
  const isExpense = !typeRadio || typeRadio.value === 'expense';
  const modeRadio = document.querySelector('input[name="expense_mode"]:checked');
  const mode = modeRadio ? modeRadio.value : 'regular';
  const fromSavingsInput = document.getElementById('fromSavingsInput');
  const fromSavingsBox = document.getElementById('fromSavingsCategoryBox');
  const quickChips = document.getElementById('quickChipsRow');

  if (fromSavingsInput) {
    fromSavingsInput.value = (isExpense && mode === 'savings') ? '1' : '0';
  }
  if (fromSavingsBox) {
    fromSavingsBox.style.display = (isExpense && mode === 'savings') ? 'block' : 'none';
  }
  if (quickChips) {
    quickChips.style.display = (isExpense && mode === 'regular') ? 'flex' : 'none';
  }
  syncSegStyles();
}

function onTypeChange() {
  const checkedRadio = document.querySelector('input[name="type"]:checked');
  if (!checkedRadio) return;
  const type = checkedRadio.value;
  const groupRow = document.getElementById('groupRow');
  const expenseSubRow = document.getElementById('expenseSubRow');
  const fromSavingsBox = document.getElementById('fromSavingsCategoryBox');
  const quickChips = document.getElementById('quickChipsRow');
  const fromSavingsInput = document.getElementById('fromSavingsInput');

  if (groupRow) groupRow.style.display = (type === 'income') ? 'flex' : 'none';

  if (type === 'expense') {
    if (expenseSubRow) expenseSubRow.style.display = 'flex';
    onExpenseModeChange();
  } else {
    if (expenseSubRow) expenseSubRow.style.display = 'none';
    if (fromSavingsBox) fromSavingsBox.style.display = 'none';
    if (quickChips) quickChips.style.display = 'none';
    if (fromSavingsInput) fromSavingsInput.value = '0';
  }

  populateCategories();
  syncSegStyles();
}

function toggleMoreSheet(force) {
  const sheet = document.getElementById('moreSheet');
  const backdrop = document.getElementById('sheetBackdrop');
  if (!sheet || !backdrop) return;
  const isShow = typeof force === 'boolean' ? force : !sheet.classList.contains('show');
  sheet.classList.toggle('show', isShow);
  backdrop.classList.toggle('show', isShow);
  if (isShow) {
    updateAppDownloadStatus();
  }
}

function quickFillForm(amount, category, note) {
  const typeRadio = document.querySelector('input[name="type"][value="expense"]');
  if (typeRadio) {
    typeRadio.checked = true;
  }
  const modeRadio = document.querySelector('input[name="expense_mode"][value="regular"]');
  if (modeRadio) {
    modeRadio.checked = true;
  }
  onTypeChange();
  const amountInput = document.querySelector('input[name="amount"]');
  if (amountInput) {
    amountInput.value = Number(amount).toFixed(2);
    amountInput.focus();
  }
  const sel = document.getElementById('categorySelect');
  if (sel) {
    sel.value = category;
  }
  const noteInput = document.querySelector('input[name="note"]');
  if (noteInput && note) {
    noteInput.value = note;
  }
}

function populateCategories() {
  const sel = document.getElementById('categorySelect');
  if (!sel || !window.CATEGORY_DATA) return;
  const typeInput = document.querySelector('input[name="type"]:checked');
  const type = typeInput ? typeInput.value : 'expense';

  let options = [];
  if (type === 'income') {
    const groupInput = document.querySelector('input[name="group_name"]:checked');
    const group = groupInput ? groupInput.value : 'main';
    options = window.CATEGORY_DATA['income_' + group] || [];
  } else if (type === 'savings') {
    options = window.CATEGORY_DATA.savings || ['定期存款', '应急基金', '投资理财', '心愿基金'];
  } else {
    options = window.CATEGORY_DATA.expense || [];
  }

  const preselect = sel.dataset.preselect || '';
  sel.innerHTML = options.map(function (o) {
    const label = window.t_cat ? window.t_cat(o) : o;
    return '<option value="' + o + '"' + (o === preselect ? ' selected' : '') + '>' + label + '</option>';
  }).join('');

  syncSegStyles();
}

function toggleTypeCol() {
  const mode = document.querySelector('input[name="type_mode"]:checked');
  const row = document.getElementById('typeColRow');
  if (row) row.style.display = (mode && mode.value === 'column') ? 'flex' : 'none';
}

// 沉稳的珠宝色系配色，避免默认高饱和度调色板
const CHART_PALETTE = ['#10213b', '#b6902f', '#1e7a5c', '#7a5c8a', '#4d7ea8', '#a0522d', '#5c6b73', '#b04a56'];

function fitText(ctx, text, maxWidth) {
  if (maxWidth <= 0) return '';
  if (ctx.measureText(text).width <= maxWidth) return text;
  let t = text;
  while (t.length > 1 && ctx.measureText(t + '…').width > maxWidth) {
    t = t.slice(0, -1);
  }
  return t + '…';
}

function drawDonutChart(canvasId, labels, values, colors) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const cssWidth = canvas.clientWidth || canvas.width;
  const cssHeight = canvas.height;
  canvas.width = cssWidth * dpr;
  canvas.height = cssHeight * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, cssWidth, cssHeight);

  const total = values.reduce(function (a, b) { return a + (b || 0); }, 0);
  if (!total) {
    ctx.fillStyle = '#9a978f';
    ctx.font = '13px "Segoe UI", sans-serif';
    ctx.fillText('暂无数据', 16, cssHeight / 2);
    return;
  }

  const cy = cssHeight / 2;
  const rOuter = Math.max(48, Math.min(cssHeight / 2 - 14, cssWidth * 0.22));
  const cx = Math.max(rOuter + 12, Math.min(cssWidth * 0.26, rOuter + 26));
  const rInner = rOuter * 0.56;

  let start = -Math.PI / 2;
  labels.forEach(function (label, i) {
    const value = values[i] || 0;
    if (value <= 0) return;
    const angle = (value / total) * Math.PI * 2;
    ctx.beginPath();
    ctx.moveTo(cx + rInner * Math.cos(start), cy + rInner * Math.sin(start));
    ctx.arc(cx, cy, rOuter, start, start + angle);
    ctx.arc(cx, cy, rInner, start + angle, start, true);
    ctx.closePath();
    ctx.fillStyle = colors[i % colors.length];
    ctx.fill();
    start += angle;
  });

  ctx.fillStyle = '#1b1b1f';
  ctx.textAlign = 'center';
  ctx.font = '600 ' + Math.max(11, Math.min(14, rInner / 3.2)) + 'px ui-monospace, SFMono-Regular, Consolas, monospace';
  ctx.fillText(fitText(ctx, formatMoney(total), rInner * 1.7), cx, cy + 5);
  ctx.textAlign = 'left';

  const legendX = cx + rOuter + 20;
  const dotX = legendX + 4;
  const textX = legendX + 14;
  const legendMaxWidth = Math.max(50, cssWidth - textX - 8);

  ctx.font = '12px "Segoe UI", sans-serif';
  const needsTwoLines = labels.some(function (label, i) {
    const value = values[i] || 0;
    const pct = total ? (value / total * 100).toFixed(1) : '0.0';
    const line = label + '  ' + formatMoney(value) + ' (' + pct + '%)';
    return ctx.measureText(line).width > legendMaxWidth;
  });

  const rowH = needsTwoLines
    ? Math.min(36, (cssHeight - 12) / Math.max(labels.length, 1))
    : Math.min(24, (cssHeight - 12) / Math.max(labels.length, 1));
  let ly = (cssHeight - rowH * labels.length) / 2 + rowH / 2;

  labels.forEach(function (label, i) {
    const value = values[i] || 0;
    const pct = total ? (value / total * 100).toFixed(1) : '0.0';

    ctx.fillStyle = colors[i % colors.length];
    ctx.beginPath();
    ctx.arc(dotX, needsTwoLines ? ly - 5 : ly, 5, 0, Math.PI * 2);
    ctx.fill();

    if (needsTwoLines) {
      ctx.font = '12px "Segoe UI", sans-serif';
      ctx.fillStyle = '#3a3a40';
      ctx.fillText(fitText(ctx, label, legendMaxWidth), textX, ly - 1);
      ctx.font = '11px ui-monospace, SFMono-Regular, Consolas, monospace';
      ctx.fillStyle = '#8a877e';
      ctx.fillText(fitText(ctx, formatMoney(value) + ' (' + pct + '%)', legendMaxWidth), textX, ly + 13);
    } else {
      ctx.font = '12px "Segoe UI", sans-serif';
      ctx.fillStyle = '#3a3a40';
      ctx.fillText(fitText(ctx, label + '  ' + formatMoney(value) + ' (' + pct + '%)', legendMaxWidth), textX, ly + 4);
    }
    ly += rowH;
  });
}

// ---------- SweetAlert2：成功提示（toast） ----------

function showSuccessToasts() {
  if (typeof Swal === 'undefined' || !window.FLASH_SUCCESS || !window.FLASH_SUCCESS.length) return;
  const msgs = window.FLASH_SUCCESS.slice();
  window.FLASH_SUCCESS = null; // 消费后立刻置空！防止页面重新水合或导航时重复提示
  Swal.fire({
    toast: true,
    position: 'top-end',
    icon: 'success',
    title: msgs.join('　'),
    showConfirmButton: false,
    timer: 2600,
    timerProgressBar: true,
    customClass: { popup: 'app-swal-toast' }
  });
}

// ---------- SweetAlert2：错误提示 ----------

function errorAlert(message, title) {
  if (typeof Swal === 'undefined') { return; }
  Swal.fire({
    icon: 'error',
    title: title || (window.t ? window.t('swal.op_incomplete', '操作未完成') : '操作未完成'),
    html: message,
    confirmButtonText: window.t ? window.t('swal.got_it', '知道了') : '知道了',
    buttonsStyling: false,
    customClass: {
      popup: 'app-swal-popup',
      title: 'app-swal-title',
      htmlContainer: 'app-swal-html',
      confirmButton: 'btn-primary'
    }
  });
}

function showErrorAlerts() {
  if (!window.FLASH_ERROR || !window.FLASH_ERROR.length) return;
  const msgs = window.FLASH_ERROR.slice();
  window.FLASH_ERROR = null; // 消费后立刻置空！
  errorAlert(msgs.join('<br>'), '操作未完成');
}

// ---------- SweetAlert2：删除确认 ----------

function attachDeleteConfirm() {
  if (typeof Swal === 'undefined') return;
  document.querySelectorAll('form.js-delete-confirm').forEach(function (form) {
    if (form.dataset.swalBound) return;
    form.dataset.swalBound = '1';
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      const defaultTitle = window.t ? window.t('swal.delete_confirm', '确认删除？') : '确认删除？';
      const defaultDesc = window.t ? window.t('swal.irreversible_action', '此操作不可撤销。') : '此操作不可撤销。';
      const undoWindowHint = window.t ? window.t('swal.undo_window_5s', '删除后提供 5 秒撤销恢复窗口。') : '删除后提供 5 秒撤销恢复窗口。';
      Swal.fire({
        icon: 'warning',
        iconColor: '#a8475a',
        title: form.dataset.confirmTitle || defaultTitle,
        html: (form.dataset.confirmText || defaultDesc) + '<br><span style="color:#8a877e;font-size:13px;">' + undoWindowHint + '</span>',
        showCancelButton: true,
        reverseButtons: true,
        confirmButtonText: window.t ? window.t('common.confirm_delete', '确认删除') : '确认删除',
        cancelButtonText: window.t ? window.t('common.cancel', '取消') : '取消',
        buttonsStyling: false,
        customClass: {
          popup: 'app-swal-popup',
          title: 'app-swal-title',
          htmlContainer: 'app-swal-html',
          confirmButton: 'btn-danger-solid',
          cancelButton: 'btn-ghost',
          actions: 'app-swal-actions'
        }
      }).then(function (result) {
        if (result.isConfirmed) {
          const row = form.closest('tr') || form.closest('.card') || form.closest('li');
          if (row) {
            row.style.opacity = '0.35';
            row.style.filter = 'grayscale(1)';
            row.style.pointerEvents = 'none';
          }
          let isUndone = false;
          const undoTitle = window.t ? window.t('swal.item_deleted', '已删除项目') : '已删除项目';
          const undoHint = window.t ? window.t('swal.undo_hint_5s', '如需撤销请在 5 秒内点击') : '如需撤销请在 5 秒内点击';
          const undoBtn = window.t ? window.t('swal.undo_btn', '撤销 (Undo)') : '撤销 (Undo)';
          Swal.fire({
            toast: true,
            position: 'bottom-end',
            icon: 'info',
            title: undoTitle,
            html: '<span style="font-size:12px;color:var(--muted)">' + undoHint + '</span>',
            timer: 5000,
            timerProgressBar: true,
            showConfirmButton: true,
            confirmButtonText: undoBtn,
            buttonsStyling: false,
            customClass: {
              popup: 'app-swal-toast app-swal-undo-toast',
              confirmButton: 'btn-primary btn-sm'
            }
          }).then(function (res) {
            if (res.isConfirmed) {
              isUndone = true;
              if (row) {
                row.style.opacity = '';
                row.style.filter = '';
                row.style.pointerEvents = '';
              }
              Swal.fire({
                toast: true,
                position: 'top-end',
                icon: 'success',
                title: window.t ? window.t('swal.undo_success', '已撤销删除') : '已撤销删除',
                showConfirmButton: false,
                timer: 2000
              });
            } else if (res.dismiss === Swal.DismissReason.timer || !isUndone) {
              if (typeof showProgressBar === 'function') showProgressBar();
              const formData = new FormData(form);
              fetch(form.action, {
                method: 'POST',
                body: formData,
                headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' }
              })
              .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
              })
              .then(data => {
                if (typeof finishProgressBar === 'function') finishProgressBar();
                if (data && data.ok) {
                  if (row) row.remove();
                  Swal.fire({
                    toast: true,
                    position: 'top-end',
                    icon: 'success',
                    title: data.message || (window.t ? window.t('swal.deleted', '已删除') : '已删除'),
                    showConfirmButton: false,
                    timer: 2000,
                    customClass: { popup: 'app-swal-toast' }
                  });
                  try {
                    if (typeof updateBatchBar === 'function' && document.getElementById('batchBar')) {
                      updateBatchBar();
                    }
                  } catch (e) {
                    console.warn(e);
                  }
                } else {
                  if (row) {
                    row.style.opacity = '';
                    row.style.filter = '';
                    row.style.pointerEvents = '';
                  }
                  errorAlert((data && data.message) || (window.t ? window.t('swal.delete_failed', '删除失败') : '删除失败'));
                }
              })
              .catch(err => {
                console.error('Delete request failed:', err);
                if (typeof finishProgressBar === 'function') finishProgressBar();
                if (row) {
                  row.style.opacity = '';
                  row.style.filter = '';
                  row.style.pointerEvents = '';
                }
                errorAlert(window.t ? window.t('swal.network_delete_failed', '网络连接异常，删除未完成') : '网络连接异常，删除未完成');
              });
            }
          });
        }
      });
    });
  });
}

// ---------- SweetAlert2：AJAX 表单与校验 ----------

function attachQuickAddFormAjax() {
  const form = document.getElementById('quickAddForm');
  if (!form || form.dataset.ajaxBound) return;
  form.dataset.ajaxBound = '1';

  const accountSelect = document.getElementById('quickAddAccountSelect');
  const continuousCheckbox = document.getElementById('continuousEntryCheckbox');

  if (continuousCheckbox) {
    try {
      const savedContinuous = localStorage.getItem('ledger_continuous_entry');
      if (savedContinuous !== null) {
        continuousCheckbox.checked = (savedContinuous === 'true');
      }
      continuousCheckbox.addEventListener('change', function () {
        localStorage.setItem('ledger_continuous_entry', this.checked ? 'true' : 'false');
      });
    } catch (e) {}
  }

  if (accountSelect) {
    try {
      const savedAccId = localStorage.getItem('ledger_last_account_id');
      if (savedAccId && accountSelect.querySelector(`option[value="${savedAccId}"]`)) {
        accountSelect.value = savedAccId;
      }
      accountSelect.addEventListener('change', function () {
        if (this.value) {
          localStorage.setItem('ledger_last_account_id', this.value);
        }
      });
    } catch (e) {}
  }

  form.addEventListener('submit', function (e) {
    const amountInput = form.querySelector('input[name="amount"]');
    const categorySelect = form.querySelector('select[name="category"]');
    const amount = parseFloat(amountInput ? amountInput.value : '');

    if (!amountInput || amountInput.value.trim() === '' || isNaN(amount)) {
      e.preventDefault();
      errorAlert(window.t ? window.t('swal.form_amount_req', '金额必须是大于 0 的数字，不能留空。') : '金额必须是大于 0 的数字，不能留空。', window.t ? window.t('swal.check_form', '请检查表单') : '请检查表单');
      return;
    }
    if (amount <= 0) {
      e.preventDefault();
      errorAlert((window.t ? window.t('swal.form_amount_req', '金额必须是大于 0 的数字') : '金额必须是大于 0 的数字') + ': ' + amountInput.value, window.t ? window.t('swal.check_form', '请检查表单') : '请检查表单');
      return;
    }
    if (categorySelect && !categorySelect.value) {
      e.preventDefault();
      errorAlert(window.t ? window.t('swal.need_category_first', '请先在「分类管理」里添加至少一个对应分类，再回来录入。') : '请先在「分类管理」里添加至少一个对应分类，再回来录入。', window.t ? window.t('swal.check_form', '缺少可选分类') : '缺少可选分类');
      return;
    }

    e.preventDefault();
    if (typeof showProgressBar === 'function') showProgressBar();
    const actionUrl = form.action || window.location.href;
    const formData = new FormData(form);

    fetch(actionUrl, {
      method: 'POST',
      body: formData,
      headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' }
    })
    .then(r => r.json())
    .then(data => {
      if (typeof finishProgressBar === 'function') finishProgressBar();
      if (data.ok) {
        if (accountSelect && accountSelect.value) {
          try {
            localStorage.setItem('ledger_last_account_id', accountSelect.value);
          } catch (e) {}
        }

        if (data.transaction) {
          const tx = data.transaction;
          const sym = window.LEDGER_CURRENCY_SYMBOL || 'RM';
          const amt = tx.amount ? `${sym} ${Number(tx.amount).toFixed(2)}` : '';
          const cat = tx.category ? `【${tx.category}】` : '';
          const note = tx.note ? ` ${tx.note}` : '';
          sendPhoneNotification('记账成功 📝', `${cat} ${amt}${note}`.trim());
        }

        const isContinuous = continuousCheckbox && continuousCheckbox.checked;
        Swal.fire({
          toast: true,
          position: 'top-end',
          icon: 'success',
          title: isContinuous ? (data.message ? `${data.message} (可继续录入下一笔)` : '记账成功，可继续录入下一笔') : (data.message || '操作成功'),
          showConfirmButton: false,
          timer: isContinuous ? 2000 : 2500,
          timerProgressBar: true,
          customClass: { popup: 'app-swal-toast' }
        });

        if (data.budget_alert && data.budget_alert.message) {
          Swal.fire({
            toast: true,
            position: 'top-end',
            icon: data.budget_alert.threshold >= 100 ? 'error' : 'warning',
            title: data.budget_alert.message,
            showConfirmButton: false,
            timer: 4200,
            timerProgressBar: true,
            customClass: { popup: 'app-swal-toast' }
          });
        }

        if (actionUrl.includes('/records/') && actionUrl.includes('/edit')) {
          if (typeof htmx !== 'undefined') {
            htmx.ajax('GET', '/records', { target: '#mainContainer', swap: 'innerHTML show:window:top' });
            history.pushState({}, '', '/records');
            if (typeof updateActiveNav === 'function') updateActiveNav('/records');
          } else {
            window.location.href = '/records';
          }
        } else {
          if (amountInput) amountInput.value = '';
          const noteInput = form.querySelector('input[name="note"]');
          if (noteInput) noteInput.value = '';
          const tagsInput = form.querySelector('input[name="tags"]');
          if (tagsInput) tagsInput.value = '';

          // 连续记账：自动重新聚焦金额输入框
          if (isContinuous && amountInput) {
            setTimeout(() => amountInput.focus(), 60);
          }

          if (data.version) {
            _currentDataVersion = data.version;
          }

          if (data.chart_data) {
            const curMonth = document.querySelector('.month-nav-current')?.textContent.trim() || '';
            if (!curMonth || curMonth === data.chart_data.month) {
              window.CHART_DATA = data.chart_data;
              if (typeof renderDonutCharts === 'function') {
                renderDonutCharts();
              }
            }
          }

          // 局部平滑无刷新更新仪表盘卡片与图表，彻底避免整页/整容器重载闪烁
          if (typeof refreshDashboardPartials === 'function') {
            refreshDashboardPartials(data.transaction);
          }

          // 动态更新储蓄资金池下拉选择中各分类的最新结余金额
          if (data.savings_pool) {
            const savingsSelect = document.getElementById('fromSavingsCategorySelect');
            if (savingsSelect) {
              const curSym = window.LEDGER_CURRENCY_SYMBOL || 'RM';
              Array.from(savingsSelect.options).forEach(opt => {
                const catName = opt.value;
                if (catName) {
                  const balLabel = window.t ? window.t('accounts.balance', '结余') : '结余';
                  opt.textContent = `${catName} (${balLabel}: ${curSym} ${Number(bal).toFixed(2)})`;
                }
              });
            }
          }
        }
      } else {
        errorAlert(data.message || '提交失败', '提示');
      }
    })
    .catch(() => {
      if (typeof finishProgressBar === 'function') finishProgressBar();
      errorAlert('网络连接异常，请重试。', '提交失败');
    });
  });
}

function attachCategoryFormAjax() {
  document.querySelectorAll('form.js-validate-category').forEach(function (form) {
    if (form.dataset.ajaxBound) return;
    form.dataset.ajaxBound = '1';
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      const nameInput = form.querySelector('input[name="name"]');
      if (!nameInput || nameInput.value.trim() === '') {
        errorAlert('分类名称不能为空，也不能只是空格。', '请检查表单');
        return;
      }

      if (typeof showProgressBar === 'function') showProgressBar();
      const formData = new FormData(form);
      fetch(form.action || '/categories/add', {
        method: 'POST',
        body: formData,
        headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' }
      })
      .then(r => r.json())
      .then(data => {
        if (typeof finishProgressBar === 'function') finishProgressBar();
        if (data.ok) {
          nameInput.value = '';
          Swal.fire({
            toast: true,
            position: 'top-end',
            icon: 'success',
            title: data.message || '分类已添加',
            showConfirmButton: false,
            timer: 2200,
            timerProgressBar: true,
            customClass: { popup: 'app-swal-toast' }
          });
          if (typeof htmx !== 'undefined') {
            htmx.ajax('GET', window.location.href, { target: '#mainContainer', swap: 'innerHTML' });
          } else {
            window.location.reload();
          }
        } else {
          errorAlert(data.message || '添加失败');
        }
      })
      .catch(() => {
        if (typeof finishProgressBar === 'function') finishProgressBar();
        errorAlert('网络连接异常，添加分类未完成');
      });
    });
  });
}

function attachRecurringFormsAjax() {
  document.querySelectorAll('form[action*="/recurring/"]').forEach(function (form) {
    const action = form.getAttribute('action') || '';
    if (!action.includes('/toggle') && !action.includes('/generate')) return;
    if (form.dataset.ajaxBound) return;
    form.dataset.ajaxBound = '1';

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      if (typeof showProgressBar === 'function') showProgressBar();
      const formData = new FormData(form);
      fetch(form.action, {
        method: 'POST',
        body: formData,
        headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' }
      })
      .then(r => r.json())
      .then(data => {
        if (typeof finishProgressBar === 'function') finishProgressBar();
        if (data.ok) {
          Swal.fire({
            toast: true,
            position: 'top-end',
            icon: 'success',
            title: data.message || '操作已完成',
            showConfirmButton: false,
            timer: 2400,
            timerProgressBar: true,
            customClass: { popup: 'app-swal-toast' }
          });
          if (typeof htmx !== 'undefined') {
            htmx.ajax('GET', window.location.href, { target: '#mainContainer', swap: 'innerHTML' });
          } else {
            window.location.reload();
          }
        } else {
          errorAlert(data.message || '操作未成功');
        }
      })
      .catch(() => {
        if (typeof finishProgressBar === 'function') finishProgressBar();
        errorAlert('网络连接异常，请重试');
      });
    });
  });
}

function attachSplitBillFormAjax() {
  document.querySelectorAll('form[action*="/split-bill/save-record"]').forEach(function (form) {
    if (form.dataset.ajaxBound) return;
    form.dataset.ajaxBound = '1';

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      if (typeof showProgressBar === 'function') showProgressBar();
      const formData = new FormData(form);
      fetch(form.action, {
        method: 'POST',
        body: formData,
        headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' }
      })
      .then(r => r.json())
      .then(data => {
        if (typeof finishProgressBar === 'function') finishProgressBar();
        if (data.ok) {
          Swal.fire({
            toast: true,
            position: 'top-end',
            icon: 'success',
            title: data.message || '已记入支出',
            showConfirmButton: false,
            timer: 2500,
            timerProgressBar: true,
            customClass: { popup: 'app-swal-toast' }
          });
          if (typeof htmx !== 'undefined') {
            htmx.ajax('GET', '/records', { target: '#mainContainer', swap: 'innerHTML show:window:top' });
            history.pushState({}, '', '/records');
            if (typeof updateActiveNav === 'function') updateActiveNav('/records');
          } else {
            window.location.href = '/records';
          }
        } else {
          errorAlert(data.message || '保存失败');
        }
      })
      .catch(() => {
        if (typeof finishProgressBar === 'function') finishProgressBar();
        errorAlert('网络连接异常，请重试');
      });
    });
  });
}

function attachImportFormValidation() {
  const form = document.getElementById('importUploadForm');
  if (!form || form.dataset.validateBound) return;
  form.dataset.validateBound = '1';
  form.addEventListener('submit', function (e) {
    const fileInput = form.querySelector('input[type="file"]');
    const file = fileInput && fileInput.files[0];
    if (!file) {
      e.preventDefault();
      errorAlert('请先选择要导入的 .csv / .xlsx / .xls 文件。', '请检查表单');
      return;
    }
    const ext = '.' + file.name.split('.').pop().toLowerCase();
    if (!['.csv', '.xlsx', '.xls'].includes(ext)) {
      e.preventDefault();
      errorAlert('「' + file.name + '」不是支持的文件类型，仅支持 .csv / .xlsx / .xls。', '文件格式不对');
    }
  });
}

// ---------- SweetAlert2：自然语言快速记账确认弹窗 ----------

function populateSwalCategorySelect(sel, type, group) {
  if (!sel || !window.CATEGORY_DATA) return;
  const options = type === 'income'
    ? (window.CATEGORY_DATA['income_' + group] || [])
    : (window.CATEGORY_DATA.expense || []);
  const preselect = sel.dataset.preselect || '';
  sel.innerHTML = options.map(function (o) {
    const label = window.t_cat ? window.t_cat(o) : o;
    return '<option value="' + o + '"' + (o === preselect ? ' selected' : '') + '>' + label + '</option>';
  }).join('');
}

function buildNlpConfirmHtml(parsed, warnings) {
  const verifyHint = window.t ? window.t('swal.nlp_verify_hint', '请核对下方字段，确认无误后再保存。') : '请核对下方字段，确认无误后再保存。';
  const warningHtml = warnings && warnings.length
    ? '<div class="warning-box">' + warnings.map(function (w) { return '⚠ ' + w; }).join('<br>') + '<br>' + verifyHint + '</div>'
    : '';
  const optExpense = window.t ? window.t('common.expense', '支出') : '支出';
  const optIncome = window.t ? window.t('common.income', '收入') : '收入';
  const optMain = window.t ? window.t('dashboard.main_income', '主业收入') : '主业收入';
  const optSide = window.t ? window.t('dashboard.side_income', '副业收入') : '副业收入';
  const lblDate = window.t ? window.t('common.date', '日期') : '日期';
  const lblAmount = window.t ? window.t('common.amount', '金额') : '金额';
  const lblCategory = window.t ? window.t('common.category', '分类') : '分类';
  const lblNote = window.t ? window.t('common.note', '备注') : '备注';

  return (
    warningHtml +
    '<div class="row seg-row">' +
    '  <label class="seg"><input type="radio" name="swalType" value="expense"><span>' + optExpense + '</span></label>' +
    '  <label class="seg"><input type="radio" name="swalType" value="income"><span>' + optIncome + '</span></label>' +
    '</div>' +
    '<div class="row seg-row" id="swalGroupRow">' +
    '  <label class="seg"><input type="radio" name="swalGroup" value="main"><span>' + optMain + '</span></label>' +
    '  <label class="seg"><input type="radio" name="swalGroup" value="side"><span>' + optSide + '</span></label>' +
    '</div>' +
    '<div class="row">' +
    '  <label>' + lblDate + ' <input type="date" id="swalDate"></label>' +
    '  <label>' + lblAmount + ' <input type="number" id="swalAmount" step="0.01" min="0.01"></label>' +
    '</div>' +
    '<div class="row">' +
    '  <label>' + lblCategory + ' <select id="swalCategory" data-preselect="' + (parsed.category || '') + '"></select></label>' +
    '  <label>' + lblNote + ' <input type="text" id="swalNote"></label>' +
    '</div>'
  );
}

function openNlpConfirmDialog(parsed, warnings) {
  const dlgTitle = window.t ? window.t('swal.nlp_confirm_title', '确认解析结果') : '确认解析结果';
  const btnSave = window.t ? window.t('swal.nlp_confirm_save', '确认保存') : '确认保存';
  const btnCancel = window.t ? window.t('common.cancel', '取消') : '取消';

  Swal.fire({
    title: dlgTitle,
    html: buildNlpConfirmHtml(parsed, warnings),
    showCancelButton: true,
    reverseButtons: true,
    confirmButtonText: btnSave,
    cancelButtonText: btnCancel,
    buttonsStyling: false,
    focusConfirm: false,
    customClass: {
      popup: 'app-swal-popup app-swal-popup-wide',
      title: 'app-swal-title',
      htmlContainer: 'app-swal-html',
      confirmButton: 'btn-primary',
      cancelButton: 'btn-ghost',
      actions: 'app-swal-actions'
    },
    didOpen: function () {
      const popup = Swal.getPopup();
      const typeInputs = popup.querySelectorAll('input[name="swalType"]');
      const groupRow = popup.querySelector('#swalGroupRow');
      const categorySelect = popup.querySelector('#swalCategory');

      function syncType() {
        const checked = popup.querySelector('input[name="swalType"]:checked');
        const type = checked ? checked.value : 'expense';
        groupRow.style.display = type === 'income' ? 'flex' : 'none';
        const groupChecked = popup.querySelector('input[name="swalGroup"]:checked');
        populateSwalCategorySelect(categorySelect, type, groupChecked ? groupChecked.value : 'main');
        popup.querySelectorAll('.seg').forEach(function (label) {
          const input = label.querySelector('input');
          label.classList.toggle('seg-checked', !!(input && input.checked));
        });
      }

      typeInputs.forEach(function (input) { input.addEventListener('change', syncType); });
      popup.querySelectorAll('input[name="swalGroup"]').forEach(function (input) {
        input.addEventListener('change', syncType);
      });

      popup.querySelector('input[name="swalType"][value="' + parsed.type + '"]').checked = true;
      if (parsed.type === 'income') {
        popup.querySelector('input[name="swalGroup"][value="' + (parsed.group_name || 'main') + '"]').checked = true;
      }
      popup.querySelector('#swalDate').value = parsed.date;
      popup.querySelector('#swalAmount').value = parsed.amount;
      popup.querySelector('#swalNote').value = parsed.note || '';
      syncType();
    },
    preConfirm: function () {
      const popup = Swal.getPopup();
      const type = popup.querySelector('input[name="swalType"]:checked').value;
      const groupChecked = popup.querySelector('input[name="swalGroup"]:checked');
      const amountValue = popup.querySelector('#swalAmount').value;
      const amount = parseFloat(amountValue);
      const category = popup.querySelector('#swalCategory').value;
      const date = popup.querySelector('#swalDate').value;
      const note = popup.querySelector('#swalNote').value;

      if (!date) {
        Swal.showValidationMessage(window.t ? window.t('swal.nlp_select_date', '请选择日期') : '请选择日期');
        return false;
      }
      if (amountValue.trim() === '' || isNaN(amount) || amount <= 0) {
        Swal.showValidationMessage(window.t ? window.t('swal.nlp_amount_positive', '金额必须是大于 0 的数字') : '金额必须是大于 0 的数字');
        return false;
      }
      if (!category) {
        Swal.showValidationMessage(window.t ? window.t('swal.nlp_need_category', '请先在「分类管理」里添加对应分类') : '请先在「分类管理」里添加对应分类');
        return false;
      }
      return {
        type: type,
        group_name: type === 'income' ? (groupChecked ? groupChecked.value : 'main') : '',
        date: date,
        amount: amount,
        category: category,
        note: note
      };
    }
  }).then(function (result) {
    if (!result.isConfirmed) return;
    const v = result.value;
    const csrfToken = document.querySelector('meta[name="csrf-token"]')?.getAttribute('content') || '';
    const body = new URLSearchParams({
      source: 'nlp', type: v.type, group_name: v.group_name, date: v.date,
      amount: v.amount, category: v.category, note: v.note,
      csrf_token: csrfToken
    });
    if (typeof showProgressBar === 'function') showProgressBar();
    fetch('/transactions/add', {
      method: 'POST',
      body: body,
      headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' }
    })
      .then(r => r.json())
      .then(function (data) {
        if (typeof finishProgressBar === 'function') finishProgressBar();
        if (data.ok) {
          Swal.fire({
            toast: true,
            position: 'top-end',
            icon: 'success',
            title: data.message || '记录已添加',
            showConfirmButton: false,
            timer: 2600,
            timerProgressBar: true,
            customClass: { popup: 'app-swal-toast' }
          });
          const textInput = document.querySelector('#nlpForm input[name="text"]');
          if (textInput) textInput.value = '';
          const isDashboard = document.getElementById('dashboardSummaryCardsWrap') !== null;
          if (isDashboard && typeof refreshDashboardPartials === 'function') {
            if (data.chart_data) {
              window.CHART_DATA = data.chart_data;
              if (typeof renderDonutCharts === 'function') renderDonutCharts();
            }
            refreshDashboardPartials(data.transaction);
          } else if (typeof htmx !== 'undefined') {
            htmx.ajax('GET', window.location.href, { target: '#mainContainer', swap: 'innerHTML' });
          } else {
            window.location.reload();
          }
        } else {
          errorAlert(data.message || '保存失败');
        }
      })
      .catch(function () {
        if (typeof finishProgressBar === 'function') finishProgressBar();
        errorAlert('保存失败，请检查网络或重试。');
      });
  });
}

function attachNlpForm() {
  const form = document.getElementById('nlpForm');
  if (!form || form.dataset.swalBound) return;
  form.dataset.swalBound = '1';
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    const textInput = form.querySelector('input[name="text"]');
    const text = textInput ? textInput.value.trim() : '';
    if (!text) {
      errorAlert(window.t ? window.t('nav.nlp_input_empty_msg', '请输入一句话，如「打车 32.5」。') : '请输入一句话，如「打车 32.5」。', window.t ? window.t('nav.nlp_input_empty_title', '请先输入内容') : '请先输入内容');
      return;
    }
    const submitBtn = form.querySelector('button[type="submit"]');
    const originalBtnHtml = submitBtn ? submitBtn.innerHTML : '';
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.innerHTML = '<span class="btn-spinner"></span> ' + (window.t ? window.t('swal.nlp_analyzing', '智能解析中...') : '智能解析中...');
    }
    const csrfToken = document.querySelector('meta[name="csrf-token"]')?.getAttribute('content') || form.querySelector('input[name="csrf_token"]')?.value || '';
    fetch('/nlp/parse', {
      method: 'POST',
      body: new URLSearchParams({ text: text, csrf_token: csrfToken })
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.ok) {
          errorAlert(data.message, '解析失败');
          return;
        }

        const confirmRequired = (typeof window.LEDGER_NLP_CONFIRM_REQUIRED !== 'undefined') ? (window.LEDGER_NLP_CONFIRM_REQUIRED !== 0 && window.LEDGER_NLP_CONFIRM_REQUIRED !== false) : true;
        const hasWarnings = data.warnings && data.warnings.length > 0;
        const hasValidAmount = data.parsed && Number(data.parsed.amount) > 0;

        if (!confirmRequired && !hasWarnings && hasValidAmount) {
          // 免确认直接自动入账
          const v = data.parsed;
          const params = new URLSearchParams({
            csrf_token: csrfToken,
            source: 'nlp',
            type: v.type || 'expense',
            group_name: v.group_name || '',
            date: v.date || '',
            category: v.category || '',
            amount: v.amount || '0',
            note: v.note || text,
            from_savings: v.from_savings ? '1' : '0',
            from_savings_category: v.from_savings_category || ''
          });

          fetch('/add', {
            method: 'POST',
            body: params,
            headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json' }
          })
            .then(function (res) { return res.json(); })
            .then(function (addRes) {
              if (addRes.ok) {
                Swal.fire({
                  toast: true,
                  position: 'top-end',
                  icon: 'success',
                  title: '智能记账直接入账成功 (' + formatMoney(v.amount) + ')',
                  showConfirmButton: false,
                  timer: 2500,
                  timerProgressBar: true,
                  customClass: { popup: 'app-swal-toast' }
                });
                if (textInput) textInput.value = '';
                const isDashboard = document.getElementById('dashboardSummaryCardsWrap') !== null;
                if (isDashboard && typeof refreshDashboardPartials === 'function') {
                  if (addRes.chart_data) {
                    window.CHART_DATA = addRes.chart_data;
                    if (typeof renderDonutCharts === 'function') renderDonutCharts();
                  }
                  refreshDashboardPartials(addRes.transaction);
                } else if (typeof htmx !== 'undefined') {
                  htmx.ajax('GET', window.location.href, { target: '#mainContainer', swap: 'innerHTML' });
                } else {
                  window.location.reload();
                }
              } else {
                openNlpConfirmDialog(data.parsed, data.warnings);
              }
            })
            .catch(function () {
              openNlpConfirmDialog(data.parsed, data.warnings);
            });
          return;
        }

        openNlpConfirmDialog(data.parsed, data.warnings);
      })
      .catch(function () {
        errorAlert('网络连接失败，请稍后重试。', '解析失败');
      })
      .finally(function () {
        if (submitBtn) {
          submitBtn.disabled = false;
          submitBtn.innerHTML = originalBtnHtml;
        }
      });
  });
}

// ---------------------------------------------------------------------------
// 总体 / 跨月仪表盘 (Total / Overview Dashboard)
// ---------------------------------------------------------------------------

let currentOverviewData = null;
let currentOverviewRange = 'all';
let trendHoverIndex = -1;

function switchDashboardView(view) {
  const isMonthly = (view === 'monthly');
  const btnMonthly = document.getElementById('tabMonthly');
  const btnOverview = document.getElementById('tabOverview');
  const secMonthly = document.getElementById('monthlySection');
  const secOverview = document.getElementById('overviewSection');

  if (btnMonthly) btnMonthly.classList.toggle('active', isMonthly);
  if (btnOverview) btnOverview.classList.toggle('active', !isMonthly);

  if (secMonthly) secMonthly.style.display = isMonthly ? 'block' : 'none';
  if (secOverview) secOverview.style.display = !isMonthly ? 'block' : 'none';

  try {
    if (window.history && window.history.replaceState) {
      window.history.replaceState(null, null, isMonthly ? '#monthly' : '#overview');
    }
  } catch (e) {}

  if (!isMonthly) {
    if (!currentOverviewData) {
      loadOverviewData(currentOverviewRange);
    } else {
      renderOverview(currentOverviewData);
    }
    attachTrendChartHover();
  } else {
    requestAnimationFrame(function () {
      if (typeof renderDonutCharts === 'function') {
        renderDonutCharts();
      }
    });
  }
}

function selectOverviewRange(range) {
  currentOverviewRange = range;
  document.querySelectorAll('.filter-pills .pill-btn').forEach(function (btn) {
    btn.classList.toggle('active', btn.dataset.range === range);
  });
  const customBox = document.getElementById('customDateBox');
  if (customBox) {
    customBox.style.display = (range === 'custom') ? 'inline-flex' : 'none';
  }
  if (range !== 'custom') {
    loadOverviewData(range);
  }
}

function applyCustomOverviewFilter() {
  const startInput = document.getElementById('overviewStartDate');
  const endInput = document.getElementById('overviewEndDate');
  const start = startInput ? startInput.value.trim() : '';
  const end = endInput ? endInput.value.trim() : '';
  if (start && end && start > end) {
    if (typeof errorAlert === 'function') {
      errorAlert('开始日期不能晚于结束日期', '筛选提示');
    } else {
      alert('开始日期不能晚于结束日期');
    }
    return;
  }
  loadOverviewData('custom', start, end);
}

function loadOverviewData(range, start, end) {
  const badge = document.getElementById('overviewRangeBadge');
  if (badge) {
    badge.innerHTML = '<span class="skeleton-shimmer" style="display:inline-block; width:140px; height:15px; vertical-align:middle; border-radius:4px;"></span>';
  }

  // 给 4 个总览指标卡片数字加上优雅微光骨架状态，避免直显 RM 0.00 造成迟钝感
  const ovIds = ['ovTotalIncome', 'ovTotalExpense', 'ovNetSavings', 'ovAvgIncome', 'ovAvgExpense'];
  ovIds.forEach(id => {
    const el = document.getElementById(id);
    if (el) {
      el.innerHTML = '<span class="skeleton-shimmer" style="display:inline-block; width:85px; height:24px; vertical-align:middle; border-radius:4px;"></span>';
    }
  });

  let url = '/api/overview?range=' + encodeURIComponent(range || 'all');
  if (start) url += '&start=' + encodeURIComponent(start);
  if (end) url += '&end=' + encodeURIComponent(end);

  fetch(url)
    .then(function (r) { return r.json(); })
    .then(function (data) {
      if (!data.ok) {
        if (badge) badge.textContent = window.t ? window.t('dashboard.load_failed_retry', '加载失败，请刷新重试') : '加载失败，请刷新重试';
        return;
      }
      currentOverviewData = data;
      renderOverview(data);
      attachTrendChartHover();
    })
    .catch(function (err) {
      console.error(err);
      if (badge) badge.textContent = window.t ? window.t('dashboard.network_error', '网络异常，无法获取统计数据') : '网络异常，无法获取统计数据';
    });
}

function renderOverview(data) {
  const badge = document.getElementById('overviewRangeBadge');
  if (badge) {
    let rangeDesc = window.t ? window.t('dashboard.range_all', '全部历史') : '全部历史';
    if (data.range === '12m') rangeDesc = window.t ? window.t('dashboard.range_12m', '近 12 个月') : '近 12 个月';
    else if (data.range === 'ytd') rangeDesc = window.t ? window.t('dashboard.range_ytd', '本年度 (YTD)') : '本年度 (YTD)';
    else if (data.range === 'custom') {
      const earliest = window.t ? window.t('dashboard.earliest', '最早') : '最早';
      const present = window.t ? window.t('dashboard.present', '至今') : '至今';
      const toStr = window.t ? window.t('dashboard.to', '至') : '至';
      rangeDesc = (data.start_date || earliest) + ' ' + toStr + ' ' + (data.end_date || present);
    }
    const spanMonths = window.t ? window.t('dashboard.timespan_fmt', '{desc} · 跨度 {months} 个月', { desc: rangeDesc, months: data.num_months || 0 }) : (rangeDesc + ' · 跨度 ' + (data.num_months || 0) + ' 个月');
    badge.textContent = spanMonths;
  }

  const m = data.metrics || {};
  const totalInc = document.getElementById('ovTotalIncome');
  const totalExp = document.getElementById('ovTotalExpense');
  const netSav = document.getElementById('ovNetSavings');
  const netCard = document.getElementById('ovNetSavingsCard');
  const avgInc = document.getElementById('ovAvgIncome');
  const avgExp = document.getElementById('ovAvgExpense');

  if (totalInc) totalInc.textContent = formatMoney(m.total_income);
  if (totalExp) totalExp.textContent = formatMoney(m.total_expense);
  if (netSav) {
    netSav.textContent = formatMoney(m.net_savings);
    netSav.className = 'amount ' + (m.net_savings >= 0 ? 'income' : 'expense');
  }
  if (netCard) {
    netCard.classList.toggle('positive', m.net_savings >= 0);
    netCard.classList.toggle('negative', m.net_savings < 0);
  }
  if (avgInc) avgInc.textContent = formatMoney(m.avg_income);
  if (avgExp) avgExp.textContent = formatMoney(m.avg_expense);

  const emptyState = document.getElementById('overviewEmptyState');
  const chartsArea = document.getElementById('overviewChartsArea');

  if (!data.has_data) {
    if (emptyState) emptyState.style.display = 'block';
    if (chartsArea) chartsArea.style.display = 'none';
    return;
  }

  if (emptyState) emptyState.style.display = 'none';
  if (chartsArea) chartsArea.style.display = 'block';

  drawOverviewBarChart(data.trend || []);

  const expCats = data.expense_categories || { labels: [], values: [] };
  drawOverviewDoughnut('overviewExpenseChart', expCats.labels, expCats.values);

  const incGrp = data.income_group || { main: 0, side: 0, side_ratio: 0 };
  const incLabels = [
    window.t ? window.t('dashboard.main_income', '主业收入') : '主业收入',
    window.t ? window.t('dashboard.side_income', '副业收入') : '副业收入'
  ];
  drawOverviewDoughnut('overviewIncomeChart', incLabels, [incGrp.main || 0, incGrp.side || 0]);

  const sideBadge = document.getElementById('sideIncomeBadge');
  if (sideBadge) {
    const sideRatioLbl = window.t ? window.t('dashboard.side_ratio', '副业占比') : '副业占比';
    sideBadge.textContent = sideRatioLbl + ': ' + (incGrp.side_ratio || 0).toFixed(1) + '%';
  }

  const sideDesc = document.getElementById('sideRatioDesc');
  if (sideDesc) {
    const r = incGrp.side_ratio || 0;
    const rStr = r.toFixed(1);
    if (r >= 35) {
      sideDesc.textContent = window.t ? window.t('dashboard.side_ratio_high', '副业贡献率达 {ratio}%，副业成长显著，收入来源具备很强的防御性与弹性。', { ratio: rStr }) : ('副业贡献率达 ' + rStr + '%，副业成长显著，收入来源具备很强的防御性与弹性。');
    } else if (r > 0) {
      const sideAmtStr = formatMoney(incGrp.side || 0);
      sideDesc.textContent = window.t ? window.t('dashboard.side_ratio_mid', '副业累计贡献 {amount} ({ratio}%)，主业为基本盘，副业稳健增益。', { amount: sideAmtStr, ratio: rStr }) : ('副业累计贡献 ' + sideAmtStr + ' (' + rStr + '%)，主业为基本盘，副业稳健增益。');
    } else {
      sideDesc.textContent = window.t ? window.t('dashboard.side_ratio_zero', '选定范围内暂无副业收入记录，当前收入 100% 来自主要工作。') : '选定范围内暂无副业收入记录，当前收入 100% 来自主要工作。';
    }
  }
}

// ---------------------------------------------------------------------------
// Chart.js-based overview chart renderers
// ---------------------------------------------------------------------------

var _ovTrendChart = null;
var _ovExpenseChart = null;
var _ovIncomeChart = null;
var _monthlyIncomeChart = null;
var _monthlyExpenseChart = null;

function isDarkModeActive() {
  var theme = document.documentElement.getAttribute('data-theme');
  if (theme === 'dark') return true;
  if (theme === 'light') return false;
  try {
    var saved = localStorage.getItem('ledger_theme_mode');
    if (saved === 'dark') return true;
    if (saved === 'light') return false;
  } catch (e) {}
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function getChartPalette() {
  if (isDarkModeActive()) {
    // 高对比度现代暗色主题调色盘，适配深蓝黑卡片底色
    return ['#38bdf8', '#fbbf24', '#34d399', '#a78bfa', '#f87171', '#fb923c', '#818cf8', '#f472b6', '#2dd4bf', '#e879f9'];
  }
  return ['#10213b', '#b6902f', '#1e7a5c', '#7a5c8a', '#4d7ea8', '#a0522d', '#5c6b73', '#b04a56', '#2b6cb0', '#d69e2e'];
}

function destroyChart(instance) {
  if (instance) {
    try { instance.destroy(); } catch (e) {}
  }
  return null;
}

function rmMoneyFmt(value) {
  var n = Number(value) || 0;
  var sym = (typeof window !== 'undefined' && window.LEDGER_CURRENCY_SYMBOL) || 'RM';
  var absN = Math.abs(n);
  var sign = n < 0 ? '-' : '';
  if (absN >= 1000000) return sign + sym + ' ' + (absN / 1000000).toFixed(1) + 'M';
  if (absN >= 1000) return sign + sym + ' ' + (absN / 1000).toFixed(1) + 'k';
  return sign + sym + ' ' + absN.toFixed(2);
}

function drawOverviewBarChart(trendData) {
  var canvas = document.getElementById('overviewTrendChart');
  if (!canvas) return;

  _ovTrendChart = destroyChart(_ovTrendChart);

  if (!trendData || trendData.length === 0) return;

  var isDark = isDarkModeActive();
  var incomeColor = isDark ? 'rgba(56, 189, 248, 0.88)' : 'rgba(16, 33, 59, 0.88)';
  var incomeHover = isDark ? 'rgba(56, 189, 248, 1)' : 'rgba(30, 63, 111, 0.95)';
  var expenseColor = isDark ? 'rgba(248, 113, 113, 0.88)' : 'rgba(168, 71, 90, 0.88)';
  var expenseHover = isDark ? 'rgba(248, 113, 113, 1)' : 'rgba(191, 83, 104, 0.95)';
  var tickColor = isDark ? '#94a3b8' : '#334155';
  var gridColor = isDark ? 'rgba(255, 255, 255, 0.08)' : 'rgba(27, 27, 31, 0.06)';
  var gridBorder = isDark ? 'rgba(255, 255, 255, 0.15)' : 'rgba(27, 27, 31, 0.18)';
  var tooltipBg = isDark ? 'rgba(19, 29, 49, 0.96)' : 'rgba(255, 255, 255, 0.97)';
  var tooltipTitle = isDark ? '#f8fafc' : '#10213b';
  var tooltipBody = isDark ? '#cbd5e1' : '#46453f';
  var tooltipBorder = isDark ? 'rgba(255, 255, 255, 0.15)' : 'rgba(27, 27, 31, 0.1)';

  var labels = trendData.map(function (d) {
    return d.month.slice(2).replace('-', '/');
  });
  var incomeVals = trendData.map(function (d) { return d.income; });
  var expenseVals = trendData.map(function (d) { return d.expense; });
  var balanceVals = trendData.map(function (d) { return d.balance; });

  _ovTrendChart = new Chart(canvas.getContext('2d'), {
    type: 'bar',
    data: {
      labels: labels,
      datasets: [
        {
          label: window.t ? window.t('common.income', '收入') : '收入',
          data: incomeVals,
          backgroundColor: incomeColor,
          hoverBackgroundColor: incomeHover,
          borderRadius: 4,
          borderSkipped: 'bottom'
        },
        {
          label: window.t ? window.t('common.expense', '支出') : '支出',
          data: expenseVals,
          backgroundColor: expenseColor,
          hoverBackgroundColor: expenseHover,
          borderRadius: 4,
          borderSkipped: 'bottom'
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: {
          display: false
        },
        tooltip: {
          backgroundColor: tooltipBg,
          titleColor: tooltipTitle,
          bodyColor: tooltipBody,
          borderColor: tooltipBorder,
          borderWidth: 1,
          padding: 12,
          titleFont: { family: 'ui-monospace, SFMono-Regular, Consolas, monospace', size: 13, weight: '700' },
          bodyFont: { family: 'Segoe UI, sans-serif', size: 12 },
          callbacks: {
            title: function (items) {
              var idx = items[0].dataIndex;
              return trendData[idx].month;
            },
            label: function (item) {
              if (document.documentElement.classList.contains('privacy-mode')) {
                return ' ' + item.dataset.label + ': ••••••';
              }
              return ' ' + item.dataset.label + ': ' + formatMoney(item.raw);
            },
            afterBody: function (items) {
              var balPrefix = (window.t ? window.t('dashboard.balance_prefix', '结余') : '结余') + ': ';
              if (document.documentElement.classList.contains('privacy-mode')) {
                return [balPrefix + '••••••'];
              }
              var idx = items[0].dataIndex;
              var bal = balanceVals[idx];
              return [balPrefix + formatMoney(bal)];
            }
          }
        }
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: {
            color: tickColor,
            font: { family: 'ui-monospace, SFMono-Regular, Consolas, monospace', size: 11 },
            maxRotation: 45
          }
        },
        y: {
          grid: {
            color: gridColor,
            borderColor: gridBorder
          },
          ticks: {
            color: tickColor,
            font: { family: 'ui-monospace, SFMono-Regular, Consolas, monospace', size: 11 },
            callback: function (value) {
              if (document.documentElement.classList.contains('privacy-mode')) {
                return '••••';
              }
              return rmMoneyFmt(value);
            }
          }
        }
      }
    }
  });
}

function drawOverviewDoughnut(canvasId, labels, values) {
  var canvas = document.getElementById(canvasId);
  if (!canvas) return;

  if (canvasId === 'overviewExpenseChart') {
    _ovExpenseChart = destroyChart(_ovExpenseChart);
  } else {
    _ovIncomeChart = destroyChart(_ovIncomeChart);
  }

  var total = values.reduce(function (a, b) { return a + (b || 0); }, 0);
  var isDark = isDarkModeActive();
  var palette = getChartPalette();
  var sliceBorder = isDark ? '#131d31' : '#ffffff';
  var tooltipBg = isDark ? 'rgba(19, 29, 49, 0.96)' : 'rgba(255, 255, 255, 0.97)';
  var tooltipTitle = isDark ? '#f8fafc' : '#10213b';
  var tooltipBody = isDark ? '#cbd5e1' : '#46453f';
  var tooltipBorder = isDark ? 'rgba(255, 255, 255, 0.15)' : 'rgba(27, 27, 31, 0.1)';

  var centerTitle = (canvasId === 'overviewExpenseChart')
    ? (window.t ? window.t('dashboard.chart_center_expense', window.t('dashboard.total_cumulative_expense', '总支出')) : '总支出')
    : (window.t ? window.t('dashboard.chart_center_income', window.t('dashboard.total_cumulative_income', '总收入')) : '总收入');
  var centerTextPlugin = {
    afterDraw: function(chart) {
      if (!chart.chartArea) return;
      var ctx = chart.ctx;
      ctx.save();
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      
      var centerX = (chart.chartArea.left + chart.chartArea.right) / 2;
      var centerY = (chart.chartArea.top + chart.chartArea.bottom) / 2;
      var curDark = isDarkModeActive();

      // 获取环形图真实内圈半径并保留 15% 安全边距，杜绝文字触碰或被环形遮挡
      var meta = chart.getDatasetMeta(0);
      var innerR = 52;
      if (meta && meta.data && meta.data[0] && typeof meta.data[0].innerRadius === 'number') {
        innerR = meta.data[0].innerRadius;
      }
      var maxInnerWidth = Math.max(innerR * 1.65, 50);

      // 标题自适应字号
      var titleSize = 12;
      ctx.font = '500 ' + titleSize + 'px "Segoe UI", sans-serif';
      while (titleSize > 8.5 && ctx.measureText(centerTitle).width > maxInnerWidth) {
        titleSize -= 0.5;
        ctx.font = '500 ' + titleSize + 'px "Segoe UI", sans-serif';
      }
      ctx.fillStyle = curDark ? '#94a3b8' : '#64748b';
      ctx.fillText(centerTitle, centerX, centerY - 9);

      // 金额数字自适应字号
      var isPrivacy = document.documentElement.classList.contains('privacy-mode');
      var amtText = isPrivacy ? '••••••' : formatMoney(total);
      var amtSize = 15;
      ctx.font = '700 ' + amtSize + 'px ui-monospace, SFMono-Regular, Consolas, monospace';
      while (amtSize > 10 && ctx.measureText(amtText).width > maxInnerWidth) {
        amtSize -= 0.5;
        ctx.font = '700 ' + amtSize + 'px ui-monospace, SFMono-Regular, Consolas, monospace';
      }
      ctx.fillStyle = curDark ? '#ffffff' : '#0f172a';
      ctx.fillText(amtText, centerX, centerY + 10);
      ctx.restore();
    }
  };

  var instance = new Chart(canvas.getContext('2d'), {
    type: 'doughnut',
    data: {
      labels: labels,
      datasets: [{
        data: values.length > 0 && total > 0 ? values : [1],
        backgroundColor: values.length > 0 && total > 0 ? palette.slice(0, labels.length) : (isDark ? ['#334155'] : ['#e2e8f0']),
        hoverBackgroundColor: values.length > 0 && total > 0 ? palette.slice(0, labels.length) : (isDark ? ['#334155'] : ['#e2e8f0']),
        borderWidth: 2,
        borderColor: sliceBorder,
        hoverBorderColor: sliceBorder
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '72%',
      plugins: {
        legend: {
          display: false
        },
        tooltip: {
          enabled: total > 0,
          backgroundColor: tooltipBg,
          titleColor: tooltipTitle,
          bodyColor: tooltipBody,
          borderColor: tooltipBorder,
          borderWidth: 1,
          padding: 10,
          titleFont: { family: 'Segoe UI, sans-serif', size: 12, weight: '600' },
          bodyFont: { family: 'ui-monospace, SFMono-Regular, Consolas, monospace', size: 11 },
          callbacks: {
            label: function (item) {
              if (document.documentElement.classList.contains('privacy-mode')) {
                return ' ' + (item.label || '') + ': ••••••';
              }
              var val = item.raw || 0;
              var pct = total > 0 ? (val / total * 100).toFixed(1) : '0.0';
              return ' ' + formatMoney(val) + ' (' + pct + '%)';
            }
          }
        }
      }
    },
    plugins: [centerTextPlugin]
  });

  var legendContainer = document.getElementById(canvasId + 'Legend');
  if (legendContainer) {
    if (!total || values.length === 0) {
      var noDataText = window.t ? window.t('common.no_data', '暂无数据') : '暂无数据';
      legendContainer.innerHTML = '<div style="text-align:center; color:var(--muted); font-size:12px; padding:8px;">' + noDataText + '</div>';
    } else {
      legendContainer.innerHTML = labels.map(function (lbl, i) {
        var val = values[i] || 0;
        var pct = total > 0 ? (val / total * 100).toFixed(1) : '0.0';
        var color = palette[i % palette.length];
        return '<div class="chart-legend-item">' +
          '<div class="chart-legend-left">' +
            '<span class="chart-legend-dot" style="background-color:' + color + '"></span>' +
            '<span class="chart-legend-name" title="' + lbl + '">' + lbl + '</span>' +
          '</div>' +
          '<span class="chart-legend-right">' + formatMoney(val) + ' (' + pct + '%)</span>' +
        '</div>';
      }).join('');
    }
  }

  if (canvasId === 'overviewExpenseChart') {
    _ovExpenseChart = instance;
  } else {
    _ovIncomeChart = instance;
  }
}

// ---------------------------------------------------------------------------
// Monthly view Chart.js doughnut renderer
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Monthly view Chart.js doughnut renderer with auto-recovery & safe fallbacks
// ---------------------------------------------------------------------------

let _isFetchingChartData = false;

function fetchChartDataAndRender(month) {
  if (_isFetchingChartData) return;
  _isFetchingChartData = true;
  const query = month ? '?month=' + encodeURIComponent(month) : '';
  fetch('/api/dashboard-charts' + query, { cache: 'no-store' })
    .then(res => {
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.json();
    })
    .then(data => {
      _isFetchingChartData = false;
      if (data && data.ok) {
        window.CHART_DATA = {
          month: data.month,
          income: data.income || { labels: [window.t ? window.t('dashboard.main_income', '主业收入') : '主业收入', window.t ? window.t('dashboard.side_income', '副业收入') : '副业收入'], values: [0, 0] },
          expense: data.expense || { labels: [], values: [] }
        };
        renderDonutCharts();
      }
    })
    .catch(err => {
      _isFetchingChartData = false;
      console.warn('[Dashboard Chart] Fallback fetch failed:', err);
    });
}

function drawMonthlyDoughnut(canvasId, rawLabels, rawValues) {
  var canvas = document.getElementById(canvasId);
  if (!canvas) return;

  var isIncome = (canvasId === 'incomeChart');
  var labels = Array.isArray(rawLabels) ? rawLabels.slice() : [];
  var values = Array.isArray(rawValues) ? rawValues.map(function (v) { return Number(v) || 0; }) : [];
  var total = values.reduce(function (a, b) { return a + b; }, 0);
  var centerTitle = isIncome
    ? (window.t ? window.t('dashboard.monthly_income', '本月总收入') : '本月总收入')
    : (window.t ? window.t('dashboard.monthly_expense', '本月总支出') : '本月总支出');
  var isDark = isDarkModeActive();
  var palette = getChartPalette();
  var sliceBorder = isDark ? '#131d31' : '#ffffff';
  var centerTitleColor = isDark ? '#94a3b8' : '#6f6c66';
  var centerAmountColor = isDark ? '#ffffff' : '#10213b';
  var tooltipBg = isDark ? 'rgba(19, 29, 49, 0.96)' : 'rgba(255, 255, 255, 0.97)';
  var tooltipTitle = isDark ? '#f8fafc' : '#10213b';
  var tooltipBody = isDark ? '#cbd5e1' : '#46453f';
  var tooltipBorder = isDark ? 'rgba(255, 255, 255, 0.15)' : 'rgba(27, 27, 31, 0.1)';

  var sliceColors = values.length > 0 && total > 0
    ? labels.map(function(_, idx) { return palette[idx % palette.length]; })
    : (isDark ? ['#334155'] : ['#e0e0e0']);

  // 1. 实时更新下方图例列表 HTML（先行渲染，保证空数据时也有优雅提示，杜绝卡片内部空白）
  var legendContainer = document.getElementById(canvasId + 'Legend');
  if (legendContainer) {
    if (!total || values.length === 0) {
      var noDataText = window.t ? window.t('common.no_data', '暂无数据') : '暂无数据';
      legendContainer.innerHTML = '<div style="text-align:center; color:var(--muted); font-size:12px; padding:8px;">' + noDataText + '</div>';
    } else {
      legendContainer.innerHTML = labels.map(function (lbl, i) {
        var val = Number(values[i]) || 0;
        var pct = total > 0 ? (val / total * 100).toFixed(1) : '0.0';
        var color = palette[i % palette.length];
        return '<div class="chart-legend-item">' +
          '<div class="chart-legend-left">' +
            '<span class="chart-legend-dot" style="background-color:' + color + '"></span>' +
            '<span class="chart-legend-name" title="' + lbl + '">' + lbl + '</span>' +
          '</div>' +
          '<span class="chart-legend-right">' + formatMoney(val) + ' (' + pct + '%)</span>' +
        '</div>';
      }).join('');
    }
  }

  // 2. 环境检查：确保 Chart.js 已加载
  if (typeof Chart === 'undefined') {
    setTimeout(function () { drawMonthlyDoughnut(canvasId, labels, values); }, 120);
    return;
  }

  // 3. 检查容器尺寸，若处于隐藏或未完成 Reflow（宽为0），使用 requestAnimationFrame 延迟重绘
  if (canvas.parentElement && canvas.parentElement.clientWidth === 0) {
    requestAnimationFrame(function () {
      drawMonthlyDoughnut(canvasId, labels, values);
    });
    return;
  }

  // 4. 获取已有 Chart.js 实例并尝试复用
  var existingChart = isIncome ? _monthlyIncomeChart : _monthlyExpenseChart;
  if (!existingChart && Chart.getChart) {
    existingChart = Chart.getChart(canvas);
  }

  // 如果已有实例的主题模式与当前不符，彻底销毁并重新建立以保证色系完全匹配
  if (existingChart && existingChart._customThemeMode !== (isDark ? 'dark' : 'light')) {
    existingChart = destroyChart(existingChart);
  }

  // 5. 如果已有可用图表实例且 canvas 节点依然匹配，平滑更新数据与中心文本
  if (existingChart && existingChart.ctx && existingChart.canvas === canvas) {
    try {
      existingChart._customCenterTotal = total;
      existingChart._customThemeMode = isDark ? 'dark' : 'light';
      existingChart.data.labels = labels;
      if (!existingChart.data.datasets || existingChart.data.datasets.length === 0) {
        existingChart.data.datasets = [{}];
      }
      existingChart.data.datasets[0].data = values.length > 0 && total > 0 ? values : [1];
      existingChart.data.datasets[0].backgroundColor = sliceColors;
      existingChart.data.datasets[0].borderColor = sliceBorder;
      if (existingChart.options && existingChart.options.plugins && existingChart.options.plugins.tooltip) {
        existingChart.options.plugins.tooltip.enabled = total > 0;
        existingChart.options.plugins.tooltip.backgroundColor = tooltipBg;
        existingChart.options.plugins.tooltip.titleColor = tooltipTitle;
        existingChart.options.plugins.tooltip.bodyColor = tooltipBody;
        existingChart.options.plugins.tooltip.borderColor = tooltipBorder;
      }
      existingChart.update();
      if (isIncome) _monthlyIncomeChart = existingChart;
      else _monthlyExpenseChart = existingChart;
      return;
    } catch (updateErr) {
      console.warn('[Dashboard Chart] Smooth update failed, falling back to recreation:', updateErr);
      existingChart = null;
    }
  }

  // 6. 清理旧图表并重新创建（安全彻底销毁）
  if (isIncome) {
    _monthlyIncomeChart = destroyChart(_monthlyIncomeChart);
  } else {
    _monthlyExpenseChart = destroyChart(_monthlyExpenseChart);
  }
  if (Chart.getChart) {
    var residual = Chart.getChart(canvas);
    if (residual) destroyChart(residual);
  }

  // 自定义中心文字局部插件（不设置固定全局 id，规避插件多次注册时的冲突异常）
  var centerTextPlugin = {
    afterDraw: function(chart) {
      if (!chart.chartArea) return;
      var ctx = chart.ctx;
      ctx.save();
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      
      var centerX = (chart.chartArea.left + chart.chartArea.right) / 2;
      var centerY = (chart.chartArea.top + chart.chartArea.bottom) / 2;

      var currentTotal = chart._customCenterTotal !== undefined ? chart._customCenterTotal : total;
      var curDark = isDarkModeActive();

      // 获取环形图真实内圈半径并留出安全间距，防止文字溢出遮盖
      var meta = chart.getDatasetMeta(0);
      var innerR = 52;
      if (meta && meta.data && meta.data[0] && typeof meta.data[0].innerRadius === 'number') {
        innerR = meta.data[0].innerRadius;
      }
      var maxInnerWidth = Math.max(innerR * 1.65, 50);

      // 标题 (本月总收入 / 本月总支出) 自适应缩放
      var titleSize = 12;
      ctx.font = '500 ' + titleSize + 'px "Segoe UI", sans-serif';
      while (titleSize > 8.5 && ctx.measureText(centerTitle).width > maxInnerWidth) {
        titleSize -= 0.5;
        ctx.font = '500 ' + titleSize + 'px "Segoe UI", sans-serif';
      }
      ctx.fillStyle = curDark ? '#94a3b8' : '#6f6c66';
      ctx.fillText(centerTitle, centerX, centerY - 9);

      // 金额自适应缩放
      var isPrivacy = document.documentElement.classList.contains('privacy-mode');
      var amtText = isPrivacy ? '••••••' : formatMoney(currentTotal);
      var amtSize = 15;
      ctx.font = '700 ' + amtSize + 'px ui-monospace, SFMono-Regular, Consolas, monospace';
      while (amtSize > 10 && ctx.measureText(amtText).width > maxInnerWidth) {
        amtSize -= 0.5;
        ctx.font = '700 ' + amtSize + 'px ui-monospace, SFMono-Regular, Consolas, monospace';
      }
      ctx.fillStyle = curDark ? '#ffffff' : '#10213b';
      ctx.fillText(amtText, centerX, centerY + 10);
      ctx.restore();
    }
  };

  try {
    var instance = new Chart(canvas.getContext('2d'), {
      type: 'doughnut',
      data: {
        labels: labels,
        datasets: [{
          data: values.length > 0 && total > 0 ? values : [1],
          backgroundColor: sliceColors,
          borderWidth: 2,
          borderColor: sliceBorder,
          hoverBorderColor: sliceBorder
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '72%',
        animation: {
          duration: 450,
          easing: 'easeOutQuart'
        },
        layout: {
          padding: { top: 4, bottom: 4 }
        },
        plugins: {
          legend: {
            display: false
          },
          tooltip: {
            enabled: total > 0,
            backgroundColor: tooltipBg,
            titleColor: tooltipTitle,
            bodyColor: tooltipBody,
            borderColor: tooltipBorder,
            borderWidth: 1,
            padding: 10,
            titleFont: { family: 'Segoe UI, sans-serif', size: 12, weight: '600' },
            bodyFont: { family: 'ui-monospace, SFMono-Regular, Consolas, monospace', size: 11 },
            callbacks: {
              label: function (item) {
                if (document.documentElement.classList.contains('privacy-mode')) {
                  return ' ' + (item.label || '') + ': ••••••';
                }
                var val = item.raw || 0;
                var pct = total > 0 ? (val / total * 100).toFixed(1) : '0.0';
                return ' ' + formatMoney(val) + ' (' + pct + '%)';
              }
            }
          }
        }
      },
      plugins: [centerTextPlugin]
    });

    instance._customCenterTotal = total;
    instance._customThemeMode = isDark ? 'dark' : 'light';

    if (isIncome) {
      _monthlyIncomeChart = instance;
    } else {
      _monthlyExpenseChart = instance;
    }
  } catch (createErr) {
    console.error('[Dashboard Chart] Failed to create Chart.js instance:', createErr);
  }
}

function renderDonutCharts() {
  const hasIncomeCanvas = document.getElementById('incomeChart') !== null;
  const hasExpenseCanvas = document.getElementById('expenseChart') !== null;
  if (!hasIncomeCanvas && !hasExpenseCanvas) return;

  const monthlySec = document.getElementById('monthlySection');
  const isMonthlyVisible = !monthlySec || monthlySec.style.display !== 'none';
  if (!isMonthlyVisible) return;

  // 自愈机制：如果 window.CHART_DATA 不完整，异步兜底拉取当前月份数据并平滑重绘
  if (!window.CHART_DATA || !window.CHART_DATA.income || !window.CHART_DATA.expense) {
    const monthElem = document.querySelector('.month-nav-current');
    const month = monthElem ? monthElem.textContent.trim() : '';
    fetchChartDataAndRender(month);
    return;
  }

  if (hasIncomeCanvas && window.CHART_DATA.income) {
    try {
      drawMonthlyDoughnut('incomeChart', window.CHART_DATA.income.labels || [], window.CHART_DATA.income.values || []);
    } catch (e) {
      console.error('[Dashboard Chart] render income chart failed:', e);
    }
  }
  if (hasExpenseCanvas && window.CHART_DATA.expense) {
    try {
      drawMonthlyDoughnut('expenseChart', window.CHART_DATA.expense.labels || [], window.CHART_DATA.expense.values || []);
    } catch (e) {
      console.error('[Dashboard Chart] render expense chart failed:', e);
    }
  }
}

function attachTrendChartHover() {
  // No-op: Chart.js handles tooltips natively
}

window.addEventListener('resize', function () {
  // Chart.js handles resize automatically via responsive:true
});

function LEGACY_drawTrendBarChart(canvasId, trendData) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const container = canvas.parentElement;
  const cssWidth = container ? container.clientWidth : (canvas.clientWidth || 700);
  const cssHeight = 260;

  canvas.width = cssWidth * dpr;
  canvas.height = cssHeight * dpr;
  canvas.style.width = cssWidth + 'px';
  canvas.style.height = cssHeight + 'px';
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, cssWidth, cssHeight);

  if (!trendData || trendData.length === 0) {
    ctx.fillStyle = '#9a978f';
    ctx.font = '14px "Segoe UI", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('暂无趋势数据', cssWidth / 2, cssHeight / 2);
    return;
  }

  const padLeft = 65;
  const padRight = 20;
  const padTop = 25;
  const padBottom = 42;
  const chartW = Math.max(10, cssWidth - padLeft - padRight);
  const chartH = Math.max(10, cssHeight - padTop - padBottom);

  let maxVal = 0;
  trendData.forEach(function (d) {
    if (d.income > maxVal) maxVal = d.income;
    if (d.expense > maxVal) maxVal = d.expense;
  });
  if (maxVal <= 0) maxVal = 100;
  const magnitude = Math.pow(10, Math.floor(Math.log10(maxVal)));
  maxVal = Math.ceil(maxVal / magnitude) * magnitude;
  if (maxVal === 0) maxVal = 100;

  // 绘制横向参考线与数值
  const gridSteps = 4;
  ctx.lineWidth = 1;
  ctx.font = '11px ui-monospace, SFMono-Regular, Consolas, monospace';

  for (let i = 0; i <= gridSteps; i++) {
    const yVal = (maxVal / gridSteps) * i;
    const yPos = padTop + chartH - (i / gridSteps) * chartH;

    ctx.strokeStyle = (i === 0) ? 'rgba(27, 27, 31, 0.18)' : 'rgba(27, 27, 31, 0.06)';
    ctx.beginPath();
    ctx.moveTo(padLeft, yPos);
    ctx.lineTo(cssWidth - padRight, yPos);
    ctx.stroke();

    ctx.fillStyle = '#8a877e';
    ctx.textAlign = 'right';
    const label = yVal >= 1000 ? (yVal / 1000).toFixed(yVal % 1000 === 0 ? 0 : 1) + 'k' : yVal.toFixed(0);
    ctx.fillText('RM ' + label, padLeft - 8, yPos + 4);
  }

  // 绘制柱体
  const count = trendData.length;
  const groupWidth = chartW / count;
  const barGap = Math.max(2, Math.min(6, groupWidth * 0.08));
  const maxSingleBar = 24;
  const availBarW = Math.max(2, (groupWidth - barGap * 3) / 2);
  const singleBarW = Math.min(maxSingleBar, availBarW);

  canvas._barGroups = [];

  trendData.forEach(function (d, idx) {
    const centerX = padLeft + idx * groupWidth + groupWidth / 2;
    const incomeH = maxVal > 0 ? (d.income / maxVal) * chartH : 0;
    const expenseH = maxVal > 0 ? (d.expense / maxVal) * chartH : 0;

    const incomeX = centerX - singleBarW - barGap / 2;
    const expenseX = centerX + barGap / 2;
    const incomeY = padTop + chartH - incomeH;
    const expenseY = padTop + chartH - expenseH;

    canvas._barGroups.push({
      index: idx,
      data: d,
      left: padLeft + idx * groupWidth,
      right: padLeft + (idx + 1) * groupWidth,
      centerX: centerX
    });

    const isHovered = (trendHoverIndex === idx);

    // 柱形背景微光指示（悬停时）
    if (isHovered) {
      ctx.fillStyle = 'rgba(182, 144, 47, 0.08)';
      ctx.fillRect(padLeft + idx * groupWidth + 1, padTop, groupWidth - 2, chartH);
    }

    // 绘制收入柱
    ctx.fillStyle = isHovered ? '#1e3f6f' : '#10213b';
    if (ctx.roundRect) {
      ctx.beginPath();
      ctx.roundRect(incomeX, incomeY, singleBarW, incomeH, [3, 3, 0, 0]);
      ctx.fill();
    } else {
      ctx.fillRect(incomeX, incomeY, singleBarW, incomeH);
    }

    // 绘制支出柱
    ctx.fillStyle = isHovered ? '#bf5368' : '#a8475a';
    if (ctx.roundRect) {
      ctx.beginPath();
      ctx.roundRect(expenseX, expenseY, singleBarW, expenseH, [3, 3, 0, 0]);
      ctx.fill();
    } else {
      ctx.fillRect(expenseX, expenseY, singleBarW, expenseH);
    }

    // X 轴月份标签
    ctx.fillStyle = isHovered ? '#10213b' : '#6f6c66';
    ctx.font = isHovered
      ? '600 11px ui-monospace, SFMono-Regular, Consolas, monospace'
      : '11px ui-monospace, SFMono-Regular, Consolas, monospace';
    ctx.textAlign = 'center';

    const shortLabel = d.month.length >= 7 ? d.month.slice(2).replace('-', '/') : d.month;
    ctx.fillText(shortLabel, centerX, padTop + chartH + 18);
  });
}

function attachTrendChartHover() {
  const canvas = document.getElementById('overviewTrendChart');
  const tooltip = document.getElementById('trendTooltip');
  if (!canvas || !tooltip || canvas._hoverAttached) return;
  canvas._hoverAttached = true;

  canvas.addEventListener('mousemove', function (e) {
    if (!canvas._barGroups || !canvas._barGroups.length) return;
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    let found = null;
    for (let i = 0; i < canvas._barGroups.length; i++) {
      const g = canvas._barGroups[i];
      if (mouseX >= g.left && mouseX <= g.right) {
        found = g;
        break;
      }
    }

    if (found) {
      if (trendHoverIndex !== found.index) {
        trendHoverIndex = found.index;
        if (currentOverviewData) {
          drawTrendBarChart('overviewTrendChart', currentOverviewData.trend || []);
        }
      }
      const d = found.data;
      tooltip.style.display = 'block';
      tooltip.innerHTML =
        '<div class="tt-header">' + d.month + '</div>' +
        '<div class="tt-row"><span class="tt-label"><span class="legend-dot dot-income"></span> 收入</span> <span class="tt-val income">' + formatMoney(d.income) + '</span></div>' +
        '<div class="tt-row"><span class="tt-label"><span class="legend-dot dot-expense"></span> 支出</span> <span class="tt-val expense">' + formatMoney(d.expense) + '</span></div>' +
        '<div class="tt-sep"></div>' +
        '<div class="tt-row"><span class="tt-label">结余</span> <span class="tt-val ' + (d.balance >= 0 ? 'income' : 'expense') + '">' + formatMoney(d.balance) + '</span></div>';

      const tipW = tooltip.offsetWidth || 140;
      let tipX = mouseX + 16;
      if (tipX + tipW > rect.width) {
        tipX = mouseX - tipW - 16;
      }
      let tipY = mouseY - 25;
      if (tipY < 8) tipY = 8;
      tooltip.style.left = tipX + 'px';
      tooltip.style.top = tipY + 'px';
    } else {
      if (trendHoverIndex !== -1) {
        trendHoverIndex = -1;
        if (currentOverviewData) {
          drawTrendBarChart('overviewTrendChart', currentOverviewData.trend || []);
        }
      }
      tooltip.style.display = 'none';
    }
  });

  canvas.addEventListener('mouseleave', function () {
    if (trendHoverIndex !== -1) {
      trendHoverIndex = -1;
      if (currentOverviewData) {
        drawTrendBarChart('overviewTrendChart', currentOverviewData.trend || []);
      }
    }
    tooltip.style.display = 'none';
  });
}

window.addEventListener('resize', function () {
  if (currentOverviewData && document.getElementById('overviewSection') && document.getElementById('overviewSection').style.display !== 'none') {
    renderOverview(currentOverviewData);
  }
});

function refreshChartsOnThemeChange() {
  _monthlyIncomeChart = destroyChart(_monthlyIncomeChart);
  _monthlyExpenseChart = destroyChart(_monthlyExpenseChart);
  _ovTrendChart = destroyChart(_ovTrendChart);
  _ovExpenseChart = destroyChart(_ovExpenseChart);
  _ovIncomeChart = destroyChart(_ovIncomeChart);

  if (window.CHART_DATA && document.getElementById('incomeChart') && (!document.getElementById('monthlySection') || document.getElementById('monthlySection').style.display !== 'none')) {
    drawMonthlyDoughnut('incomeChart', window.CHART_DATA.income.labels || [], window.CHART_DATA.income.values || []);
    drawMonthlyDoughnut('expenseChart', window.CHART_DATA.expense.labels || [], window.CHART_DATA.expense.values || []);
  }
  if (currentOverviewData && document.getElementById('overviewSection') && document.getElementById('overviewSection').style.display !== 'none') {
    renderOverview(currentOverviewData);
  }
}
window.addEventListener('app:theme-changed', refreshChartsOnThemeChange);

// 监听系统/浏览器浅色与暗色模式切换，自动重新渲染图表与高对比度调色盘
if (window.matchMedia) {
  try {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
      refreshChartsOnThemeChange();
    });
  } catch (e) {}
}

// 兼容 SPA 动态注入时 DOMContentLoaded 已过时的场景
const _origDocAddEventListener = Document.prototype.addEventListener;
Document.prototype.addEventListener = function (type, listener, options) {
  if (type === 'DOMContentLoaded' && document.readyState !== 'loading') {
    try {
      listener.call(this, new Event('DOMContentLoaded'));
    } catch (e) {
      console.error(e);
    }
    return;
  }
  return _origDocAddEventListener.call(this, type, listener, options);
};

// ---------- 实时网络与云端同步状态 (Live Sync Status) ----------

let _healthCheckTimer = null;
let _syncListenersAttached = false;

function updateSyncBadge(state) {
  const desktopBadge = document.getElementById('desktopSyncStatusBadge');
  const desktopDot = document.getElementById('desktopSyncStatusDot');
  const desktopText = document.getElementById('desktopSyncStatusText');

  const mobileBadge = document.getElementById('syncStatusBadge');
  const mobileDot = document.getElementById('syncStatusDot');
  const mobileText = document.getElementById('syncStatusText');

  const badges = [desktopBadge, mobileBadge].filter(Boolean);
  const dots = [desktopDot, mobileDot].filter(Boolean);
  const texts = [desktopText, mobileText].filter(Boolean);

  if (state === 'online') {
    badges.forEach(b => {
      b.classList.remove('badge-offline', 'badge-unreachable');
      b.title = window.t ? window.t('swal.online_service_ok', '网络良好，服务正常') : '网络良好，服务正常';
    });
    dots.forEach(d => {
      d.classList.remove('dot-offline', 'dot-unreachable');
    });
    texts.forEach(t => {
      t.textContent = '';
    });
  } else if (state === 'offline') {
    badges.forEach(b => {
      b.classList.remove('badge-unreachable');
      b.classList.add('badge-offline');
      b.title = window.t ? window.t('swal.device_offline', '当前设备处于离线状态，无法连接互联网') : '当前设备处于离线状态，无法连接互联网';
    });
    dots.forEach(d => {
      d.classList.remove('dot-unreachable');
      d.classList.add('dot-offline');
    });
    texts.forEach(t => {
      t.textContent = window.t ? window.t('swal.status_offline', '离线') : '离线';
    });
  } else if (state === 'unreachable') {
    badges.forEach(b => {
      b.classList.remove('badge-offline');
      b.classList.add('badge-unreachable');
      b.title = window.t ? window.t('swal.server_disconnected', '无法连接到云端记账服务器') : '无法连接到云端记账服务器';
    });
    dots.forEach(d => {
      d.classList.remove('dot-offline');
      d.classList.add('dot-unreachable');
    });
    texts.forEach(t => {
      t.textContent = window.t ? window.t('swal.status_disconnected', '断开') : '断开';
    });
  }
}

let _currentDataVersion = (typeof window !== 'undefined' && window.INITIAL_DATA_VERSION) ? window.INITIAL_DATA_VERSION : null;
let _isPollingActive = false;
const _notifiedAutoTrackTxIds = new Set();

function flashSyncBadgeUpdated() {
  const badges = [document.getElementById('syncStatusBadge'), document.getElementById('desktopSyncStatusBadge')].filter(Boolean);
  const texts = [document.getElementById('syncStatusText'), document.getElementById('desktopSyncStatusText')].filter(Boolean);

  badges.forEach(b => {
    b.classList.add('live-active', 'live-pulse');
  });
  texts.forEach(t => {
    t.textContent = window.t ? window.t('swal.status_updated', '已更新') : '已更新';
  });

  setTimeout(() => {
    badges.forEach(b => {
      b.classList.remove('live-pulse');
    });
    texts.forEach(t => {
      t.textContent = '';
    });
  }, 3000);
}

function refreshDashboardPartials(event) {
  const monthElem = document.querySelector('.month-nav-current');
  const month = monthElem ? monthElem.textContent.trim() : '';

  // 1. 局部刷新 4 张核心统计卡片
  fetch('/partial/dashboard-cards' + (month ? '?month=' + encodeURIComponent(month) : ''), {
    cache: 'no-store'
  })
    .then(res => res.text())
    .then(html => {
      const wrap = document.getElementById('dashboardSummaryCardsWrap');
      if (wrap) {
        wrap.innerHTML = html;
        wrap.querySelectorAll('.card').forEach(c => c.classList.add('card-updated'));
        setTimeout(() => {
          wrap.querySelectorAll('.card').forEach(c => c.classList.remove('card-updated'));
        }, 1500);
      }
    })
    .catch(console.error);

  // 2. 局部重新拉取当月图表数据并平滑重绘
  fetch('/api/dashboard-charts' + (month ? '?month=' + encodeURIComponent(month) : ''), {
    cache: 'no-store'
  })
    .then(res => res.json())
    .then(data => {
      if (!data || !data.ok) return;
      window.CHART_DATA = {
        month: data.month || month,
        income: data.income || { labels: ['主业收入', '副业收入'], values: [0, 0] },
        expense: data.expense || { labels: [], values: [] }
      };
      if (typeof renderDonutCharts === 'function') {
        renderDonutCharts();
      }
      // 图表面板实时更新脉冲高亮动画
      ['incomeChart', 'expenseChart'].forEach(id => {
        const p = document.getElementById(id)?.closest('.panel');
        if (p) {
          p.classList.add('card-updated');
          setTimeout(() => p.classList.remove('card-updated'), 1500);
        }
      });
    })
    .catch(console.error);

  // 3. 局部实时刷新月度预算监控卡片
  const budgetWrap = document.getElementById('dashboardBudgetWrap');
  if (budgetWrap) {
    fetch('/partial/dashboard-budget' + (month ? '?month=' + encodeURIComponent(month) : ''), {
      cache: 'no-store'
    })
      .then(res => res.text())
      .then(html => {
        budgetWrap.innerHTML = html;
        budgetWrap.querySelectorAll('.budget-dash-card').forEach(c => c.classList.add('card-updated'));
        setTimeout(() => {
          budgetWrap.querySelectorAll('.budget-dash-card').forEach(c => c.classList.remove('card-updated'));
        }, 1500);
      })
      .catch(console.error);
  }

  // 4. 如果在总体概览 Tab，重新拉取概览数据
  const ovSec = document.getElementById('overviewSection');
  if (ovSec && ovSec.style.display !== 'none' && typeof loadOverviewData === 'function') {
    loadOverviewData(currentOverviewRange || 'all');
  }
}

function refreshRecordsPartials(event) {
  const container = document.getElementById('recordsLiveContainer');
  if (!container) return;

  const filterForm = document.querySelector('form.filters');
  const params = new URLSearchParams();
  params.set('partial', '1');

  if (filterForm) {
    const formData = new FormData(filterForm);
    for (const [k, v] of formData.entries()) {
      if (v) params.set(k, v);
    }
  }

  fetch('/records?' + params.toString(), {
    cache: 'no-store'
  })
    .then(res => res.text())
    .then(html => {
      container.innerHTML = html;
      if (typeof htmx !== 'undefined') {
        htmx.process(container);
      }
      if (typeof setupRecordsObserver === 'function') {
        setupRecordsObserver();
      }
      if (typeof updateBatchBar === 'function') {
        updateBatchBar();
      }

      // 如果有新添加的交易 ID，添加脉冲动画
      if (event && event.data && event.data.id) {
        const row = document.getElementById('row-' + event.data.id);
        if (row) {
          row.classList.add('row-highlight-new');
          setTimeout(() => row.classList.remove('row-highlight-new'), 1900);
        }
      }
    })
    .catch(console.error);
}

function handleRealtimeUpdate(event) {
  flashSyncBadgeUpdated();

  const isDashboard = document.getElementById('dashboardSummaryCardsWrap') !== null;
  const isRecords = document.getElementById('recordsLiveContainer') !== null;

  if (isDashboard) {
    refreshDashboardPartials(event);
  } else if (isRecords) {
    refreshRecordsPartials(event);
  } else {
    // 全站其它所有页面（分类洞察、固定收支、分类管理、AA分账等）进行实时局部更新
    const mainContainer = document.getElementById('mainContainer');
    if (mainContainer && typeof htmx !== 'undefined') {
      htmx.ajax('GET', window.location.href, {
        target: '#mainContainer',
        swap: 'innerHTML',
        headers: { 'HX-Request': 'true' }
      });
    }
  }

  // 仅在被动接收到外部自动记账（如银行通知/短信同步）且未曾提示过时才显示通知与轻提示
  // 手动记账与常规后台同步保持静默无感更新，坚决不弹出冗余打扰的“⚡ 实时记账成功”
  if (event && event.type === 'auto_track' && event.data) {
    const tx = event.data;
    const txId = tx.id || tx.tx_id || `${tx.amount}_${tx.timestamp || ''}`;
    if (!_notifiedAutoTrackTxIds.has(txId)) {
      _notifiedAutoTrackTxIds.add(txId);
      if (_notifiedAutoTrackTxIds.size > 100) {
        const first = _notifiedAutoTrackTxIds.values().next().value;
        _notifiedAutoTrackTxIds.delete(first);
      }

      const sym = window.LEDGER_CURRENCY_SYMBOL || 'RM';
      const amountStr = tx.amount ? ` ${sym} ` + Number(tx.amount).toFixed(2) : '';
      const noteStr = tx.note ? `【${tx.note}】` : '';
      const catStr = tx.category ? `[${tx.category}] ` : '';
      const title = `🎉 自动记账入账${amountStr}`;
      const bodyStr = `${catStr}${noteStr} 记账成功`.trim();

      sendPhoneNotification(title, bodyStr);

      if (typeof Swal !== 'undefined') {
        Swal.fire({
          toast: true,
          position: 'top-end',
          icon: 'success',
          title: title,
          showConfirmButton: false,
          timer: 3500,
          background: 'var(--surface)',
          color: 'var(--navy)'
        });
      }
    }
  }
}

function checkRealtimeUpdates() {
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    updateSyncBadge('offline');
    return;
  }
  if (_isPollingActive) return;
  _isPollingActive = true;

  const url = '/api/realtime/check' + (_currentDataVersion !== null ? '?v=' + _currentDataVersion : '');

  fetch(url, {
    method: 'GET',
    cache: 'no-store',
    headers: { 'X-Requested-With': 'XMLHttpRequest' }
  })
    .then(res => {
      if (res.ok) {
        updateSyncBadge('online');
        return res.json();
      }
      updateSyncBadge('unreachable');
      return null;
    })
    .then(data => {
      _isPollingActive = false;
      if (!data || !data.ok) return;

      if (_currentDataVersion === null) {
        _currentDataVersion = data.version;
        return;
      }

      if (data.has_update && data.version > _currentDataVersion) {
        _currentDataVersion = data.version;
        window.INITIAL_DATA_VERSION = data.version;
        if (typeof InstantNav !== 'undefined' && typeof InstantNav.clearCache === 'function') {
          InstantNav.clearCache();
        }
        handleRealtimeUpdate(data.event);
      }
    })
    .catch(() => {
      _isPollingActive = false;
      if (typeof navigator !== 'undefined' && !navigator.onLine) {
        updateSyncBadge('offline');
      } else {
        updateSyncBadge('unreachable');
      }
    });
}

function initLiveSyncStatus() {
  checkRealtimeUpdates();

  if (!_syncListenersAttached && typeof window !== 'undefined') {
    _syncListenersAttached = true;
    window.addEventListener('online', function () {
      updateSyncBadge('online');
      checkRealtimeUpdates();
    });
    window.addEventListener('offline', function () {
      updateSyncBadge('offline');
    });
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') {
        checkRealtimeUpdates();
      }
    });
  }

  if (_healthCheckTimer) {
    clearInterval(_healthCheckTimer);
  }
  // 活跃状态下每 2.5 秒进行一次轻量级版本检查
  _healthCheckTimer = setInterval(function () {
    if (document.visibilityState === 'visible') {
      checkRealtimeUpdates();
    }
  }, 2500);
}

// 进度条控制
let _progressTimer = null;

function showProgressBar() {
  const bar = document.getElementById('appProgressBar');
  if (!bar) return;
  bar.classList.add('loading');
  bar.style.width = '35%';
  clearTimeout(_progressTimer);
  _progressTimer = setTimeout(() => {
    bar.style.width = '78%';
  }, 120);
}

function finishProgressBar() {
  const bar = document.getElementById('appProgressBar');
  if (!bar) return;
  clearTimeout(_progressTimer);
  bar.style.width = '100%';
  setTimeout(() => {
    bar.classList.remove('loading');
    bar.style.width = '0%';
  }, 240);
}

// 导航高亮同步
function updateActiveNav(urlStr) {
  let path = urlStr || window.location.pathname;
  try {
    const url = new URL(urlStr, window.location.origin);
    path = url.pathname;
  } catch (e) {}

  let navKey = 'index';
  if (path === '/') navKey = 'index';
  else if (path.startsWith('/records')) navKey = 'records';
  else if (path.startsWith('/split-bill')) navKey = 'split-bill';
  else if (path.startsWith('/auto-track')) navKey = 'auto-track';
  else if (path.startsWith('/accounts')) navKey = 'accounts';
  else if (path.startsWith('/liabilities')) navKey = 'liabilities';
  else if (path.startsWith('/subscriptions')) navKey = 'subscriptions';
  else if (path.startsWith('/recurring')) navKey = 'recurring';
  else if (path.startsWith('/categories/insights')) navKey = 'insights';
  else if (path.startsWith('/categories')) navKey = 'categories';
  else if (path.startsWith('/import')) navKey = 'import';
  else if (path.startsWith('/settings')) navKey = 'settings';

  // 同步桌面端导航链接高亮
  document.querySelectorAll('.desktop-nav-links a[data-nav]').forEach(a => {
    a.classList.toggle('active', a.dataset.nav === navKey);
  });

  // 同步桌面端下拉菜单父级按钮高亮 (管理 / 工具)
  document.querySelectorAll('.nav-group').forEach(group => {
    const trigger = group.querySelector('.nav-group-trigger');
    if (trigger) {
      const hasActiveChild = Array.from(group.querySelectorAll('a[data-nav]'))
        .some(a => a.dataset.nav === navKey);
      trigger.classList.toggle('active', hasActiveChild);
    }
  });

  // 同步手机端底部导航高亮（如果是二级功能如偏好设置、账户管理、固定收支、负债分期、订阅大厅、分类管理、分类洞察、批量导入，则高亮“更多”按钮）
  const isSecondaryPage = ['settings', 'accounts', 'liabilities', 'subscriptions', 'recurring', 'insights', 'categories', 'import', 'auto-track'].includes(navKey);
  document.querySelectorAll('.mobile-bottom-nav .bnav-item').forEach(btn => {
    if (btn.id === 'btnMoreSheet') {
      btn.classList.toggle('active', isSecondaryPage);
    } else if (btn.dataset.nav) {
      btn.classList.toggle('active', btn.dataset.nav === navKey);
    }
  });

  // 同步手机端底部抽屉高亮
  document.querySelectorAll('.sheet-tile').forEach(tile => {
    tile.classList.toggle('active', tile.dataset.nav === navKey);
  });
}

// 页面全局生命周期初始化函数
function initPageLifecycle() {
  onTypeChange();
  populateCategories();
  toggleTypeCol();
  attachDeleteConfirm();
  attachQuickAddFormAjax();
  attachCategoryFormAjax();
  attachRecurringFormsAjax();
  attachSplitBillFormAjax();
  attachImportFormValidation();
  attachNlpForm();
  showSuccessToasts();
  showErrorAlerts();
  syncSegStyles();
  initLiveSyncStatus();
  updateActiveNav(window.location.pathname);

  if (typeof renderDonutCharts === 'function') {
    renderDonutCharts();
  }

  // 全局事件代理：点击表格行任意位置快速勾选/取消勾选（批量删除、局部刷新后依然永久生效）
  if (typeof window !== 'undefined' && !window._recordRowSelectDelegated) {
    window._recordRowSelectDelegated = true;
    document.addEventListener('click', function (e) {
      if (e.target.closest('.actions') || e.target.closest('a') || e.target.closest('button') || e.target.closest('input')) {
        return;
      }
      const row = e.target.closest('tr.record-row');
      if (!row) return;
      const checkbox = row.querySelector('.record-checkbox');
      if (checkbox) {
        checkbox.checked = !checkbox.checked;
        row.classList.toggle('selected-row', checkbox.checked);
        if (typeof updateBatchBar === 'function') updateBatchBar();
      }
    });
  }

  // 首次用户交互时预请求手机原生系统通知权限
  if (typeof window !== 'undefined' && !window._notifPrompted) {
    window._notifPrompted = true;
    document.addEventListener('click', function reqOnce() {
      requestPhoneNotificationPermission();
      document.removeEventListener('click', reqOnce);
    }, { once: true });
  }

  // 检查 URL 是否指定了总体视图
  if (window.location.hash === '#overview' || window.location.search.indexOf('view=overview') !== -1) {
    if (typeof switchDashboardView === 'function') {
      switchDashboardView('overview');
    }
  }
}

// ---------- 智能微光骨架屏渲染器 (Skeleton Screen Renderer) ----------

function getDashboardSkeletonHtml() {
  return `
  <div class="skeleton-screen">
    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; flex-wrap:wrap; gap:12px;">
      <div class="skeleton-shimmer" style="height:34px; width:150px; border-radius:999px;"></div>
      <div style="display:flex; gap:8px;">
        <div class="skeleton-shimmer" style="height:32px; width:90px; border-radius:999px;"></div>
        <div class="skeleton-shimmer" style="height:32px; width:90px; border-radius:999px;"></div>
      </div>
    </div>
    <div class="cards cards-four" style="margin-bottom:24px;">
      <div class="skeleton-card">
        <div class="skeleton-shimmer" style="height:13px; width:55px; margin-bottom:12px;"></div>
        <div class="skeleton-shimmer" style="height:28px; width:110px;"></div>
      </div>
      <div class="skeleton-card">
        <div class="skeleton-shimmer" style="height:13px; width:55px; margin-bottom:12px;"></div>
        <div class="skeleton-shimmer" style="height:28px; width:110px;"></div>
      </div>
      <div class="skeleton-card">
        <div class="skeleton-shimmer" style="height:13px; width:55px; margin-bottom:12px;"></div>
        <div class="skeleton-shimmer" style="height:28px; width:110px;"></div>
      </div>
      <div class="skeleton-card">
        <div class="skeleton-shimmer" style="height:13px; width:55px; margin-bottom:12px;"></div>
        <div class="skeleton-shimmer" style="height:28px; width:110px;"></div>
      </div>
    </div>
    <div class="panels" style="margin-bottom:22px;">
      <div class="panel skeleton-panel" style="flex:1; min-width:300px;">
        <div class="skeleton-shimmer" style="height:20px; width:85px; margin-bottom:18px;"></div>
        <div style="display:flex; gap:10px; margin-bottom:16px;">
          <div class="skeleton-shimmer" style="height:34px; flex:1; border-radius:8px;"></div>
          <div class="skeleton-shimmer" style="height:34px; flex:1; border-radius:8px;"></div>
          <div class="skeleton-shimmer" style="height:34px; flex:1; border-radius:8px;"></div>
        </div>
        <div style="display:flex; gap:12px; margin-bottom:14px;">
          <div class="skeleton-shimmer" style="height:38px; flex:1; border-radius:8px;"></div>
          <div class="skeleton-shimmer" style="height:38px; flex:1; border-radius:8px;"></div>
        </div>
        <div style="display:flex; gap:8px; margin:12px 0 16px;">
          <div class="skeleton-shimmer" style="height:28px; width:75px; border-radius:999px;"></div>
          <div class="skeleton-shimmer" style="height:28px; width:75px; border-radius:999px;"></div>
          <div class="skeleton-shimmer" style="height:28px; width:75px; border-radius:999px;"></div>
        </div>
        <div class="skeleton-shimmer" style="height:38px; width:90px; border-radius:8px;"></div>
      </div>
      <div class="panel skeleton-panel" style="flex:1; min-width:300px;">
        <div class="skeleton-shimmer" style="height:20px; width:130px; margin-bottom:14px;"></div>
        <div class="skeleton-shimmer" style="height:14px; width:80%; margin-bottom:22px;"></div>
        <div class="skeleton-shimmer" style="height:42px; width:100%; border-radius:8px; margin-bottom:16px;"></div>
        <div class="skeleton-shimmer" style="height:38px; width:96px; border-radius:8px;"></div>
      </div>
    </div>
    <div class="panels">
      <div class="panel skeleton-panel" style="flex:1; min-width:300px; display:flex; flex-direction:column; align-items:center;">
        <div class="skeleton-shimmer" style="height:18px; width:140px; align-self:flex-start; margin-bottom:20px;"></div>
        <div class="skeleton-shimmer" style="height:130px; width:130px; border-radius:50%; margin-bottom:18px;"></div>
        <div class="skeleton-shimmer" style="height:14px; width:65%; border-radius:4px;"></div>
      </div>
      <div class="panel skeleton-panel" style="flex:1; min-width:300px; display:flex; flex-direction:column; align-items:center;">
        <div class="skeleton-shimmer" style="height:18px; width:120px; align-self:flex-start; margin-bottom:20px;"></div>
        <div class="skeleton-shimmer" style="height:130px; width:130px; border-radius:50%; margin-bottom:18px;"></div>
        <div class="skeleton-shimmer" style="height:14px; width:65%; border-radius:4px;"></div>
      </div>
    </div>
  </div>`;
}

function getRecordsSkeletonHtml() {
  return `
  <div class="skeleton-screen">
    <div class="panel skeleton-panel" style="margin-bottom:20px;">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; flex-wrap:wrap; gap:10px;">
        <div class="skeleton-shimmer" style="height:24px; width:120px;"></div>
        <div style="display:flex; gap:8px;">
          <div class="skeleton-shimmer" style="height:32px; width:80px; border-radius:8px;"></div>
          <div class="skeleton-shimmer" style="height:32px; width:80px; border-radius:8px;"></div>
        </div>
      </div>
      <div style="display:flex; gap:10px; flex-wrap:wrap;">
        <div class="skeleton-shimmer" style="height:36px; width:140px; border-radius:8px;"></div>
        <div class="skeleton-shimmer" style="height:36px; width:140px; border-radius:8px;"></div>
        <div class="skeleton-shimmer" style="height:36px; width:110px; border-radius:8px;"></div>
        <div class="skeleton-shimmer" style="height:36px; flex:1; min-width:180px; border-radius:8px;"></div>
        <div class="skeleton-shimmer" style="height:36px; width:70px; border-radius:8px;"></div>
      </div>
    </div>
    <div class="cards cards-four" style="margin-bottom:20px;">
      <div class="skeleton-card"><div class="skeleton-shimmer" style="height:13px; width:50px; margin-bottom:10px;"></div><div class="skeleton-shimmer" style="height:22px; width:85px;"></div></div>
      <div class="skeleton-card"><div class="skeleton-shimmer" style="height:13px; width:50px; margin-bottom:10px;"></div><div class="skeleton-shimmer" style="height:22px; width:85px;"></div></div>
      <div class="skeleton-card"><div class="skeleton-shimmer" style="height:13px; width:50px; margin-bottom:10px;"></div><div class="skeleton-shimmer" style="height:22px; width:85px;"></div></div>
      <div class="skeleton-card"><div class="skeleton-shimmer" style="height:13px; width:50px; margin-bottom:10px;"></div><div class="skeleton-shimmer" style="height:22px; width:85px;"></div></div>
    </div>
    <div class="panel skeleton-panel" style="padding:0; overflow:hidden;">
      <div style="padding:16px 20px; border-bottom:1px solid var(--border); display:flex; justify-content:space-between; align-items:center;">
        <div class="skeleton-shimmer" style="height:16px; width:100px;"></div>
        <div class="skeleton-shimmer" style="height:28px; width:90px; border-radius:6px;"></div>
      </div>
      <div class="skeleton-row"><div class="skeleton-shimmer" style="height:15px; width:80px;"></div><div class="skeleton-shimmer" style="height:22px; width:55px; border-radius:999px;"></div><div class="skeleton-shimmer" style="height:15px; flex:1; margin:0 12px;"></div><div class="skeleton-shimmer" style="height:18px; width:80px;"></div><div class="skeleton-shimmer" style="height:20px; width:50px; border-radius:4px;"></div></div>
      <div class="skeleton-row"><div class="skeleton-shimmer" style="height:15px; width:80px;"></div><div class="skeleton-shimmer" style="height:22px; width:55px; border-radius:999px;"></div><div class="skeleton-shimmer" style="height:15px; flex:1; margin:0 12px;"></div><div class="skeleton-shimmer" style="height:18px; width:80px;"></div><div class="skeleton-shimmer" style="height:20px; width:50px; border-radius:4px;"></div></div>
      <div class="skeleton-row"><div class="skeleton-shimmer" style="height:15px; width:80px;"></div><div class="skeleton-shimmer" style="height:22px; width:55px; border-radius:999px;"></div><div class="skeleton-shimmer" style="height:15px; flex:1; margin:0 12px;"></div><div class="skeleton-shimmer" style="height:18px; width:80px;"></div><div class="skeleton-shimmer" style="height:20px; width:50px; border-radius:4px;"></div></div>
      <div class="skeleton-row"><div class="skeleton-shimmer" style="height:15px; width:80px;"></div><div class="skeleton-shimmer" style="height:22px; width:55px; border-radius:999px;"></div><div class="skeleton-shimmer" style="height:15px; flex:1; margin:0 12px;"></div><div class="skeleton-shimmer" style="height:18px; width:80px;"></div><div class="skeleton-shimmer" style="height:20px; width:50px; border-radius:4px;"></div></div>
      <div class="skeleton-row"><div class="skeleton-shimmer" style="height:15px; width:80px;"></div><div class="skeleton-shimmer" style="height:22px; width:55px; border-radius:999px;"></div><div class="skeleton-shimmer" style="height:15px; flex:1; margin:0 12px;"></div><div class="skeleton-shimmer" style="height:18px; width:80px;"></div><div class="skeleton-shimmer" style="height:20px; width:50px; border-radius:4px;"></div></div>
    </div>
  </div>`;
}

function getGenericSkeletonHtml() {
  return `
  <div class="skeleton-screen">
    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:22px;">
      <div class="skeleton-shimmer" style="height:28px; width:160px; border-radius:6px;"></div>
      <div class="skeleton-shimmer" style="height:32px; width:100px; border-radius:8px;"></div>
    </div>
    <div class="cards cards-four" style="margin-bottom:20px;">
      <div class="skeleton-card"><div class="skeleton-shimmer" style="height:13px; width:60px; margin-bottom:10px;"></div><div class="skeleton-shimmer" style="height:24px; width:90px;"></div></div>
      <div class="skeleton-card"><div class="skeleton-shimmer" style="height:13px; width:60px; margin-bottom:10px;"></div><div class="skeleton-shimmer" style="height:24px; width:90px;"></div></div>
      <div class="skeleton-card"><div class="skeleton-shimmer" style="height:13px; width:60px; margin-bottom:10px;"></div><div class="skeleton-shimmer" style="height:24px; width:90px;"></div></div>
      <div class="skeleton-card"><div class="skeleton-shimmer" style="height:13px; width:60px; margin-bottom:10px;"></div><div class="skeleton-shimmer" style="height:24px; width:90px;"></div></div>
    </div>
    <div class="panel skeleton-panel" style="margin-bottom:20px;">
      <div class="skeleton-shimmer" style="height:20px; width:120px; margin-bottom:16px;"></div>
      <div class="skeleton-shimmer" style="height:14px; width:75%; margin-bottom:20px;"></div>
      <div style="display:flex; gap:12px; margin-bottom:14px;">
        <div class="skeleton-shimmer" style="height:40px; flex:1; border-radius:8px;"></div>
        <div class="skeleton-shimmer" style="height:40px; flex:1; border-radius:8px;"></div>
      </div>
      <div class="skeleton-shimmer" style="height:42px; width:120px; border-radius:8px;"></div>
    </div>
    <div class="panel skeleton-panel" style="padding:0; overflow:hidden;">
      <div class="skeleton-row"><div class="skeleton-shimmer" style="height:16px; width:100px;"></div><div class="skeleton-shimmer" style="height:16px; flex:1; margin:0 16px;"></div><div class="skeleton-shimmer" style="height:18px; width:80px;"></div></div>
      <div class="skeleton-row"><div class="skeleton-shimmer" style="height:16px; width:100px;"></div><div class="skeleton-shimmer" style="height:16px; flex:1; margin:0 16px;"></div><div class="skeleton-shimmer" style="height:18px; width:80px;"></div></div>
      <div class="skeleton-row"><div class="skeleton-shimmer" style="height:16px; width:100px;"></div><div class="skeleton-shimmer" style="height:16px; flex:1; margin:0 16px;"></div><div class="skeleton-shimmer" style="height:18px; width:80px;"></div></div>
    </div>
  </div>`;
}

function renderSkeletonScreen(urlStr) {
  let path = urlStr || '';
  try {
    const u = new URL(urlStr, window.location.origin);
    path = u.pathname;
  } catch (e) {}

  if (path === '/' || path === '/index' || path === '') {
    return getDashboardSkeletonHtml();
  }
  if (path.startsWith('/records')) {
    return getRecordsSkeletonHtml();
  }
  return getGenericSkeletonHtml();
}

// ---------- HTMX 页面平滑切换集成 (Smooth Navigation with HTMX) ----------

// ===========================================================================
// 极速智能导航引擎 (InstantNav Engine): 预测性预取 + 会话级页面缓存 + 防抖骨架屏 + View Transitions
// ===========================================================================

const InstantNav = {
  cache: new Map(), // url -> { html, timestamp, dataVersion }
  prefetchInflight: new Map(), // url -> Promise<string|null>
  skeletonTimer: null,
  maxCacheAgeMs: 45000, // 45 秒内平滑秒开 (0ms)
  showProgress: showProgressBar,
  finishProgress: finishProgressBar,
  updateActiveNav: updateActiveNav,

  cleanUrl(urlStr) {
    if (!urlStr) return '';
    try {
      const u = new URL(urlStr, window.location.origin);
      return u.pathname + u.search;
    } catch (e) {
      return urlStr;
    }
  },

  isNavigable(urlStr) {
    if (!urlStr || urlStr.startsWith('#') || urlStr.startsWith('javascript:')) return false;
    const clean = this.cleanUrl(urlStr);
    // 排除登出、下载、静态资源文件与独立 API
    if (clean.startsWith('/logout') || clean.startsWith('/download') || clean.startsWith('/api/') || clean.startsWith('/static/')) {
      return false;
    }
    return true;
  },

  clearCache() {
    this.cache.clear();
    this.prefetchInflight.clear();
  },

  prefetch(urlStr) {
    if (!this.isNavigable(urlStr)) return;
    const url = this.cleanUrl(urlStr);
    const cached = this.cache.get(url);
    const curVersion = window.INITIAL_DATA_VERSION || 0;
    const now = Date.now();

    // 命中新鲜缓存则无需重新预取
    if (cached && (now - cached.timestamp < this.maxCacheAgeMs) && (cached.dataVersion === curVersion)) {
      return;
    }
    if (this.prefetchInflight.has(url)) return;

    const p = fetch(url, {
      headers: { 'HX-Request': 'true' },
      credentials: 'same-origin'
    }).then(res => {
      if (res.ok) return res.text();
      return null;
    }).then(html => {
      if (html && html.trim().length > 0) {
        this.cache.set(url, {
          html: html,
          timestamp: Date.now(),
          dataVersion: window.INITIAL_DATA_VERSION || 0
        });
      }
      this.prefetchInflight.delete(url);
      return html;
    }).catch(err => {
      console.debug('Prefetch error:', err);
      this.prefetchInflight.delete(url);
      return null;
    });

    this.prefetchInflight.set(url, p);
  },

  prefetchIdleRoutes() {
    const curPath = window.location.pathname;
    const baseRoutes = ['/', '/records', '/split-bill', '/accounts', '/settings', '/categories', '/subscriptions', '/liabilities'];
    const commonRoutes = baseRoutes.filter(r => r !== curPath);
    const doPrefetch = () => {
      if (navigator.connection && (navigator.connection.saveData || navigator.connection.effectiveType === '2g')) {
        return;
      }
      commonRoutes.forEach((route, idx) => {
        setTimeout(() => {
          this.prefetch(route);
        }, idx * 160);
      });
    };

    if ('requestIdleCallback' in window) {
      requestIdleCallback(doPrefetch, { timeout: 2000 });
    } else {
      setTimeout(doPrefetch, 500);
    }
  },

  applyHtmlWithTransition(container, newHtml, callback) {
    if (document.startViewTransition) {
      document.startViewTransition(() => {
        container.innerHTML = newHtml;
        if (callback) callback();
      });
    } else {
      container.classList.add('page-fade-out');
      setTimeout(() => {
        container.innerHTML = newHtml;
        container.classList.remove('page-fade-out');
        container.classList.add('page-fade-in');
        setTimeout(() => container.classList.remove('page-fade-in'), 120);
        if (callback) callback();
      }, 15);
    }
  },

  renderContent(url, html, pushState = true) {
    const container = document.getElementById('mainContainer');
    if (!container) return;

    if (this.skeletonTimer) {
      clearTimeout(this.skeletonTimer);
      this.skeletonTimer = null;
    }

    this.applyHtmlWithTransition(container, html, () => {
      // 执行内联数据脚本，确保各页面的 window 局部变量生效
      container.querySelectorAll('script').forEach(s => {
        try {
          const fn = new Function(s.textContent);
          fn();
        } catch (e) {
          console.debug('Error executing partial script:', e);
        }
      });

      if (pushState) {
        history.pushState({ instantNav: true, url: url }, '', url);
      }
      updateActiveNav(url);
      initPageLifecycle();
      finishProgressBar();
      window.scrollTo({ top: 0, behavior: 'instant' });
    });
  },

  navigate(urlStr) {
    if (!this.isNavigable(urlStr)) {
      window.location.href = urlStr;
      return;
    }
    const url = this.cleanUrl(urlStr);
    updateActiveNav(url);
    showProgressBar();

    const cached = this.cache.get(url);
    const curVersion = window.INITIAL_DATA_VERSION || 0;
    const now = Date.now();

    // 1. 命中有效缓存：0ms 瞬间挂载并呈现！
    if (cached && (now - cached.timestamp < this.maxCacheAgeMs) && (cached.dataVersion === curVersion)) {
      this.renderContent(url, cached.html);
      return;
    }

    // 2. 检查是否有正在飞行的预加载请求
    const inFlight = this.prefetchInflight.get(url);
    if (inFlight) {
      inFlight.then(html => {
        if (html) {
          this.renderContent(url, html);
        } else {
          this._fallbackFetch(url);
        }
      });
      return;
    }

    this._fallbackFetch(url);
  },

  _fallbackFetch(url) {
    const container = document.getElementById('mainContainer');
    if (this.skeletonTimer) clearTimeout(this.skeletonTimer);
    // 延迟 120ms 防抖展示骨架屏：若网络在 120ms 内极速响应，坚决杜绝骨架屏闪烁！
    this.skeletonTimer = setTimeout(() => {
      if (container) {
        container.innerHTML = renderSkeletonScreen(url);
        window.scrollTo({ top: 0, behavior: 'instant' });
      }
    }, 120);

    const controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    const timeoutId = controller ? setTimeout(() => controller.abort(), 5000) : null;

    fetch(url, {
      headers: { 'HX-Request': 'true' },
      credentials: 'same-origin',
      signal: controller ? controller.signal : undefined
    }).then(res => {
      if (timeoutId) clearTimeout(timeoutId);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.text();
    }).then(html => {
      if (this.skeletonTimer) {
        clearTimeout(this.skeletonTimer);
        this.skeletonTimer = null;
      }
      this.cache.set(url, {
        html: html,
        timestamp: Date.now(),
        dataVersion: window.INITIAL_DATA_VERSION || 0
      });
      this.renderContent(url, html);
    }).catch(err => {
      if (timeoutId) clearTimeout(timeoutId);
      if (this.skeletonTimer) {
        clearTimeout(this.skeletonTimer);
        this.skeletonTimer = null;
      }
      finishProgressBar();
      // 遇异常或超时，直接原生跳转，绝不卡在骨架屏中
      window.location.href = url;
    });
  }
};

document.addEventListener('DOMContentLoaded', function () {
  initPageLifecycle();

  // 0. 空闲期推测性预取：用户浏览当前页面时，后台静默将核心高频页面载入内存缓存
  if (typeof InstantNav !== 'undefined' && typeof InstantNav.prefetchIdleRoutes === 'function') {
    InstantNav.prefetchIdleRoutes();
  }

  // 1. 预测性预取：鼠标悬停或手指触碰任意内链时，提前在后台静默预取目标页 HTML
  const prefetchTarget = function (e) {
    const navLink = e.target.closest('a[href^="/"], a[hx-get^="/"], .mobile-bottom-nav .bnav-item, .sheet-action-btn, .sheet-list-item');
    if (!navLink) return;
    if (navLink.getAttribute('target') === '_blank' || navLink.hasAttribute('download')) return;
    const url = navLink.getAttribute('hx-get') || navLink.getAttribute('href');
    if (url && InstantNav.isNavigable(url)) {
      InstantNav.prefetch(url);
    }
  };

  document.addEventListener('mouseover', prefetchTarget, { passive: true });
  document.addEventListener('touchstart', prefetchTarget, { passive: true, capture: true });

  // 2. 全局内链与导航按钮点击拦截：0ms 立即高亮状态 + 优先从缓存瞬间挂载 (0ms 瞬时秒开)
  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const navItem = e.target.closest('a[href^="/"], a[hx-get^="/"], .mobile-bottom-nav .bnav-item, .sheet-action-btn, .sheet-list-item');
    if (!navItem) return;
    if (navItem.id === 'btnMoreSheet') return;
    if (navItem.getAttribute('target') === '_blank' || navItem.hasAttribute('download') || navItem.dataset.noInstant) return;

    const url = navItem.getAttribute('hx-get') || navItem.getAttribute('href');
    if (!url || !InstantNav.isNavigable(url)) return;

    const navKey = navItem.dataset.nav;
    if (navKey) {
      updateActiveNav(url);
    }
    if (typeof toggleMoreSheet === 'function') {
      toggleMoreSheet(false);
    }

    // 拦截点击事件，直接由 InstantNav 引擎瞬间置换 DOM
    e.preventDefault();
    e.stopPropagation();
    InstantNav.navigate(url);
  }, true);

  // 3. 浏览器前进/后退历史平滑支持
  window.addEventListener('popstate', function (e) {
    const path = window.location.pathname + window.location.search;
    InstantNav.navigate(path);
  });

  // 4. 数据变动时立即清空过期页面缓存（表单提交、记账变动）
  document.addEventListener('submit', function () {
    InstantNav.clearCache();
  });

  // 5. HTMX 事件监听器：连接顶部加载条与防抖骨架屏支持
  document.body.addEventListener('htmx:beforeRequest', function (evt) {
    showProgressBar();
    if (typeof toggleMoreSheet === 'function') {
      toggleMoreSheet(false);
    }

    // 换页时清空滞留的全局 Toast 消息变量，防止换页误触重复弹窗
    window.FLASH_SUCCESS = null;
    window.FLASH_ERROR = null;

    const target = evt.detail.target;
    const elt = evt.target || (evt.detail && evt.detail.elt);
    if (target && target.id === 'mainContainer') {
      const isNav = elt && (
        elt.closest('.desktop-nav-links') || 
        elt.closest('.mobile-bottom-nav') || 
        elt.closest('.mobile-bottom-sheet') || 
        elt.tagName === 'A'
      );
      if (isNav) {
        const targetUrl = (evt.detail.pathInfo && evt.detail.pathInfo.requestPath) || 
                          (evt.detail.requestConfig && evt.detail.requestConfig.path) || 
                          (elt && elt.getAttribute('href')) || 
                          (elt && elt.getAttribute('hx-get')) || '';
        if (targetUrl) {
          updateActiveNav(targetUrl);
        }
        // 延迟 120ms 防抖，快请求完全不展示骨架屏
        if (window._htmxSkeletonTimer) clearTimeout(window._htmxSkeletonTimer);
        window._htmxSkeletonTimer = setTimeout(() => {
          target.innerHTML = renderSkeletonScreen(targetUrl);
          window.scrollTo({ top: 0, behavior: 'instant' });
        }, 120);
      }
    }
  });

  document.body.addEventListener('htmx:afterRequest', function (evt) {
    if (window._htmxSkeletonTimer) {
      clearTimeout(window._htmxSkeletonTimer);
      window._htmxSkeletonTimer = null;
    }
    finishProgressBar();
    const reqMethod = (evt.detail && evt.detail.requestConfig && evt.detail.requestConfig.verb) || '';
    if (reqMethod && reqMethod.toLowerCase() !== 'get') {
      InstantNav.clearCache();
    }
  });

  document.body.addEventListener('htmx:afterSwap', function (evt) {
    if (window._htmxSkeletonTimer) {
      clearTimeout(window._htmxSkeletonTimer);
      window._htmxSkeletonTimer = null;
    }
    const container = evt.detail.target;
    if (container && container.id === 'mainContainer') {
      container.querySelectorAll('script').forEach(s => {
        try {
          const fn = new Function(s.textContent);
          fn();
        } catch (e) {
          console.error('Error executing partial script:', e);
        }
      });

      const newPath = (evt.detail.pathInfo && evt.detail.pathInfo.requestPath) || window.location.pathname;
      updateActiveNav(newPath);
      initPageLifecycle();
      requestAnimationFrame(function () {
        if (typeof renderDonutCharts === 'function') {
          renderDonutCharts();
        }
      });
      window.scrollTo({ top: 0, behavior: 'instant' });
    }
  });

  document.body.addEventListener('htmx:historyRestore', function () {
    const container = document.getElementById('mainContainer');
    if (container) {
      container.querySelectorAll('script').forEach(s => {
        try {
          const fn = new Function(s.textContent);
          fn();
        } catch (e) {}
      });
    }
    updateActiveNav(window.location.pathname);
    initPageLifecycle();
    requestAnimationFrame(function () {
      if (typeof renderDonutCharts === 'function') {
        renderDonutCharts();
      }
    });
  });
});

// ==========================================
// PWA (Progressive Web App) 注册与安装交互
// ==========================================
let deferredPwaPrompt = null;

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { scope: '/' })
      .then((reg) => {
        console.log('[PWA] ServiceWorker registered with scope:', reg.scope);
      })
      .catch((err) => {
        console.warn('[PWA] ServiceWorker registration failed:', err);
      });
  });
}

// 智能判断当前是否已在独立 App 模式内运行（含 Android 原生伴侣 APK、iOS/Android 桌面 PWA 全屏/独立模式、TWA 等）
function isRunningInApp() {
  // 1. Android 原生伴侣 App 环境 (WebView Bridge / Custom User-Agent)
  if (
    typeof window.LedgerNativeBridge !== 'undefined' ||
    (navigator.userAgent && (navigator.userAgent.includes('LedgerAppNative') || navigator.userAgent.includes('wv'))) ||
    typeof window.AndroidBridge !== 'undefined' ||
    typeof window.Android !== 'undefined'
  ) {
    return true;
  }

  // 2. 现代浏览器 PWA 独立/全屏/最小窗口显示模式
  try {
    if (
      (window.matchMedia && (
        window.matchMedia('(display-mode: standalone)').matches ||
        window.matchMedia('(display-mode: fullscreen)').matches ||
        window.matchMedia('(display-mode: minimal-ui)').matches ||
        window.matchMedia('(display-mode: window-controls-overlay)').matches
      )) ||
      window.navigator.standalone === true
    ) {
      return true;
    }
  } catch (e) {}

  // 3. Android TWA (Trusted Web Activity) 或原生 App 来源
  if (document.referrer && document.referrer.startsWith('android-app://')) {
    return true;
  }

  // 4. 从手机桌面 PWA 快捷方式或 Manifest start_url 启动
  if (window.location.search && (
    window.location.search.includes('source=pwa') ||
    window.location.search.includes('pwa=1')
  )) {
    return true;
  }

  return false;
}

// 判断当前设备是否已经安装了应用（Native APK 或桌面 PWA），或已由用户标记已安装
function isPwaOrAppInstalled() {
  if (isRunningInApp()) return true;

  try {
    if (localStorage.getItem('ledger_app_installed') === 'true') return true;
    if (localStorage.getItem('ledger_pwa_installed') === 'true') return true;
    if (localStorage.getItem('ledger_app_downloaded') === 'true') return true;
  } catch (e) {}

  return false;
}

// 判断是否应当展示浮动安装横幅
function shouldShowPwaBanner() {
  // 正在 App 内运行：坚决不展示
  if (isRunningInApp()) return false;

  // 已经安装过（或已下载过安装包）：坚决不展示
  if (isPwaOrAppInstalled()) return false;

  // 用户此前点击关闭过横幅：持久化记忆（localStorage + sessionStorage），不重复骚扰
  try {
    if (localStorage.getItem('pwa_banner_dismissed') === 'true') return false;
    if (sessionStorage.getItem('pwa_banner_closed') === 'true') return false;
  } catch (e) {}

  return true;
}

// 检查并更新 PWA 安装入口与浮动横幅状态
function checkPwaUi() {
  const inApp = isRunningInApp();
  const banner = document.getElementById('pwaInstallBanner');
  const sheetBtn = document.getElementById('pwaSheetInstallBtn');

  // 如果当前已在 App 内运行，立即将状态写入 localStorage，彻底避免日后在普通浏览器中反复误报
  if (inApp) {
    try {
      localStorage.setItem('ledger_app_installed', 'true');
      localStorage.setItem('ledger_pwa_installed', 'true');
    } catch (e) {}

    // 如果 URL 中有 source=pwa / pwa=1，静默清洗 URL，保持地址栏美观
    try {
      if (window.location.search && (window.location.search.includes('source=pwa') || window.location.search.includes('pwa=1'))) {
        const url = new URL(window.location.href);
        url.searchParams.delete('source');
        url.searchParams.delete('pwa');
        window.history.replaceState({}, document.title, url.pathname + (url.search ? url.search : '') + url.hash);
      }
    } catch (e) {}

    if (banner) banner.style.display = 'none';
    if (sheetBtn) sheetBtn.style.display = 'none';
    return;
  }

  // 检查是否已安装或已被关闭
  if (!shouldShowPwaBanner()) {
    if (banner) banner.style.display = 'none';
    return;
  }

  // 仅在未安装且未关闭的情况下，延迟 1.2 秒平滑滑出提示条
  if (banner) {
    setTimeout(() => {
      if (shouldShowPwaBanner()) {
        banner.style.display = 'flex';
      } else {
        banner.style.display = 'none';
      }
    }, 1200);
  }
}

// 捕获 Android / Chrome 原生安装提示事件
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPwaPrompt = e;
  checkPwaUi();
});

// 监听安装完成事件
window.addEventListener('appinstalled', () => {
  deferredPwaPrompt = null;
  console.log('[PWA] App successfully installed');
  try {
    localStorage.setItem('ledger_app_installed', 'true');
    localStorage.setItem('ledger_pwa_installed', 'true');
    localStorage.setItem('pwa_banner_dismissed', 'true');
  } catch (e) {}
  const banner = document.getElementById('pwaInstallBanner');
  if (banner) banner.style.display = 'none';
  const sheetBtn = document.getElementById('pwaSheetInstallBtn');
  if (sheetBtn) sheetBtn.style.display = 'none';
  if (typeof Swal !== 'undefined') {
    Swal.fire({
      toast: true,
      position: 'top',
      icon: 'success',
      title: window.t ? window.t('pwa.installed_success', '已成功添加到主屏幕！') : '已成功添加到主屏幕！',
      showConfirmButton: false,
      timer: 2500
    });
  }
});

// 手动触发安装操作
window.installPwaApp = function () {
  if (deferredPwaPrompt) {
    deferredPwaPrompt.prompt();
    deferredPwaPrompt.userChoice.then((choiceResult) => {
      if (choiceResult && choiceResult.outcome === 'accepted') {
        console.log('[PWA] User accepted install prompt');
        try {
          localStorage.setItem('ledger_app_installed', 'true');
          localStorage.setItem('ledger_pwa_installed', 'true');
          localStorage.setItem('pwa_banner_dismissed', 'true');
        } catch (e) {}
        const banner = document.getElementById('pwaInstallBanner');
        if (banner) banner.style.display = 'none';
      }
      deferredPwaPrompt = null;
    });
  } else {
    // 检测是否为 iOS Safari
    const isIos = /iphone|ipad|ipod/.test(window.navigator.userAgent.toLowerCase());
    const alreadyBtnText = window.t ? window.t('pwa.already_installed', '已安装，不再提醒') : '已安装，不再提醒';
    const gotItBtnText = window.t ? window.t('pwa.got_it', '我知道了') : '我知道了';

    if (isIos && typeof Swal !== 'undefined') {
      Swal.fire({
        title: window.t ? window.t('pwa.prompt_safari_title', '📲 添加到手机主屏幕') : '📲 添加到手机主屏幕',
        html: '<div style="text-align: left; font-size: 14px; line-height: 1.8; color: var(--ink-soft);">' +
              '1. 点击 Safari 底部中间的 <b>分享按钮</b> <span style="font-size: 18px;">📤</span><br>' +
              '2. 向上滑动菜单找到并点击 <b>「添加到主屏幕」</b> <span style="font-size: 18px;">➕</span><br>' +
              '3. 点击右上角「添加」，即可像原生 App 一样全屏使用！</div>',
        icon: 'info',
        showCancelButton: true,
        confirmButtonText: gotItBtnText,
        cancelButtonText: alreadyBtnText
      }).then((res) => {
        if (res.dismiss === Swal.DismissReason.cancel) {
          try {
            localStorage.setItem('ledger_app_installed', 'true');
            localStorage.setItem('ledger_pwa_installed', 'true');
            localStorage.setItem('pwa_banner_dismissed', 'true');
          } catch (e) {}
          const banner = document.getElementById('pwaInstallBanner');
          if (banner) banner.style.display = 'none';
        }
      });
    } else if (typeof Swal !== 'undefined') {
      Swal.fire({
        title: window.t ? window.t('pwa.prompt_chrome_title', '📲 安装为手机应用') : '📲 安装为手机应用',
        html: '<div style="text-align: left; font-size: 14px; line-height: 1.8; color: var(--ink-soft);">' +
              '1. 点击浏览器右上角或底部的 <b>菜单按钮</b>（通常是三个点 <b>⋮</b> 或图标）<br>' +
              '2. 在弹出的菜单列表中选择 <b>「安装应用」</b> 或 <b>「添加到主屏幕」</b> ➕<br>' +
              '3. 确认后手机桌面即会生成独立 App 图标，无需再开浏览器！</div>',
        icon: 'info',
        showCancelButton: true,
        confirmButtonText: gotItBtnText,
        cancelButtonText: alreadyBtnText
      }).then((res) => {
        if (res.dismiss === Swal.DismissReason.cancel) {
          try {
            localStorage.setItem('ledger_app_installed', 'true');
            localStorage.setItem('ledger_pwa_installed', 'true');
            localStorage.setItem('pwa_banner_dismissed', 'true');
          } catch (e) {}
          const banner = document.getElementById('pwaInstallBanner');
          if (banner) banner.style.display = 'none';
        }
      });
    } else {
      alert('请在手机浏览器菜单中点击「安装应用」或「添加到主屏幕」！');
    }
  }
  if (typeof toggleMoreSheet === 'function') {
    toggleMoreSheet(false);
  }
};

window.dismissPwaBanner = function () {
  const banner = document.getElementById('pwaInstallBanner');
  if (banner) banner.style.display = 'none';
  try {
    sessionStorage.setItem('pwa_banner_closed', 'true');
    localStorage.setItem('pwa_banner_dismissed', 'true');
  } catch (e) {}
};

// 页面加载及 HTMX 切换后主动检查 PWA 入口与 App 下载状态
function runDeviceAppChecks() {
  if (typeof checkPwaUi === 'function') checkPwaUi();
  updateAppDownloadStatus();
}

window.addEventListener('load', runDeviceAppChecks);
document.addEventListener('DOMContentLoaded', runDeviceAppChecks);
document.body.addEventListener('htmx:afterSwap', runDeviceAppChecks);

// 智能检测当前设备是否已下载或运行原生 App
function updateAppDownloadStatus() {
  const tile = document.getElementById('appDownloadTile');
  if (!tile) return;

  const icon = document.getElementById('appDownloadIcon');
  const title = document.getElementById('appDownloadTitle');
  const desc = document.getElementById('appDownloadDesc');
  const badge = document.getElementById('appDownloadBadge');

  const isNativeApp = (typeof window.LedgerNativeBridge !== 'undefined') ||
                      (navigator.userAgent && navigator.userAgent.includes('LedgerAppNative'));
  const isDownloaded = localStorage.getItem('ledger_app_downloaded') === 'true';

  if (isNativeApp) {
    if (icon) icon.textContent = '🚀';
    if (title) title.textContent = window.t ? window.t('nav.sheet_download_app', '原生应用') : '原生应用';
    if (desc) desc.textContent = window.t ? window.t('nav.sheet_download_native', '当前正在原生应用内运行') : '当前正在原生应用内运行';
    if (badge) {
      badge.textContent = window.t ? window.t('nav.sheet_download_running', '运行中') : '运行中';
      badge.style.display = 'inline-block';
      badge.style.background = 'rgba(16, 185, 129, 0.2)';
      badge.style.color = '#10b981';
    }
  } else if (isDownloaded) {
    if (icon) icon.textContent = '✅';
    if (title) title.textContent = window.t ? window.t('nav.sheet_download_app', '下载应用') : '下载应用';
    if (desc) desc.textContent = window.t ? window.t('nav.sheet_download_installed', '本机已下载 (点击重新下载更新)') : '本机已下载 (点击重新下载更新)';
    if (badge) {
      badge.textContent = window.t ? window.t('nav.sheet_download_ready', '已下载') : '已下载';
      badge.style.display = 'inline-block';
      badge.style.background = 'rgba(16, 185, 129, 0.2)';
      badge.style.color = '#10b981';
    }
  } else {
    if (icon) icon.textContent = '📱';
    if (title) title.textContent = window.t ? window.t('nav.sheet_download_app', '下载应用') : '下载应用';
    if (desc) desc.textContent = window.t ? window.t('nav.sheet_download_desc', '点击下载 Android 原生应用包') : '点击下载 Android 原生应用包';
    if (badge) {
      badge.style.display = 'none';
    }
  }
}

window.handleAppDownloadClick = function (e) {
  const isNativeApp = (typeof window.LedgerNativeBridge !== 'undefined') ||
                      (navigator.userAgent && navigator.userAgent.includes('LedgerAppNative'));

  if (isNativeApp && typeof Swal !== 'undefined') {
    e.preventDefault();
    Swal.fire({
      title: window.t ? window.t('nav.sheet_app_in_native_title', '📱 当前已在原生 App 内') : '📱 当前已在原生 App 内',
      text: window.t ? window.t('nav.sheet_app_in_native_desc', '您可以呼起原生伴侣快捷调试菜单，或重新下载最新版本安装包：') : '您可以呼起原生伴侣快捷调试菜单，或重新下载最新版本安装包：',
      icon: 'info',
      showDenyButton: true,
      showCancelButton: true,
      confirmButtonText: window.t ? window.t('nav.sheet_app_debug_menu', '⚡ 原生调试与快捷菜单') : '⚡ 原生调试与快捷菜单',
      denyButtonText: window.t ? window.t('nav.sheet_app_redownload', '⬇️ 重新下载 APK') : '⬇️ 重新下载 APK',
      cancelButtonText: window.t ? window.t('common.cancel', '关闭') : '关闭'
    }).then((res) => {
      if (res.isConfirmed) {
        if (window.LedgerNativeBridge && typeof window.LedgerNativeBridge.openQuickMenu === 'function') {
          window.LedgerNativeBridge.openQuickMenu();
        }
        if (typeof toggleMoreSheet === 'function') toggleMoreSheet(false);
      } else if (res.isDenied) {
        localStorage.setItem('ledger_app_downloaded', 'true');
        localStorage.setItem('ledger_app_download_time', new Date().toISOString());
        updateAppDownloadStatus();
        window.location.href = '/download/apk?t=' + Date.now();
      }
    });
    return;
  }

  // 记录本机已下载状态
  localStorage.setItem('ledger_app_downloaded', 'true');
  localStorage.setItem('ledger_app_download_time', new Date().toISOString());
  updateAppDownloadStatus();

  if (typeof Swal !== 'undefined') {
    Swal.fire({
      toast: true,
      position: 'top',
      icon: 'success',
      title: window.t ? window.t('nav.sheet_downloading_apk', '正在下载「我的账本」安装包...') : '正在下载「我的账本」安装包...',
      text: window.t ? window.t('nav.sheet_downloading_hint', '下载后请点击通知栏或文件管理进行安装') : '下载后请点击通知栏或文件管理进行安装',
      showConfirmButton: false,
      timer: 3500
    });
  }
};

// ==========================================
// 分类月度预算交互 (弹窗设置与仪表盘折叠)
// ==========================================
window.openSetBudgetModal = function (triggerBtn) {
  if (!triggerBtn) return;
  const catId = triggerBtn.dataset.id;
  const catName = triggerBtn.dataset.name || '此分类';
  const catDisplay = window.t_cat ? window.t_cat(catName) : catName;
  const currentLimit = triggerBtn.dataset.limit || '';
  const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content ||
                    document.querySelector('input[name="csrf_token"]')?.value || '';

  const currentNum = currentLimit ? parseFloat(currentLimit) : '';

  if (typeof Swal === 'undefined') {
    const val = window.prompt(`设置「${catDisplay}」每月预算上限（RM，留空或0为不限额）：`, currentNum !== '' ? currentNum : '');
    if (val !== null) {
      submitCategoryBudgetAjax(catId, val, csrfToken);
    }
    return;
  }

  const modalTitle = window.t ? window.t('swal.set_monthly_budget', '🎯 设置「{cat}」月度预算', {cat: catDisplay}) : ('🎯 设置「' + catDisplay + '」月度预算');
  const modalDesc = window.t ? window.t('swal.set_budget_desc', '设定该分类每月支出上限。支出累计达到 <b>70%</b> 和 <b>100%</b> 时系统会自动预警提醒。') : '设定该分类每月支出上限。支出累计达到 <b>70%</b> 和 <b>100%</b> 时系统会自动预警提醒。';
  const phLimit = window.t ? window.t('swal.budget_no_limit_ph', '不设限额（留空或填 0）') : '不设限额（留空或填 0）';
  const btnClear = window.t ? window.t('swal.budget_clear_limit', '清除限额') : '清除限额';
  const btnSave = window.t ? window.t('swal.budget_save_limit', '保存限额') : '保存限额';
  const btnCancel = window.t ? window.t('common.cancel', '取消') : '取消';

  Swal.fire({
    title: modalTitle,
    html: `
      <div style="text-align: left; font-size: 13px; color: var(--muted); margin-bottom: 14px; line-height: 1.6;">
        ${modalDesc}
      </div>
      <div style="position: relative; margin-bottom: 12px;">
        <span style="position: absolute; left: 14px; top: 50%; transform: translateY(-50%); font-weight: 700; color: var(--ink); font-size: 15px;">RM</span>
        <input id="swalBudgetInput" type="number" step="0.01" min="0" class="swal2-input"
               style="margin: 0; width: 100%; box-sizing: border-box; padding-left: 48px; font-size: 16px; font-weight: 600;"
               placeholder="${phLimit}" value="${currentNum !== '' ? currentNum : ''}">
      </div>
      <div style="display: flex; gap: 6px; justify-content: center; flex-wrap: wrap; margin-bottom: 4px;">
        <button type="button" class="btn-ghost-sm" style="border: 1px solid var(--border-soft); padding: 4px 10px; font-size: 12px; border-radius: 6px;" onclick="document.getElementById('swalBudgetInput').value='200'">200</button>
        <button type="button" class="btn-ghost-sm" style="border: 1px solid var(--border-soft); padding: 4px 10px; font-size: 12px; border-radius: 6px;" onclick="document.getElementById('swalBudgetInput').value='500'">500</button>
        <button type="button" class="btn-ghost-sm" style="border: 1px solid var(--border-soft); padding: 4px 10px; font-size: 12px; border-radius: 6px;" onclick="document.getElementById('swalBudgetInput').value='1000'">1000</button>
        <button type="button" class="btn-ghost-sm" style="border: 1px solid var(--border-soft); padding: 4px 10px; font-size: 12px; border-radius: 6px;" onclick="document.getElementById('swalBudgetInput').value='2000'">2000</button>
        ${currentLimit ? '<button type="button" class="btn-ghost-sm" style="color: #dc2626; border: 1px solid rgba(220,38,38,0.3); padding: 4px 10px; font-size: 12px; border-radius: 6px;" onclick="document.getElementById(\'swalBudgetInput\').value=\'\'">' + btnClear + '</button>' : ''}
      </div>
    `,
    showCancelButton: true,
    confirmButtonText: btnSave,
    cancelButtonText: btnCancel,
    buttonsStyling: false,
    customClass: {
      popup: 'app-swal-popup',
      title: 'app-swal-title',
      htmlContainer: 'app-swal-html',
      confirmButton: 'btn-primary',
      cancelButton: 'btn-ghost',
      actions: 'app-swal-actions'
    },
    didOpen: () => {
      const input = document.getElementById('swalBudgetInput');
      if (input) {
        input.focus();
        input.select();
      }
    },
    preConfirm: () => {
      const input = document.getElementById('swalBudgetInput');
      return input ? input.value.trim() : '';
    }
  }).then((result) => {
    if (result.isConfirmed) {
      submitCategoryBudgetAjax(catId, result.value, csrfToken);
    }
  });
};

function submitCategoryBudgetAjax(catId, limitValue, csrfToken) {
  const formData = new FormData();
  formData.append('csrf_token', csrfToken);
  formData.append('monthly_limit', limitValue);

  fetch(`/categories/${catId}/budget`, {
    method: 'POST',
    body: formData,
    headers: {
      'X-Requested-With': 'XMLHttpRequest'
    }
  })
  .then(res => res.json())
  .then(data => {
    if (data.ok) {
      if (typeof Swal !== 'undefined') {
        Swal.fire({
          toast: true,
          position: 'top',
          icon: 'success',
          title: data.message || (window.t ? window.t('swal.budget_updated', '预算设置已更新') : '预算设置已更新'),
          showConfirmButton: false,
          timer: 1500,
          customClass: { popup: 'app-swal-toast' }
        });
      }
      if (typeof htmx !== 'undefined') {
        htmx.ajax('GET', window.location.pathname + window.location.search, {
          target: '#mainContainer',
          swap: 'innerHTML show:window:top'
        });
      } else {
        window.location.reload();
      }
    } else {
      if (typeof Swal !== 'undefined') {
        Swal.fire({
          icon: 'error',
          title: '设置失败',
          text: data.message || '请检查输入',
          customClass: { popup: 'app-swal-popup' }
        });
      } else {
        alert(data.message || '设置失败');
      }
    }
  })
  .catch(err => {
    console.error('Budget update error:', err);
    window.location.reload();
  });
}

window.toggleBudgetDashExpand = function () {
  const grid = document.getElementById('budgetDashGrid');
  const btn = document.getElementById('budgetDashExpandBtn');
  if (!grid || !btn) return;
  const isCollapsed = grid.classList.contains('collapsed');
  if (isCollapsed) {
    grid.classList.remove('collapsed');
    btn.innerHTML = '<span>' + (window.t ? window.t('swal.budget_collapse', '收起 ▴') : '收起 ▴') + '</span>';
  } else {
    grid.classList.add('collapsed');
    const total = grid.querySelectorAll('.budget-dash-card').length;
    btn.innerHTML = '<span>' + (window.t ? window.t('swal.budget_expand_all', '展开全部 {total} 项预算 ▾', {total: total}) : ('展开全部 ' + total + ' 项预算 ▾')) + '</span>';
  }
};

// 监听操作系统暗黑模式切换（当用户设置为跟随系统时自动响应）
if (window.matchMedia) {
  try {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      const mode = localStorage.getItem('ledger_theme_mode') || 'system';
      if (mode === 'system') {
        document.documentElement.removeAttribute('data-theme');
      }
    });
  } catch (e) {}
}

// ============================================================================
// 智能商户自动补全与分类智能匹配 (Smart Merchant Autocomplete)
// ============================================================================
(function () {
  const _merchantCache = new Map();

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function highlightMatch(text, query) {
    if (!query) return escapeHtml(text);
    const escapedText = escapeHtml(text);
    const escapedQuery = escapeHtml(query).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp('(' + escapedQuery + ')', 'gi');
    return escapedText.replace(regex, '<span class="merchant-item-match">$1</span>');
  }

  function fetchSuggestions(query, txType, limit) {
    const cacheKey = (query || '') + '|' + (txType || '') + '|' + (limit || 10);
    if (_merchantCache.has(cacheKey)) {
      return Promise.resolve(_merchantCache.get(cacheKey));
    }
    const params = new URLSearchParams();
    if (query) params.append('q', query);
    if (txType) params.append('type', txType);
    if (limit) params.append('limit', limit);

    return fetch('/api/merchants/suggestions?' + params.toString(), {
      headers: { 'HX-Request': 'true' },
      credentials: 'same-origin'
    })
      .then(res => (res.ok ? res.json() : { ok: false, suggestions: [] }))
      .then(data => {
        const suggestions = (data && data.suggestions) || [];
        _merchantCache.set(cacheKey, suggestions);
        return suggestions;
      })
      .catch(err => {
        console.debug('Merchant suggestions fetch error:', err);
        return [];
      });
  }

  window.initMerchantAutocomplete = function (inputEl, options) {
    if (!inputEl || inputEl._hasMerchantAutocomplete) return;
    inputEl._hasMerchantAutocomplete = true;
    options = options || {};

    // 确保输入框外部具有相对定位容器
    let wrapper = inputEl.parentElement;
    if (!wrapper.classList.contains('merchant-autocomplete-wrapper')) {
      const parent = inputEl.parentNode;
      const newWrapper = document.createElement('div');
      newWrapper.className = 'merchant-autocomplete-wrapper';
      parent.insertBefore(newWrapper, inputEl);
      newWrapper.appendChild(inputEl);
      wrapper = newWrapper;
    }

    // 创建浮动下拉面板
    const dropdown = document.createElement('div');
    dropdown.className = 'merchant-autocomplete-dropdown';
    dropdown.style.display = 'none';
    dropdown.setAttribute('role', 'listbox');
    wrapper.appendChild(dropdown);

    // 匹配反馈胶囊
    const feedbackPill = document.createElement('div');
    feedbackPill.className = 'merchant-matched-feedback';
    feedbackPill.style.display = 'none';
    wrapper.appendChild(feedbackPill);

    let debounceTimer = null;
    let activeIndex = -1;
    let currentSuggestions = [];

    function getTxType() {
      if (typeof options.typeGetter === 'function') {
        return options.typeGetter();
      }
      const checked = document.querySelector('input[name="type"]:checked');
      return checked ? checked.value : 'expense';
    }

    function renderDropdown(items, query) {
      currentSuggestions = items || [];
      activeIndex = -1;
      dropdown.innerHTML = '';

      if (!items || items.length === 0) {
        dropdown.style.display = 'none';
        return;
      }

      const header = document.createElement('div');
      header.className = 'merchant-autocomplete-header';
      header.innerHTML = `
        <span>${window.t ? window.t('merchant.frequent_merchants', '常用商户与推荐') : '常用商户与推荐'}</span>
        <span style="font-weight:400; font-size:10px;">${window.t ? window.t('merchant.select_hint', '↑↓ 切换，回车确认') : '↑↓ 切换，回车确认'}</span>
      `;
      dropdown.appendChild(header);

      const currSym = window.LEDGER_CURRENCY_SYMBOL || 'RM';

      items.forEach((item, idx) => {
        const row = document.createElement('div');
        row.className = 'merchant-autocomplete-item';
        row.setAttribute('role', 'option');
        row.setAttribute('data-index', idx);

        const catName = window.t_cat ? window.t_cat(item.category) : item.category;
        const countText = window.t
          ? window.t('merchant.times_used', '{count}次', { count: item.count })
          : item.count + '次';
        const amountDisplay = item.last_amount > 0 ? `${currSym} ${item.last_amount.toFixed(2)}` : '';

        row.innerHTML = `
          <div class="merchant-item-left">
            <span class="merchant-item-icon">🏪</span>
            <span class="merchant-item-name">${highlightMatch(item.name, query)}</span>
          </div>
          <div class="merchant-item-right">
            <span class="merchant-badge-category">${escapeHtml(catName)}</span>
            ${amountDisplay ? `<span class="merchant-badge-amount">${escapeHtml(amountDisplay)}</span>` : ''}
            <span class="merchant-badge-count">${escapeHtml(countText)}</span>
          </div>
        `;

        row.addEventListener('mousedown', function (e) {
          e.preventDefault(); // 防止 input 失焦导致下拉提前关闭
          selectItem(item);
        });

        dropdown.appendChild(row);
      });

      dropdown.style.display = 'flex';
    }

    function selectItem(item) {
      if (!item) return;
      inputEl.value = item.name;
      dropdown.style.display = 'none';

      // 1. 自动关联匹配分类选择框
      const catSelect =
        typeof options.categorySelect === 'string'
          ? document.querySelector(options.categorySelect)
          : options.categorySelect;

      let matchedCat = false;
      if (catSelect && item.category) {
        for (let i = 0; i < catSelect.options.length; i++) {
          if (
            catSelect.options[i].value === item.category ||
            catSelect.options[i].text.includes(item.category)
          ) {
            catSelect.selectedIndex = i;
            catSelect.dispatchEvent(new Event('change', { bubbles: true }));
            matchedCat = true;
            break;
          }
        }
      }

      // 2. 若金额输入框为空或为0，自动回填上次金额作为贴心参考
      const amtInput =
        typeof options.amountInput === 'string'
          ? document.querySelector(options.amountInput)
          : options.amountInput;

      if (amtInput && item.last_amount > 0) {
        const curVal = parseFloat(amtInput.value || 0);
        if (isNaN(curVal) || curVal <= 0) {
          amtInput.value = item.last_amount.toFixed(2);
          amtInput.dispatchEvent(new Event('input', { bubbles: true }));
        }
      }

      // 3. 若有绑定的账户下拉框且当前为默认值，自动填入常用账户
      const accSelect =
        typeof options.accountSelect === 'string'
          ? document.querySelector(options.accountSelect)
          : options.accountSelect;

      if (accSelect && item.last_account_id) {
        const opt = accSelect.querySelector(`option[value="${item.last_account_id}"]`);
        if (opt && (!accSelect.value || accSelect.value === '')) {
          accSelect.value = String(item.last_account_id);
          accSelect.dispatchEvent(new Event('change', { bubbles: true }));
        }
      }

      // 4. 展示微提示胶囊与触觉轻震反馈
      if (matchedCat) {
        const catName = window.t_cat ? window.t_cat(item.category) : item.category;
        const currSym = window.LEDGER_CURRENCY_SYMBOL || 'RM';
        const amtStr = item.last_amount > 0 ? `${currSym} ${item.last_amount.toFixed(2)}` : '';
        feedbackPill.textContent = window.t
          ? window.t('merchant.matched_hint', '✨ 匹配: {category} · 上次 {amount}', {
              category: catName,
              amount: amtStr
            })
          : `✨ 匹配: ${catName} ${amtStr ? '· 上次 ' + amtStr : ''}`;
        feedbackPill.style.display = 'inline-flex';
        clearTimeout(feedbackPill._hideTimer);
        feedbackPill._hideTimer = setTimeout(() => {
          feedbackPill.style.display = 'none';
        }, 3200);
      }

      if (navigator.vibrate) {
        try {
          navigator.vibrate(12);
        } catch (e) {}
      }

      if (typeof options.onSelect === 'function') {
        options.onSelect(item);
      }
    }

    function updateActiveItem(index) {
      const items = dropdown.querySelectorAll('.merchant-autocomplete-item');
      items.forEach((it, idx) => {
        it.classList.toggle('active', idx === index);
        if (idx === index) {
          it.scrollIntoView({ block: 'nearest' });
        }
      });
      activeIndex = index;
    }

    inputEl.addEventListener('input', function () {
      const q = inputEl.value.trim();
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        fetchSuggestions(q, getTxType(), 10).then(items => {
          renderDropdown(items, q);
        });
      }, 120);
    });

    inputEl.addEventListener('focus', function () {
      const q = inputEl.value.trim();
      fetchSuggestions(q, getTxType(), 10).then(items => {
        renderDropdown(items, q);
      });
    });

    inputEl.addEventListener('keydown', function (e) {
      if (dropdown.style.display === 'none' || currentSuggestions.length === 0) {
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        const next = activeIndex + 1 >= currentSuggestions.length ? 0 : activeIndex + 1;
        updateActiveItem(next);
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        const prev = activeIndex - 1 < 0 ? currentSuggestions.length - 1 : activeIndex - 1;
        updateActiveItem(prev);
      } else if (e.key === 'Enter' || e.key === 'Tab') {
        if (activeIndex >= 0 && activeIndex < currentSuggestions.length) {
          e.preventDefault();
          selectItem(currentSuggestions[activeIndex]);
        }
      } else if (e.key === 'Escape') {
        dropdown.style.display = 'none';
      }
    });

    document.addEventListener('click', function (e) {
      if (!wrapper.contains(e.target)) {
        dropdown.style.display = 'none';
      }
    });
  };

  // 页面加载或 HTMX 局部刷新后自动绑定主面板的快速记账商户输入框
  function autoBindQuickAdd() {
    const quickNote = document.getElementById('quickAddNote');
    if (quickNote && !quickNote._hasMerchantAutocomplete) {
      window.initMerchantAutocomplete(quickNote, {
        categorySelect: '#categorySelect',
        amountInput: '#quickAddAmount',
        accountSelect: '#quickAddAccountSelect',
        typeGetter: () => {
          const checked = document.querySelector('input[name="type"]:checked');
          return checked ? checked.value : 'expense';
        }
      });
    }
  }

  document.addEventListener('DOMContentLoaded', autoBindQuickAdd);
  document.addEventListener('htmx:afterSettle', autoBindQuickAdd);
  setTimeout(autoBindQuickAdd, 100);
})();


/* ==========================================================================
   Aesthetic Monthly Financial Poster & Deep Analysis Report Generator
   HTML5 Canvas 2D Engine with Retina 2x Supersampling
   Features:
     - 100% Dynamic Localization (zh, zh_TW, en, ms)
     - Dynamic Color Mode Adaptation (Dark Obsidian & Light Alabaster)
     - MoM & YoY Period Comparisons
     - 50/30/20 Budgeting Rule Diagnostics & Personalized Savings Tactics
   ========================================================================== */
(function () {
  'use strict';

  function isDarkTheme() {
    const dt = document.documentElement.getAttribute('data-theme');
    if (dt === 'dark') return true;
    if (dt === 'light') return false;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  }

  function getThemeTokens(isDark) {
    if (isDark) {
      return {
        isDark: true,
        bgGradTop: '#0C1322',
        bgGradMid: '#080D18',
        bgGradBottom: '#04060B',
        glow1: 'rgba(79, 70, 229, 0.16)',
        glow2: 'rgba(245, 158, 11, 0.09)',
        cardBg: 'rgba(30, 41, 59, 0.70)',
        cardBorder: 'rgba(255, 255, 255, 0.08)',
        outerBorder: 'rgba(255, 255, 255, 0.07)',
        textPrimary: '#F8FAFC',
        textSecondary: '#94A3B8',
        textMuted: '#64748B',
        textHeading: '#FFFFFF',
        textHeadingEnd: '#E2E8F0',
        divider: 'rgba(255, 255, 255, 0.08)',
        accentGold: '#F59E0B',
        accentGoldText: '#FCD34D',
        accentGoldBg: 'rgba(245, 158, 11, 0.15)',
        accentGoldBorder: 'rgba(245, 158, 11, 0.45)',
        emerald: '#34D399',
        rose: '#F43F5E',
        barTrack: 'rgba(255, 255, 255, 0.06)',
        badgeBg: 'rgba(255, 255, 255, 0.08)'
      };
    } else {
      return {
        isDark: false,
        bgGradTop: '#FFFFFF',
        bgGradMid: '#F8FAFC',
        bgGradBottom: '#F1F5F9',
        glow1: 'rgba(99, 102, 241, 0.08)',
        glow2: 'rgba(245, 158, 11, 0.06)',
        cardBg: '#FFFFFF',
        cardBorder: 'rgba(15, 23, 42, 0.08)',
        outerBorder: 'rgba(15, 23, 42, 0.09)',
        textPrimary: '#0F172A',
        textSecondary: '#475569',
        textMuted: '#94A3B8',
        textHeading: '#0F172A',
        textHeadingEnd: '#1E293B',
        divider: 'rgba(15, 23, 42, 0.08)',
        accentGold: '#D97706',
        accentGoldText: '#B45309',
        accentGoldBg: 'rgba(245, 158, 11, 0.12)',
        accentGoldBorder: 'rgba(217, 119, 6, 0.35)',
        emerald: '#059669',
        rose: '#E11D48',
        barTrack: 'rgba(15, 23, 42, 0.06)',
        badgeBg: 'rgba(15, 23, 42, 0.06)'
      };
    }
  }

  const I18N_POSTER = {
    zh: {
      brand: '✦ 我的账本',
      issue_prefix: '财务月报',
      digest_subtitle: '极美财务月度收支总览',
      net_balance: '本月净结余',
      total_income: '总收入',
      total_expense: '总支出',
      savings_rate: '储蓄率',
      daily_avg: '日均支出',
      tx_count: '记账笔数',
      no_spend_days: '零支出天数',
      max_expense: '单笔最大',
      top_categories: '支出分类 TOP 5',
      persona_title: '财务人格与月度评语',
      footer_brand: 'MY LEDGER APP',
      footer_tagline: '掌控生活收支 · 走向财务自由',
      footer_verified: 'Verified & Generated',
      footer_secure: '本地安全加密账本',
      footer_diagnosis: '全方位财务健康诊断报告',
      deep_title: '深度洞察与同比环比分析',
      deep_subtitle: '收支趋势变动 · 50/30/20诊断 · 专属储蓄策略',
      mom_title: '环比上月 (MoM)',
      yoy_title: '同比去年 (YoY)',
      diff_expense: '支出',
      diff_income: '收入',
      diff_savings: '储蓄率',
      no_prev_data: '无上一期对比数据',
      no_expense_recorded: '本月暂无支出记录',
      rule_50_30_20: '50 / 30 / 20 预算法则健康诊断',
      needs_label: '必要支出 (Needs 50%)',
      wants_label: '弹性支出 (Wants 30%)',
      savings_label: '储蓄结余 (Savings 20%)',
      strategies_title: '💡 针对性储蓄与改善建议',
      est_save_prefix: '预估月省',
      persona_badge: '✨ 评定称号',
      other_label: '其他'
    },
    zh_TW: {
      brand: '✦ 我的賬本',
      issue_prefix: '財務月報',
      digest_subtitle: '極美財務月度收支總覽',
      net_balance: '本月淨結餘',
      total_income: '總收入',
      total_expense: '總支出',
      savings_rate: '儲蓄率',
      daily_avg: '日均支出',
      tx_count: '記賬筆數',
      no_spend_days: '零支出天數',
      max_expense: '單筆最大',
      top_categories: '支出分類 TOP 5',
      persona_title: '財務人格與月度評語',
      footer_brand: 'MY LEDGER APP',
      footer_tagline: '掌控生活收支 · 走向財務自由',
      footer_verified: 'Verified & Generated',
      footer_secure: '本地安全加密賬本',
      footer_diagnosis: '全方位財務健康診斷報告',
      deep_title: '深度洞察與同比環比分析',
      deep_subtitle: '收支趨勢變動 · 50/30/20診斷 · 專屬儲蓄策略',
      mom_title: '環比上月 (MoM)',
      yoy_title: '同比去年 (YoY)',
      diff_expense: '支出',
      diff_income: '收入',
      diff_savings: '儲蓄率',
      no_prev_data: '無上一期對比數據',
      no_expense_recorded: '本月暫無支出記錄',
      rule_50_30_20: '50 / 30 / 20 預算法則健康診斷',
      needs_label: '必要支出 (Needs 50%)',
      wants_label: '彈性支出 (Wants 30%)',
      savings_label: '儲蓄結餘 (Savings 20%)',
      strategies_title: '💡 針對性儲蓄與改善建議',
      est_save_prefix: '預估月省',
      persona_badge: '✨ 評定稱號',
      other_label: '其他'
    },
    en: {
      brand: '✦ My Ledger',
      issue_prefix: 'Monthly Digest',
      digest_subtitle: 'Aesthetic Financial Performance Report',
      net_balance: 'Net Surplus',
      total_income: 'Total Income',
      total_expense: 'Total Expense',
      savings_rate: 'Savings Rate',
      daily_avg: 'Daily Avg Spend',
      tx_count: 'Transactions',
      no_spend_days: 'Zero-Spend Days',
      max_expense: 'Max Single Spend',
      top_categories: 'Top 5 Expense Categories',
      persona_title: 'Financial Persona & Monthly Verdict',
      footer_brand: 'MY LEDGER APP',
      footer_tagline: 'Master Your Cashflow · Path to Freedom',
      footer_verified: 'Verified & Generated',
      footer_secure: 'Self-Hosted Secure Ledger',
      footer_diagnosis: 'Comprehensive Financial Diagnosis',
      deep_title: 'Deep Financial Insights & Comparisons',
      deep_subtitle: 'MoM/YoY Trends · 50/30/20 Rule · Personalized Savings Tactics',
      mom_title: 'Month-over-Month (MoM)',
      yoy_title: 'Year-over-Year (YoY)',
      diff_expense: 'Expense',
      diff_income: 'Income',
      diff_savings: 'Savings Rate',
      no_prev_data: 'No baseline data for comparison',
      no_expense_recorded: 'No expenses recorded this month.',
      rule_50_30_20: '50 / 30 / 20 Budget Rule Diagnostic',
      needs_label: 'Needs (Benchmark 50%)',
      wants_label: 'Wants (Benchmark 30%)',
      savings_label: 'Savings (Benchmark 20%)',
      strategies_title: '💡 Actionable Savings & Optimization Tactics',
      est_save_prefix: 'Est. Save',
      persona_badge: '✨ Persona Title',
      other_label: 'Others'
    },
    ms: {
      brand: '✦ Buku Wang Saya',
      issue_prefix: 'Penyata Bulanan',
      digest_subtitle: 'Ringkasan Aliran Kewangan Bulanan',
      net_balance: 'Baki Bersih',
      total_income: 'Jumlah Pendapatan',
      total_expense: 'Jumlah Belanja',
      savings_rate: 'Kadar Simpanan',
      daily_avg: 'Purata Belanja Harian',
      tx_count: 'Bilangan Transaksi',
      no_spend_days: 'Hari Tanpa Belanja',
      max_expense: 'Belanja Terbesar',
      top_categories: 'Kategori Belanja Teratas (TOP 5)',
      persona_title: 'Persona Kewangan & Ulasan Bulanan',
      footer_brand: 'MY LEDGER APP',
      footer_tagline: 'Urus Aliran Wang · Menuju Kebebasan Kewangan',
      footer_verified: 'Verified & Generated',
      footer_secure: 'Buku Wang Selamat Sendiri',
      footer_diagnosis: 'Diagnosis Kesihatan Kewangan Menyeluruh',
      deep_title: 'Wawasan Mendalam & Perbandingan Tempoh',
      deep_subtitle: 'Trend MoM/YoY · Peraturan 50/30/20 · Pelan Simpanan Pintar',
      mom_title: 'Bulan-ke-Bulan (MoM)',
      yoy_title: 'Tahun-ke-Tahun (YoY)',
      diff_expense: 'Belanja',
      diff_income: 'Pendapatan',
      diff_savings: 'Kadar Simpanan',
      no_prev_data: 'Tiada data perbandingan sebelumnya',
      no_expense_recorded: 'Tiada perbelanjaan direkodkan bulan ini.',
      rule_50_30_20: 'Diagnosis Peraturan Belanjawan 50 / 30 / 20',
      needs_label: 'Keperluan (Sasaran 50%)',
      wants_label: 'Kehendak (Sasaran 30%)',
      savings_label: 'Simpanan (Sasaran 20%)',
      strategies_title: '💡 Cadangan Penjimatan & Pengurusan Pintar',
      est_save_prefix: 'Anggaran Jimat',
      persona_badge: '✨ Gelaran Persona',
      other_label: 'Lain-lain'
    }
  };

  function getPosterLocale(serverLang) {
    if (serverLang && I18N_POSTER[serverLang]) return serverLang;
    const raw = (
      serverLang ||
      window.LEDGER_LANG ||
      (window.I18N && window.I18N.__locale) ||
      document.documentElement.lang ||
      document.documentElement.getAttribute('data-lang') ||
      'zh'
    ).toLowerCase();
    if (raw.startsWith('zh_tw') || raw.startsWith('zh-tw') || raw.startsWith('zh-hant')) return 'zh_TW';
    if (raw.startsWith('en')) return 'en';
    if (raw.startsWith('ms') || raw.startsWith('my')) return 'ms';
    return 'zh';
  }

  function formatMoney(num) {
    return Number(num || 0).toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  function roundRect(ctx, x, y, w, h, r) {
    if (w < 2 * r) r = w / 2;
    if (h < 2 * r) r = h / 2;
    if (typeof ctx.roundRect === 'function') {
      ctx.beginPath();
      ctx.roundRect(x, y, w, h, r);
      return;
    }
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function drawGlassCard(ctx, x, y, w, h, r, tokens) {
    ctx.save();
    ctx.fillStyle = tokens.cardBg;
    roundRect(ctx, x, y, w, h, r);
    ctx.fill();

    ctx.strokeStyle = tokens.cardBorder;
    ctx.lineWidth = 1;
    roundRect(ctx, x, y, w, h, r);
    ctx.stroke();
    ctx.restore();
  }

  function wrapText(ctx, text, x, y, maxWidth, lineHeight, maxLines) {
    const chars = Array.from(text || '');
    let line = '';
    let curY = y;
    let linesDrawn = 0;

    for (let i = 0; i < chars.length; i++) {
      const testLine = line + chars[i];
      const metrics = ctx.measureText(testLine);
      if (metrics.width > maxWidth && i > 0) {
        linesDrawn++;
        if (maxLines && linesDrawn >= maxLines) {
          ctx.fillText(line.slice(0, -1) + '...', x, curY);
          return;
        }
        ctx.fillText(line, x, curY);
        line = chars[i];
        curY += lineHeight;
      } else {
        line = testLine;
      }
    }
    if (line) {
      ctx.fillText(line, x, curY);
    }
  }

  // 1. 绘制月度精粹海报 (Summary Poster)
  function renderPosterCanvas(data, isDark) {
    const tokens = getThemeTokens(isDark);
    const locale = getPosterLocale(data && data.lang);
    const txt = I18N_POSTER[locale] || I18N_POSTER.zh;

    const W = 640;
    const H = 1240;
    const scale = 2; // Retina 2x

    const canvas = document.createElement('canvas');
    canvas.width = W * scale;
    canvas.height = H * scale;

    const ctx = canvas.getContext('2d');
    ctx.scale(scale, scale);

    // 背景深/浅色渐变
    const bgGrad = ctx.createLinearGradient(0, 0, 0, H);
    bgGrad.addColorStop(0, tokens.bgGradTop);
    bgGrad.addColorStop(0.5, tokens.bgGradMid);
    bgGrad.addColorStop(1, tokens.bgGradBottom);
    ctx.fillStyle = bgGrad;
    ctx.fillRect(0, 0, W, H);

    // 氛围背景微光光晕
    ctx.save();
    const glow1 = ctx.createRadialGradient(120, 160, 20, 120, 160, 360);
    glow1.addColorStop(0, tokens.glow1);
    glow1.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = glow1;
    ctx.fillRect(0, 0, W, H);

    const glow2 = ctx.createRadialGradient(W - 100, 780, 20, W - 100, 780, 420);
    glow2.addColorStop(0, tokens.glow2);
    glow2.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = glow2;
    ctx.fillRect(0, 0, W, H);
    ctx.restore();

    // 细致精致外框
    ctx.strokeStyle = tokens.outerBorder;
    ctx.lineWidth = 1;
    roundRect(ctx, 16, 16, W - 32, H - 32, 24);
    ctx.stroke();

    const curr = data.currency_symbol || data.currency || 'RM';
    const metrics = data.metrics || {};
    const persona = data.persona || {};

    // 1. Header (Y: 48 - 140)
    ctx.fillStyle = tokens.accentGold;
    ctx.font = 'bold 13px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.brand, 44, 68);

    ctx.textAlign = 'right';
    ctx.fillStyle = tokens.textMuted;
    ctx.font = '600 12px system-ui, -apple-system, sans-serif';
    ctx.fillText(`${txt.issue_prefix} · ${data.month}`, W - 44, 68);
    ctx.textAlign = 'left';

    ctx.fillStyle = tokens.textHeading;
    ctx.font = 'bold 28px system-ui, -apple-system, sans-serif';
    const monthTitle = data.month_display_title || data.month;
    ctx.fillText(monthTitle, 44, 114);

    ctx.fillStyle = tokens.textSecondary;
    ctx.font = '13.5px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.digest_subtitle, 44, 138);

    // 2. Net Balance Hero Card (Y: 165 - 345, H: 180)
    const heroY = 165;
    drawGlassCard(ctx, 44, heroY, W - 88, 175, 20, tokens);

    ctx.fillStyle = tokens.textSecondary;
    ctx.font = '600 13px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.net_balance, 68, heroY + 38);

    const netBal = Number(metrics.net_balance || 0);
    const isNetPositive = netBal >= 0;
    ctx.fillStyle = isNetPositive ? tokens.emerald : tokens.rose;
    ctx.font = 'bold 36px "SF Pro Display", -apple-system, sans-serif';
    const netBalStr = `${isNetPositive ? '+' : ''}${curr} ${formatMoney(netBal)}`;
    ctx.fillText(netBalStr, 68, heroY + 84);

    // Hero Card 内部分割线
    ctx.fillStyle = tokens.divider;
    ctx.fillRect(68, heroY + 104, W - 136, 1);

    // 收入、支出与储蓄率三栏指标
    const colW = (W - 136) / 3;
    const statBaseY = heroY + 128;

    // 总收入
    ctx.fillStyle = tokens.textMuted;
    ctx.font = '11.5px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.total_income, 68, statBaseY);
    ctx.fillStyle = tokens.emerald;
    ctx.font = 'bold 15px system-ui, -apple-system, sans-serif';
    ctx.fillText(`${curr} ${formatMoney(metrics.total_income)}`, 68, statBaseY + 22);

    // 总支出
    ctx.fillStyle = tokens.textMuted;
    ctx.font = '11.5px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.total_expense, 68 + colW, statBaseY);
    ctx.fillStyle = tokens.rose;
    ctx.font = 'bold 15px system-ui, -apple-system, sans-serif';
    ctx.fillText(`${curr} ${formatMoney(metrics.total_expense)}`, 68 + colW, statBaseY + 22);

    // 储蓄率
    ctx.fillStyle = tokens.textMuted;
    ctx.font = '11.5px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.savings_rate, 68 + colW * 2, statBaseY);
    ctx.fillStyle = tokens.accentGold;
    ctx.font = 'bold 15px system-ui, -apple-system, sans-serif';
    ctx.fillText(`${metrics.savings_rate}%`, 68 + colW * 2, statBaseY + 22);

    // 3. 统计小方块四宫格 (Y: 360 - 455, H: 90)
    const gridY = 360;
    const gridCardW = (W - 88 - 36) / 4;
    const gridCardH = 82;
    const maxExpAmt = data.max_expense ? data.max_expense.amount : (metrics.max_expense || 0);
    const subStats = [
      { label: txt.daily_avg, val: `${curr} ${formatMoney(metrics.daily_avg_expense || metrics.avg_daily_expense)}` },
      { label: txt.tx_count, val: `${metrics.tx_count || 0}` },
      { label: txt.no_spend_days, val: `${metrics.zero_spend_days || metrics.no_spend_days || 0}` },
      { label: txt.max_expense, val: `${curr} ${formatMoney(maxExpAmt)}` }
    ];

    subStats.forEach((st, i) => {
      const gx = 44 + i * (gridCardW + 12);
      drawGlassCard(ctx, gx, gridY, gridCardW, gridCardH, 14, tokens);

      ctx.fillStyle = tokens.textMuted;
      ctx.font = '10.5px system-ui, -apple-system, sans-serif';
      ctx.fillText(st.label, gx + 12, gridY + 28);

      ctx.fillStyle = tokens.textPrimary;
      ctx.font = 'bold 13.5px system-ui, -apple-system, sans-serif';
      ctx.fillText(st.val, gx + 12, gridY + 56);
    });

    // 4. TOP 5 支出分类 (Y: 465 - 805, H: 335)
    const topCatY = 468;
    ctx.fillStyle = tokens.textSecondary;
    ctx.font = 'bold 13px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.top_categories, 44, topCatY);

    const topCardY = topCatY + 14;
    const topCardH = 320;
    drawGlassCard(ctx, 44, topCardY, W - 88, topCardH, 18, tokens);

    const categories = data.top_categories || [];
    const catSlotH = 56;
    const catColors = [
      ['#F59E0B', '#D97706'],
      ['#6366F1', '#4F46E5'],
      ['#EC4899', '#DB2777'],
      ['#10B981', '#059669'],
      ['#8B5CF6', '#7C3AED']
    ];

    if (categories.length === 0) {
      ctx.fillStyle = tokens.textMuted;
      ctx.font = '13px system-ui, -apple-system, sans-serif';
      ctx.fillText(txt.no_expense_recorded, 68, topCardY + 45);
    } else {
      categories.slice(0, 5).forEach((c, idx) => {
        const ry = topCardY + 20 + idx * catSlotH;
        const colors = catColors[idx % catColors.length];

        ctx.fillStyle = colors[0];
        ctx.beginPath();
        ctx.arc(74, ry - 4, 4.5, 0, Math.PI * 2);
        ctx.fill();

        const catName = c.name || (window.t_cat ? window.t_cat(c.raw_name) : (c.raw_name || ''));
        ctx.fillStyle = tokens.textPrimary;
        ctx.font = '500 13px system-ui, -apple-system, sans-serif';
        ctx.fillText(catName, 88, ry);

        ctx.textAlign = 'right';
        ctx.fillStyle = tokens.textMuted;
        ctx.font = '12px system-ui, -apple-system, sans-serif';
        ctx.fillText(`${c.percentage}%`, W - 146, ry);

        ctx.fillStyle = tokens.textPrimary;
        ctx.font = '600 13px system-ui, -apple-system, sans-serif';
        ctx.fillText(`${curr} ${formatMoney(c.amount)}`, W - 66, ry);
        ctx.textAlign = 'left';

        const barTrackX = 66;
        const barTrackY = ry + 8;
        const barTrackW = W - 132;
        ctx.fillStyle = tokens.barTrack;
        roundRect(ctx, barTrackX, barTrackY, barTrackW, 5, 2.5);
        ctx.fill();

        const fillW = Math.max(8, Math.min(barTrackW, (c.percentage / 100) * barTrackW));
        const barGrad = ctx.createLinearGradient(barTrackX, 0, barTrackX + fillW, 0);
        barGrad.addColorStop(0, colors[0]);
        barGrad.addColorStop(1, colors[1]);
        ctx.fillStyle = barGrad;
        roundRect(ctx, barTrackX, barTrackY, fillW, 5, 2.5);
        ctx.fill();
      });
    }

    // 5. 财务人格与月度评语 (Y: 825 - 1045, H: 200)
    const personaY = 825;
    ctx.fillStyle = tokens.textSecondary;
    ctx.font = 'bold 13px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.persona_title, 44, personaY);

    const personaCardY = personaY + 14;
    const personaCardH = 200;
    drawGlassCard(ctx, 44, personaCardY, W - 88, personaCardH, 18, tokens);

    ctx.save();
    const goldAccent = ctx.createLinearGradient(0, personaCardY, 0, personaCardY + personaCardH);
    goldAccent.addColorStop(0, tokens.accentGold);
    goldAccent.addColorStop(1, '#D97706');
    ctx.fillStyle = goldAccent;
    roundRect(ctx, 44, personaCardY, 5, personaCardH, 2.5);
    ctx.fill();
    ctx.restore();

    ctx.fillStyle = tokens.accentGoldText;
    ctx.font = 'bold 16px system-ui, -apple-system, sans-serif';
    const fallbackTitle = (locale === 'en' ? 'Wealth Explorer' : (locale === 'ms' ? 'Peneroka Kewangan' : (locale === 'zh_TW' ? '理財探尋者' : '理财探寻者')));
    const personaTitleText = `${txt.persona_badge}: ${persona.title || fallbackTitle}`;
    ctx.fillText(personaTitleText, 68, personaCardY + 36);

    ctx.fillStyle = tokens.textPrimary;
    ctx.font = '13.5px system-ui, -apple-system, "PingFang SC", sans-serif';
    const fallbackComment = (locale === 'en' ? 'Track your expenses consistently to master your financial destiny.' : (locale === 'ms' ? 'Kekalkan tabiat mencatat perbelanjaan untuk masa depan kewangan yang kukuh.' : (locale === 'zh_TW' ? '保持記賬習慣，清晰掌控每一分財務未來。' : '保持记账习惯，清晰掌控每一分财务未来。')));
    wrapText(ctx, persona.commentary || fallbackComment, 68, personaCardY + 68, W - 136, 23, 4);

    if (data.quote) {
      ctx.fillStyle = tokens.badgeBg;
      roundRect(ctx, 68, personaCardY + 145, W - 136, 38, 10);
      ctx.fill();

      ctx.fillStyle = tokens.textSecondary;
      ctx.font = 'italic 12px "Playfair Display", Georgia, serif';
      ctx.fillText(`“${data.quote}”`, 82, personaCardY + 168);
    }

    // 6. 底部落款与水印 (Y: 1060 - 1220)
    const footerY = 1060;
    ctx.fillStyle = tokens.divider;
    ctx.fillRect(44, footerY, W - 88, 1);

    ctx.fillStyle = tokens.textSecondary;
    ctx.font = 'bold 12px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.footer_brand, 44, footerY + 34);

    ctx.fillStyle = tokens.textMuted;
    ctx.font = '11.5px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.footer_tagline, 44, footerY + 52);

    const todayStr = new Date().toISOString().slice(0, 10);
    ctx.textAlign = 'right';
    ctx.fillStyle = tokens.textMuted;
    ctx.font = '11px system-ui, -apple-system, sans-serif';
    ctx.fillText(`${txt.footer_verified} · ${todayStr}`, W - 44, footerY + 34);
    ctx.fillText(txt.footer_secure, W - 44, footerY + 52);
    ctx.textAlign = 'left';

    return canvas;
  }

  // 2. 绘制深度洞察与对比长图 (Deep Analysis Report)
  function renderDeepAnalysisCanvas(data, isDark) {
    const tokens = getThemeTokens(isDark);
    const locale = getPosterLocale(data && data.lang);
    const txt = I18N_POSTER[locale] || I18N_POSTER.zh;

    const W = 640;
    const H = 1640;
    const scale = 2; // Retina 2x

    const canvas = document.createElement('canvas');
    canvas.width = W * scale;
    canvas.height = H * scale;

    const ctx = canvas.getContext('2d');
    ctx.scale(scale, scale);

    // 背景深/浅色渐变
    const bgGrad = ctx.createLinearGradient(0, 0, 0, H);
    bgGrad.addColorStop(0, tokens.bgGradTop);
    bgGrad.addColorStop(0.35, tokens.bgGradMid);
    bgGrad.addColorStop(1, tokens.bgGradBottom);
    ctx.fillStyle = bgGrad;
    ctx.fillRect(0, 0, W, H);

    // 细致高贵微光光晕
    ctx.save();
    const glow1 = ctx.createRadialGradient(160, 220, 30, 160, 220, 420);
    glow1.addColorStop(0, tokens.glow1);
    glow1.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = glow1;
    ctx.fillRect(0, 0, W, H);

    const glow2 = ctx.createRadialGradient(W - 120, 1000, 30, W - 120, 1000, 500);
    glow2.addColorStop(0, tokens.glow2);
    glow2.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = glow2;
    ctx.fillRect(0, 0, W, H);
    ctx.restore();

    // 外边框
    ctx.strokeStyle = tokens.outerBorder;
    ctx.lineWidth = 1;
    roundRect(ctx, 16, 16, W - 32, H - 32, 24);
    ctx.stroke();

    const curr = data.currency_symbol || data.currency || 'RM';
    const comp = data.comparison || {};
    const diag = data.budget_diagnostic || {};
    const strategies = data.savings_strategies || [];

    // 1. Header (Y: 48 - 140)
    ctx.fillStyle = tokens.accentGold;
    ctx.font = 'bold 13px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.brand, 44, 68);

    ctx.textAlign = 'right';
    ctx.fillStyle = tokens.textMuted;
    ctx.font = '600 12px system-ui, -apple-system, sans-serif';
    ctx.fillText(`${txt.deep_title} · ${data.month}`, W - 44, 68);
    ctx.textAlign = 'left';

    ctx.fillStyle = tokens.textHeading;
    ctx.font = 'bold 26px system-ui, -apple-system, sans-serif';
    const monthTitle = data.month_display_title || data.month;
    ctx.fillText(`${monthTitle} ${txt.deep_title}`, 44, 114);

    ctx.fillStyle = tokens.textSecondary;
    ctx.font = '13.5px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.deep_subtitle, 44, 138);

    // 2. 期间对比双卡片: 环比上月 (MoM) & 同比去年 (YoY) (Y: 165 - 390, H: 215)
    const compY = 165;
    const halfCardW = (W - 88 - 16) / 2;
    const compCardH = 205;

    function drawComparisonBox(x, y, w, h, title, diffData, targetMonthStr) {
      drawGlassCard(ctx, x, y, w, h, 18, tokens);

      ctx.fillStyle = tokens.textSecondary;
      ctx.font = 'bold 13.5px system-ui, -apple-system, sans-serif';
      ctx.fillText(title, x + 18, y + 32);

      ctx.textAlign = 'right';
      ctx.fillStyle = tokens.textMuted;
      ctx.font = '11px system-ui, -apple-system, sans-serif';
      ctx.fillText(targetMonthStr || '', x + w - 18, y + 32);
      ctx.textAlign = 'left';

      ctx.fillStyle = tokens.divider;
      ctx.fillRect(x + 18, y + 46, w - 36, 1);

      if (!diffData || !(diffData.has_baseline || diffData.has_data)) {
        ctx.fillStyle = tokens.textMuted;
        ctx.font = '12.5px system-ui, -apple-system, sans-serif';
        ctx.fillText(txt.no_prev_data, x + 18, y + 110);
        return;
      }

      const rows = [
        { label: txt.diff_expense, val: diffData.expense_diff, pct: diffData.expense_pct, isExpense: true },
        { label: txt.diff_income, val: diffData.income_diff, pct: diffData.income_pct, isExpense: false },
        { label: txt.diff_savings, val: diffData.savings_rate_diff, isRate: true }
      ];

      rows.forEach((r, idx) => {
        const ry = y + 76 + idx * 40;
        ctx.fillStyle = tokens.textMuted;
        ctx.font = '12px system-ui, -apple-system, sans-serif';
        ctx.fillText(r.label, x + 18, ry);

        ctx.textAlign = 'right';
        let isPositive = false;
        let str = '';

        if (r.isRate) {
          const rateVal = Number(r.val || 0);
          isPositive = rateVal >= 0;
          str = `${rateVal >= 0 ? '+' : ''}${rateVal.toFixed(1)}%`;
        } else {
          const numVal = Number(r.val || 0);
          isPositive = r.isExpense ? (numVal <= 0) : (numVal >= 0);
          const sign = numVal >= 0 ? '+' : '';
          const pctStr = r.pct !== null && r.pct !== undefined ? ` (${r.pct >= 0 ? '+' : ''}${r.pct}%)` : '';
          str = `${sign}${curr} ${formatMoney(Math.abs(numVal))}${pctStr}`;
        }

        ctx.fillStyle = isPositive ? tokens.emerald : tokens.rose;
        ctx.font = '600 12.5px system-ui, -apple-system, sans-serif';
        ctx.fillText(str, x + w - 18, ry);
        ctx.textAlign = 'left';
      });
    }

    drawComparisonBox(44, compY, halfCardW, compCardH, txt.mom_title, comp.mom, (comp.mom && (comp.mom.prev_month || comp.mom.month)) || '');
    drawComparisonBox(44 + halfCardW + 16, compY, halfCardW, compCardH, txt.yoy_title, comp.yoy, (comp.yoy && (comp.yoy.prev_month || comp.yoy.month)) || '');

    // 3. 50/30/20 预算法则健康诊断 (Y: 395 - 615, H: 215)
    const diagY = 395;
    ctx.fillStyle = tokens.textSecondary;
    ctx.font = 'bold 13px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.rule_50_30_20, 44, diagY);

    const diagCardY = diagY + 14;
    const diagCardH = 205;
    drawGlassCard(ctx, 44, diagCardY, W - 88, diagCardH, 18, tokens);

    const needsPct = Number(diag.needs_pct || 0);
    const wantsPct = Number(diag.wants_pct || 0);
    const savPct = Number(diag.savings_pct || 0);

    // 三段式比例进度条
    const barX = 68;
    const barY = diagCardY + 28;
    const barW = W - 136;
    const barH = 14;

    const needsW = Math.max(0, Math.min(barW, (needsPct / 100) * barW));
    const wantsW = Math.max(0, Math.min(barW - needsW, (wantsPct / 100) * barW));
    const savW = Math.max(0, barW - needsW - wantsW);

    ctx.fillStyle = tokens.barTrack;
    roundRect(ctx, barX, barY, barW, barH, 7);
    ctx.fill();

    // Needs (青色)
    if (needsW > 0) {
      ctx.fillStyle = '#38BDF8';
      roundRect(ctx, barX, barY, needsW, barH, 7);
      ctx.fill();
    }
    // Wants (紫粉色)
    if (wantsW > 0) {
      ctx.fillStyle = '#F472B6';
      roundRect(ctx, barX + needsW, barY, wantsW, barH, 7);
      ctx.fill();
    }
    // Savings (翡翠绿)
    if (savW > 0) {
      ctx.fillStyle = tokens.emerald;
      roundRect(ctx, barX + needsW + wantsW, barY, savW, barH, 7);
      ctx.fill();
    }

    // 进度条下方图例与实际金额
    const legY = barY + 36;
    const legSlots = [
      { color: '#38BDF8', label: txt.needs_label, pct: needsPct, amt: diag.needs_amount },
      { color: '#F472B6', label: txt.wants_label, pct: wantsPct, amt: diag.wants_amount },
      { color: tokens.emerald, label: txt.savings_label, pct: savPct, amt: diag.savings_amount }
    ];

    const slotW = (W - 136) / 3;
    legSlots.forEach((ls, i) => {
      const sx = barX + i * slotW;
      ctx.fillStyle = ls.color;
      ctx.beginPath();
      ctx.arc(sx + 5, legY, 5, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = tokens.textPrimary;
      ctx.font = 'bold 12px system-ui, -apple-system, sans-serif';
      ctx.fillText(`${ls.pct}%`, sx + 16, legY + 4);

      ctx.fillStyle = tokens.textMuted;
      ctx.font = '11px system-ui, -apple-system, sans-serif';
      ctx.fillText(ls.label, sx, legY + 24);

      ctx.fillStyle = tokens.textSecondary;
      ctx.font = '500 11.5px system-ui, -apple-system, sans-serif';
      ctx.fillText(`${curr} ${formatMoney(ls.amt)}`, sx, legY + 42);
    });

    // 诊断评定徽章与建议总结
    const diagNoteY = diagCardY + 145;
    ctx.fillStyle = tokens.badgeBg;
    roundRect(ctx, 68, diagNoteY, W - 136, 42, 12);
    ctx.fill();

    const fallbackDiagTitle = (locale === 'en' ? 'Balanced & Steady' : (locale === 'ms' ? 'Seimbang & Stabil' : (locale === 'zh_TW' ? '財務配置平穩' : '财务配置平稳')));
    const fallbackDiagDesc = (locale === 'en' ? 'Healthy balance across categories. Keep tracking to maintain solid momentum.' : (locale === 'ms' ? 'Keseimbangan sihat merentasi kategori. Teruskan catatan untuk mengekalkan kestabilan.' : (locale === 'zh_TW' ? '整體收支平衡適中，保持穩健記賬與預算把控即可持續沉澱資產。' : '整体收支平衡适中，保持稳健记账与预算把控即可持续沉淀资产。')));

    ctx.fillStyle = tokens.accentGoldText;
    ctx.font = 'bold 12px system-ui, -apple-system, sans-serif';
    ctx.fillText(`⚡ ${diag.status_title || fallbackDiagTitle}:`, 82, diagNoteY + 26);

    ctx.fillStyle = tokens.textPrimary;
    ctx.font = '12px system-ui, -apple-system, sans-serif';
    ctx.fillText(diag.status_desc || fallbackDiagDesc, 210, diagNoteY + 26);

    // 4. 专属储蓄改善建议 (Y: 635 - 1460, H: 810)
    const stratY = 635;
    ctx.fillStyle = tokens.textSecondary;
    ctx.font = 'bold 13px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.strategies_title, 44, stratY);

    const stratCardY = stratY + 14;
    const stratCardH = 780;
    drawGlassCard(ctx, 44, stratCardY, W - 88, stratCardH, 20, tokens);

    ctx.save();
    const gStripe = ctx.createLinearGradient(0, stratCardY, 0, stratCardY + stratCardH);
    gStripe.addColorStop(0, tokens.accentGold);
    gStripe.addColorStop(1, tokens.emerald);
    ctx.fillStyle = gStripe;
    roundRect(ctx, 44, stratCardY, 5, stratCardH, 2.5);
    ctx.fill();
    ctx.restore();

    const itemSlotH = 180;
    strategies.slice(0, 4).forEach((s, idx) => {
      const sy = stratCardY + 20 + idx * itemSlotH;

      ctx.fillStyle = tokens.textPrimary;
      ctx.font = 'bold 15px system-ui, -apple-system, sans-serif';
      ctx.fillText(`${s.icon || '💡'} ${s.title}`, 68, sy + 18);

      if (s.badge) {
        ctx.save();
        ctx.font = '600 11px system-ui, -apple-system, sans-serif';
        const bW = ctx.measureText(s.badge).width + 20;
        const bX = W - 68 - bW;
        ctx.fillStyle = tokens.badgeBg;
        roundRect(ctx, bX, sy + 2, bW, 22, 11);
        ctx.fill();
        ctx.fillStyle = tokens.accentGoldText;
        ctx.fillText(s.badge, bX + 10, sy + 17);
        ctx.restore();
      }

      ctx.fillStyle = tokens.textSecondary;
      ctx.font = '13px system-ui, -apple-system, "PingFang SC", sans-serif';
      wrapText(ctx, s.detail || '', 68, sy + 48, W - 136, 21, 3);

      if (s.est_saving) {
        ctx.fillStyle = tokens.accentGoldBg;
        roundRect(ctx, 68, sy + 118, 220, 26, 13);
        ctx.fill();
        ctx.fillStyle = tokens.accentGoldText;
        ctx.font = '600 11.5px system-ui, -apple-system, sans-serif';
        ctx.fillText(`💰 ${txt.est_save_prefix}: ${s.est_saving}`, 80, sy + 135);
      }

      if (idx < 3) {
        ctx.fillStyle = tokens.divider;
        ctx.fillRect(68, sy + itemSlotH - 12, W - 136, 1);
      }
    });

    // 5. Footer (Y: 1485 - 1640)
    const footerY = 1485;
    if (data.quote) {
      ctx.fillStyle = tokens.badgeBg;
      roundRect(ctx, 44, footerY, W - 88, 44, 12);
      ctx.fill();

      ctx.fillStyle = tokens.textSecondary;
      ctx.font = 'italic 12.5px "Playfair Display", Georgia, serif';
      ctx.fillText(`“${data.quote}”`, 64, footerY + 27);
    }

    const fLineY = footerY + 65;
    ctx.fillStyle = tokens.divider;
    ctx.fillRect(44, fLineY, W - 88, 1);

    ctx.fillStyle = tokens.textSecondary;
    ctx.font = 'bold 12px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.footer_brand, 44, fLineY + 34);

    ctx.fillStyle = tokens.textMuted;
    ctx.font = '11.5px system-ui, -apple-system, sans-serif';
    ctx.fillText(txt.footer_tagline, 44, fLineY + 52);

    const todayStr = new Date().toISOString().slice(0, 10);
    ctx.textAlign = 'right';
    ctx.fillStyle = tokens.textMuted;
    ctx.font = '11px system-ui, -apple-system, sans-serif';
    ctx.fillText(`${txt.footer_verified} · ${todayStr}`, W - 44, fLineY + 34);
    ctx.fillText(txt.footer_diagnosis, W - 44, fLineY + 52);
    ctx.textAlign = 'left';

    return canvas;
  }

  function downloadPosterImage(dataUrl, monthStr, reportType) {
    const filename = `Ledger_Report_${monthStr || 'Monthly'}_${reportType || 'summary'}.png`;
    const link = document.createElement('a');
    link.download = filename;
    link.href = dataUrl;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }

  async function copyPosterImage(canvas) {
    try {
      if (navigator.clipboard && window.ClipboardItem && canvas.toBlob) {
        canvas.toBlob(async function (blob) {
          if (!blob) {
            fallbackCopyNotice();
            return;
          }
          try {
            await navigator.clipboard.write([
              new ClipboardItem({ 'image/png': blob })
            ]);
            if (window.Swal) {
              Swal.fire({
                toast: true,
                position: 'top-end',
                icon: 'success',
                title: window.t ? window.t('poster.copied_toast', '海报已复制到剪贴板！') : '海报已复制到剪贴板！',
                showConfirmButton: false,
                timer: 2200
              });
            }
          } catch (err) {
            fallbackCopyNotice();
          }
        }, 'image/png');
      } else {
        fallbackCopyNotice();
      }
    } catch (e) {
      fallbackCopyNotice();
    }
  }

  function fallbackCopyNotice() {
    if (window.Swal) {
      Swal.fire({
        toast: true,
        position: 'top-end',
        icon: 'info',
        title: window.t ? window.t('poster.copy_fallback', '请长按或右键图片另存为') : '请长按或右键图片另存为',
        showConfirmButton: false,
        timer: 2600
      });
    }
  }

  // 3. 打开月度海报与深度分析弹窗
  window.openMonthlyPosterModal = async function (monthStr) {
    if (!monthStr) {
      const curEl = document.querySelector('.month-nav-current');
      monthStr = curEl ? curEl.textContent.trim() : '';
    }

    if (window.Swal) {
      Swal.fire({
        title: window.t ? window.t('poster.generating', '正在绘制极美长图海报...') : '正在绘制极美长图海报...',
        allowOutsideClick: false,
        didOpen: () => {
          Swal.showLoading();
        }
      });
    }

    try {
      const locale = getPosterLocale();
      const res = await fetch(`/api/reports/monthly_poster?month=${encodeURIComponent(monthStr)}&lang=${encodeURIComponent(locale)}`, {
        headers: {
          'Accept': 'application/json',
          'X-Requested-With': 'XMLHttpRequest'
        }
      });
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const data = await res.json();
      if (!data.ok) {
        throw new Error(data.message || 'Failed to fetch poster data');
      }

      let activeReportType = 'summary'; // 'summary' or 'deep'
      let activeIsDark = isDarkTheme();

      let currentCanvas = null;
      let currentDataUrl = '';

      function getPosterCanvas() {
        return activeReportType === 'deep'
          ? renderDeepAnalysisCanvas(data, activeIsDark)
          : renderPosterCanvas(data, activeIsDark);
      }

      currentCanvas = getPosterCanvas();
      currentDataUrl = currentCanvas.toDataURL('image/png');

      const modalTitleText = window.t ? window.t('poster.modal_title', '月度财务长图海报') : '月度财务长图海报';
      const summaryBtnText = window.t ? window.t('poster.type_summary', '月度精粹长图') : '月度精粹长图';
      const deepBtnText = window.t ? window.t('poster.type_deep', '深度洞察与对比') : '深度洞察与对比';
      const monthTitle = data.month_display_title || data.month;

      if (window.Swal) {
        Swal.fire({
          title: `<div style="font-size: 16.5px; font-weight: 700; display: flex; align-items: center; justify-content: center; gap: 6px;"><span>🎨</span> ${modalTitleText} (${monthTitle})</div>`,
          html: `
            <div class="poster-modal-body">
              <div class="poster-control-bar">
                <div class="poster-tabs" role="tablist">
                  <button type="button" class="poster-tab-btn active" id="btnTabSummary">📊 ${summaryBtnText}</button>
                  <button type="button" class="poster-tab-btn" id="btnTabDeep">🔬 ${deepBtnText}</button>
                </div>
                <button type="button" class="poster-theme-toggle" id="btnTogglePosterTheme">
                  <span>🌓</span> <span id="posterThemeText">${activeIsDark ? (window.t ? window.t('poster.theme_dark', '深色风格') : '深色风格') : (window.t ? window.t('poster.theme_light', '浅色风格') : '浅色风格')}</span>
                </button>
              </div>
              <div class="poster-preview-viewport ${activeIsDark ? '' : 'light-frame'}" id="posterPreviewViewport">
                <img src="${currentDataUrl}" class="poster-preview-img" id="posterPreviewImg" alt="Financial Poster" />
              </div>
              <div class="poster-actions-row">
                <button type="button" class="btn-poster-action btn-poster-download" id="swalBtnDownloadPoster">
                  ${window.t ? window.t('poster.download_png', '📥 保存高清海报 (PNG)') : '📥 保存高清海报 (PNG)'}
                </button>
                <button type="button" class="btn-poster-action btn-poster-copy" id="swalBtnCopyPoster">
                  ${window.t ? window.t('poster.copy_img', '📋 复制图片') : '📋 复制图片'}
                </button>
              </div>
              <span class="poster-hint-text">${window.t ? window.t('poster.copy_fallback', '长按或右键图片亦可直接保存') : '长按或右键图片亦可直接保存'}</span>
            </div>
          `,
          customClass: {
            popup: 'poster-swal-popup'
          },
          showConfirmButton: false,
          showCloseButton: true,
          didOpen: () => {
            const btnSummary = document.getElementById('btnTabSummary');
            const btnDeep = document.getElementById('btnTabDeep');
            const btnTheme = document.getElementById('btnTogglePosterTheme');
            const themeTxt = document.getElementById('posterThemeText');
            const viewport = document.getElementById('posterPreviewViewport');
            const imgEl = document.getElementById('posterPreviewImg');
            const downloadBtn = document.getElementById('swalBtnDownloadPoster');
            const copyBtn = document.getElementById('swalBtnCopyPoster');

            function refreshDisplay() {
              currentCanvas = getPosterCanvas();
              currentDataUrl = currentCanvas.toDataURL('image/png');
              if (imgEl) {
                imgEl.src = currentDataUrl;
              }
              if (viewport) {
                if (activeIsDark) {
                  viewport.classList.remove('light-frame');
                } else {
                  viewport.classList.add('light-frame');
                }
              }
              if (themeTxt) {
                themeTxt.textContent = activeIsDark
                  ? (window.t ? window.t('poster.theme_dark', '深色风格') : '深色风格')
                  : (window.t ? window.t('poster.theme_light', '浅色风格') : '浅色风格');
              }
            }

            if (btnSummary) {
              btnSummary.addEventListener('click', () => {
                if (activeReportType === 'summary') return;
                activeReportType = 'summary';
                btnSummary.classList.add('active');
                if (btnDeep) btnDeep.classList.remove('active');
                refreshDisplay();
              });
            }

            if (btnDeep) {
              btnDeep.addEventListener('click', () => {
                if (activeReportType === 'deep') return;
                activeReportType = 'deep';
                btnDeep.classList.add('active');
                if (btnSummary) btnSummary.classList.remove('active');
                refreshDisplay();
              });
            }

            if (btnTheme) {
              btnTheme.addEventListener('click', () => {
                activeIsDark = !activeIsDark;
                refreshDisplay();
              });
            }

            if (downloadBtn) {
              downloadBtn.addEventListener('click', () => {
                downloadPosterImage(currentDataUrl, data.month, activeReportType);
              });
            }

            if (copyBtn) {
              copyBtn.addEventListener('click', () => {
                if (currentCanvas) {
                  copyPosterImage(currentCanvas);
                }
              });
            }
          }
        });
      }
    } catch (err) {
      console.error('Error generating monthly poster:', err);
      if (window.Swal) {
        Swal.fire({
          icon: 'error',
          title: window.t ? window.t('poster.error_title', '生成海报失败') : '生成海报失败',
          text: err.message || (window.t ? window.t('poster.error_desc', '请稍后重试') : '请稍后重试'),
          confirmButtonText: window.t ? window.t('common.confirm', '确定') : '确定'
        });
      }
    }
  };
})();
