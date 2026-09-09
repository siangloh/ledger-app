function formatMoney(value) {
  const n = Number(value) || 0;
  return 'RM ' + n.toLocaleString('en-MY', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function syncSegStyles() {
  document.querySelectorAll('.seg').forEach(function (label) {
    const input = label.querySelector('input');
    label.classList.toggle('seg-checked', !!(input && input.checked));
  });
}

function onTypeChange() {
  const checkedRadio = document.querySelector('input[name="type"]:checked');
  if (!checkedRadio) return;
  const type = checkedRadio.value;
  const groupRow = document.getElementById('groupRow');
  if (groupRow) groupRow.style.display = (type === 'income') ? 'flex' : 'none';
  const fromSavingsRow = document.getElementById('fromSavingsRow');
  if (fromSavingsRow) {
    fromSavingsRow.style.display = (type === 'expense') ? 'flex' : 'none';
    if (type !== 'expense') {
      const chk = fromSavingsRow.querySelector('input[type="checkbox"]');
      if (chk) chk.checked = false;
    }
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
}

function quickFillForm(amount, category, note) {
  const typeRadio = document.querySelector('input[name="type"][value="expense"]');
  if (typeRadio) {
    typeRadio.checked = true;
    onTypeChange();
  }
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
    return '<option value="' + o + '"' + (o === preselect ? ' selected' : '') + '>' + o + '</option>';
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
    title: title || '操作未完成',
    html: message,
    confirmButtonText: '知道了',
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
      Swal.fire({
        icon: 'warning',
        iconColor: '#a8475a',
        title: form.dataset.confirmTitle || '确认删除？',
        html: (form.dataset.confirmText || '此操作不可撤销。') + '<br><span style="color:#8a877e;font-size:13px;">删除后提供 5 秒撤销恢复窗口。</span>',
        showCancelButton: true,
        reverseButtons: true,
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
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
          Swal.fire({
            toast: true,
            position: 'bottom-end',
            icon: 'info',
            title: '已删除项目',
            html: '<span style="font-size:12px;color:var(--muted)">如需撤销请在 5 秒内点击</span>',
            timer: 5000,
            timerProgressBar: true,
            showConfirmButton: true,
            confirmButtonText: '撤销 (Undo)',
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
                title: '已撤销删除',
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
                    title: data.message || '已删除',
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
                  errorAlert((data && data.message) || '删除失败');
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
                errorAlert('网络连接异常，删除未完成');
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
  form.addEventListener('submit', function (e) {
    const amountInput = form.querySelector('input[name="amount"]');
    const categorySelect = form.querySelector('select[name="category"]');
    const amount = parseFloat(amountInput ? amountInput.value : '');

    if (!amountInput || amountInput.value.trim() === '' || isNaN(amount)) {
      e.preventDefault();
      errorAlert('金额必须是大于 0 的数字，不能留空。', '请检查表单');
      return;
    }
    if (amount <= 0) {
      e.preventDefault();
      errorAlert('金额必须是大于 0 的数字，当前填写的是 ' + amountInput.value + '。', '请检查表单');
      return;
    }
    if (categorySelect && !categorySelect.value) {
      e.preventDefault();
      errorAlert('请先在「分类管理」里添加至少一个对应分类，再回来录入。', '缺少可选分类');
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
        Swal.fire({
          toast: true,
          position: 'top-end',
          icon: 'success',
          title: data.message || '操作成功',
          showConfirmButton: false,
          timer: 2500,
          timerProgressBar: true,
          customClass: { popup: 'app-swal-toast' }
        });

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

          if (typeof htmx !== 'undefined') {
            htmx.ajax('GET', window.location.href, { target: '#mainContainer', swap: 'innerHTML' });
          } else {
            window.location.reload();
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
    return '<option value="' + o + '"' + (o === preselect ? ' selected' : '') + '>' + o + '</option>';
  }).join('');
}

function buildNlpConfirmHtml(parsed, warnings) {
  const warningHtml = warnings && warnings.length
    ? '<div class="warning-box">' + warnings.map(function (w) { return '⚠ ' + w; }).join('<br>') + '<br>请核对下方字段，确认无误后再保存。</div>'
    : '';
  return (
    warningHtml +
    '<div class="row seg-row">' +
    '  <label class="seg"><input type="radio" name="swalType" value="expense"><span>支出</span></label>' +
    '  <label class="seg"><input type="radio" name="swalType" value="income"><span>收入</span></label>' +
    '</div>' +
    '<div class="row seg-row" id="swalGroupRow">' +
    '  <label class="seg"><input type="radio" name="swalGroup" value="main"><span>主业收入</span></label>' +
    '  <label class="seg"><input type="radio" name="swalGroup" value="side"><span>副业收入</span></label>' +
    '</div>' +
    '<div class="row">' +
    '  <label>日期 <input type="date" id="swalDate"></label>' +
    '  <label>金额 <input type="number" id="swalAmount" step="0.01" min="0.01"></label>' +
    '</div>' +
    '<div class="row">' +
    '  <label>分类 <select id="swalCategory" data-preselect="' + (parsed.category || '') + '"></select></label>' +
    '  <label>备注 <input type="text" id="swalNote"></label>' +
    '</div>'
  );
}

function openNlpConfirmDialog(parsed, warnings) {
  Swal.fire({
    title: '确认解析结果',
    html: buildNlpConfirmHtml(parsed, warnings),
    showCancelButton: true,
    reverseButtons: true,
    confirmButtonText: '确认保存',
    cancelButtonText: '取消',
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
        Swal.showValidationMessage('请选择日期');
        return false;
      }
      if (amountValue.trim() === '' || isNaN(amount) || amount <= 0) {
        Swal.showValidationMessage('金额必须是大于 0 的数字');
        return false;
      }
      if (!category) {
        Swal.showValidationMessage('请先在「分类管理」里添加对应分类');
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
          if (typeof htmx !== 'undefined') {
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
      errorAlert('请输入一句话，如「打车 32.5」。', '请先输入内容');
      return;
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
        openNlpConfirmDialog(data.parsed, data.warnings);
      })
      .catch(function () {
        errorAlert('网络连接失败，请稍后重试。', '解析失败');
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
    if (window.CHART_DATA) {
      drawMonthlyDoughnut('incomeChart', window.CHART_DATA.income.labels, window.CHART_DATA.income.values);
      drawMonthlyDoughnut('expenseChart', window.CHART_DATA.expense.labels, window.CHART_DATA.expense.values);
    }
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
        if (badge) badge.textContent = '加载失败，请刷新重试';
        return;
      }
      currentOverviewData = data;
      renderOverview(data);
      attachTrendChartHover();
    })
    .catch(function (err) {
      console.error(err);
      if (badge) badge.textContent = '网络异常，无法获取统计数据';
    });
}

function renderOverview(data) {
  const badge = document.getElementById('overviewRangeBadge');
  if (badge) {
    let rangeDesc = '全部历史';
    if (data.range === '12m') rangeDesc = '近 12 个月';
    else if (data.range === 'ytd') rangeDesc = '本年度 (YTD)';
    else if (data.range === 'custom') rangeDesc = (data.start_date || '最早') + ' 至 ' + (data.end_date || '至今');
    badge.textContent = rangeDesc + ' · 跨度 ' + data.num_months + ' 个月';
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
  if (netSav) netSav.textContent = formatMoney(m.net_savings);
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
  drawOverviewDoughnut('overviewIncomeChart', ['主业收入', '副业收入'], [incGrp.main, incGrp.side]);

  const sideBadge = document.getElementById('sideIncomeBadge');
  if (sideBadge) {
    sideBadge.textContent = '副业占比: ' + (incGrp.side_ratio || 0).toFixed(1) + '%';
  }

  const sideDesc = document.getElementById('sideRatioDesc');
  if (sideDesc) {
    const r = incGrp.side_ratio || 0;
    if (r >= 35) {
      sideDesc.textContent = '副业贡献率达 ' + r.toFixed(1) + '%，副业成长显著，收入来源具备很强的防御性与弹性。';
    } else if (r > 0) {
      sideDesc.textContent = '副业累计贡献 ' + formatMoney(incGrp.side) + ' (' + r.toFixed(1) + '%)，主业为基本盘，副业稳健增益。';
    } else {
      sideDesc.textContent = '选定范围内暂无副业收入记录，当前收入 100% 来自主要工作。';
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
  if (n >= 1000000) return 'RM ' + (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return 'RM ' + (n / 1000).toFixed(1) + 'k';
  return 'RM ' + n.toFixed(2);
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
  var tickColor = isDark ? '#94a3b8' : '#6f6c66';
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
          label: '收入',
          data: incomeVals,
          backgroundColor: incomeColor,
          hoverBackgroundColor: incomeHover,
          borderRadius: 4,
          borderSkipped: 'bottom'
        },
        {
          label: '支出',
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
              return ' ' + item.dataset.label + ': ' + formatMoney(item.raw);
            },
            afterBody: function (items) {
              var idx = items[0].dataIndex;
              var bal = balanceVals[idx];
              return ['结余: ' + formatMoney(bal)];
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
            callback: function (value) { return rmMoneyFmt(value); }
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

  var instance = new Chart(canvas.getContext('2d'), {
    type: 'doughnut',
    data: {
      labels: labels,
      datasets: [{
        data: values,
        backgroundColor: palette.slice(0, labels.length),
        hoverBackgroundColor: palette.slice(0, labels.length),
        borderWidth: 2,
        borderColor: sliceBorder,
        hoverBorderColor: sliceBorder
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '65%',
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
          padding: 10,
          titleFont: { family: 'Segoe UI, sans-serif', size: 12, weight: '600' },
          bodyFont: { family: 'ui-monospace, SFMono-Regular, Consolas, monospace', size: 11 },
          callbacks: {
            label: function (item) {
              var val = item.raw || 0;
              var pct = total > 0 ? (val / total * 100).toFixed(1) : '0.0';
              return ' ' + formatMoney(val) + ' (' + pct + '%)';
            }
          }
        }
      }
    }
  });

  var legendContainer = document.getElementById(canvasId + 'Legend');
  if (legendContainer) {
    if (!total || values.length === 0) {
      legendContainer.innerHTML = '<div style="text-align:center; color:var(--muted); font-size:12px; padding:8px;">暂无数据</div>';
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

function drawMonthlyDoughnut(canvasId, labels, values) {
  var canvas = document.getElementById(canvasId);
  if (!canvas) return;

  if (canvasId === 'incomeChart') {
    _monthlyIncomeChart = destroyChart(_monthlyIncomeChart);
  } else {
    _monthlyExpenseChart = destroyChart(_monthlyExpenseChart);
  }

  var total = values.reduce(function (a, b) { return a + (b || 0); }, 0);
  var isIncome = (canvasId === 'incomeChart');
  var centerTitle = isIncome ? '本月总收入' : '本月总支出';
  var isDark = isDarkModeActive();
  var palette = getChartPalette();
  var sliceBorder = isDark ? '#131d31' : '#ffffff';
  var centerTitleColor = isDark ? '#94a3b8' : '#6f6c66';
  var centerAmountColor = isDark ? '#ffffff' : '#10213b';
  var tooltipBg = isDark ? 'rgba(19, 29, 49, 0.96)' : 'rgba(255, 255, 255, 0.97)';
  var tooltipTitle = isDark ? '#f8fafc' : '#10213b';
  var tooltipBody = isDark ? '#cbd5e1' : '#46453f';
  var tooltipBorder = isDark ? 'rgba(255, 255, 255, 0.15)' : 'rgba(27, 27, 31, 0.1)';

  // 自定义中心文字插件 (高对比度暗色适配)
  var centerTextPlugin = {
    id: 'centerText_' + canvasId,
    beforeDraw: function(chart) {
      var width = chart.width;
      var height = chart.height;
      var ctx = chart.ctx;
      ctx.save();
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      
      var centerX = (chart.chartArea.left + chart.chartArea.right) / 2;
      var centerY = (chart.chartArea.top + chart.chartArea.bottom) / 2;

      // 标题 (本月总收入 / 本月总支出)
      ctx.font = '500 12px "Segoe UI", sans-serif';
      ctx.fillStyle = centerTitleColor;
      ctx.fillText(centerTitle, centerX, centerY - 10);

      // 金额
      ctx.font = '700 15.5px ui-monospace, SFMono-Regular, Consolas, monospace';
      ctx.fillStyle = centerAmountColor;
      ctx.fillText(formatMoney(total), centerX, centerY + 10);
      ctx.restore();
    }
  };

  var instance = new Chart(canvas.getContext('2d'), {
    type: 'doughnut',
    data: {
      labels: labels,
      datasets: [{
        data: values.length > 0 && total > 0 ? values : [1],
        backgroundColor: values.length > 0 && total > 0 ? palette.slice(0, labels.length) : (isDark ? ['#334155'] : ['#e0e0e0']),
        borderWidth: 2,
        borderColor: sliceBorder,
        hoverBorderColor: sliceBorder
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '70%',
      layout: {
        padding: { top: 10, bottom: 10 }
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
      legendContainer.innerHTML = '<div style="text-align:center; color:var(--muted); font-size:12px; padding:8px;">暂无数据</div>';
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

  if (canvasId === 'incomeChart') {
    _monthlyIncomeChart = instance;
  } else {
    _monthlyExpenseChart = instance;
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

// 监听系统/浏览器浅色与暗色模式切换，自动重新渲染图表与高对比度调色盘
if (window.matchMedia) {
  try {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
      if (window.CHART_DATA && document.getElementById('incomeChart') && (!document.getElementById('monthlySection') || document.getElementById('monthlySection').style.display !== 'none')) {
        drawMonthlyDoughnut('incomeChart', window.CHART_DATA.income.labels, window.CHART_DATA.income.values);
        drawMonthlyDoughnut('expenseChart', window.CHART_DATA.expense.labels, window.CHART_DATA.expense.values);
      }
      if (currentOverviewData && document.getElementById('overviewSection') && document.getElementById('overviewSection').style.display !== 'none') {
        renderOverview(currentOverviewData);
      }
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
      b.title = '网络良好，已连接至云端服务';
    });
    dots.forEach(d => {
      d.classList.remove('dot-offline', 'dot-unreachable');
    });
    texts.forEach(t => {
      t.textContent = '云端在线';
    });
  } else if (state === 'offline') {
    badges.forEach(b => {
      b.classList.remove('badge-unreachable');
      b.classList.add('badge-offline');
      b.title = '当前设备处于离线状态，无法连接互联网';
    });
    dots.forEach(d => {
      d.classList.remove('dot-unreachable');
      d.classList.add('dot-offline');
    });
    texts.forEach(t => {
      t.textContent = '网络离线';
    });
  } else if (state === 'unreachable') {
    badges.forEach(b => {
      b.classList.remove('badge-offline');
      b.classList.add('badge-unreachable');
      b.title = '无法连接到云端记账服务器';
    });
    dots.forEach(d => {
      d.classList.remove('dot-offline');
      d.classList.add('dot-unreachable');
    });
    texts.forEach(t => {
      t.textContent = '连接中断';
    });
  }
}

let _currentDataVersion = null;
let _isPollingActive = false;

function flashSyncBadgeUpdated() {
  const badges = [document.getElementById('syncStatusBadge'), document.getElementById('desktopSyncStatusBadge')].filter(Boolean);
  const texts = [document.getElementById('syncStatusText'), document.getElementById('desktopSyncStatusText')].filter(Boolean);

  badges.forEach(b => {
    b.classList.add('live-active', 'live-pulse');
  });
  texts.forEach(t => {
    t.textContent = '实时已更新';
  });

  setTimeout(() => {
    badges.forEach(b => {
      b.classList.remove('live-pulse');
    });
    texts.forEach(t => {
      t.textContent = '云端在线';
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
        }, 2500);
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
      if (window.CHART_DATA) {
        window.CHART_DATA.income = data.income;
        window.CHART_DATA.expense = data.expense;
      }
      if (typeof renderDonutCharts === 'function') {
        renderDonutCharts();
      }
    })
    .catch(console.error);

  // 3. 如果在总体概览 Tab，重新拉取概览数据
  const ovSec = document.getElementById('overviewSection');
  if (ovSec && ovSec.style.display !== 'none' && typeof loadOverviewStats === 'function') {
    loadOverviewStats();
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

      // 如果有新添加的交易 ID，添加脉冲动画
      if (event && event.data && event.data.id) {
        const row = document.getElementById('row-' + event.data.id);
        if (row) {
          row.classList.add('row-highlight-new');
          setTimeout(() => row.classList.remove('row-highlight-new'), 3500);
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

  // 显示优雅的非侵入式 Toast 提示
  if (event && event.data) {
    const tx = event.data;
    const amountStr = tx.amount ? ' RM ' + Number(tx.amount).toFixed(2) : '';
    const noteStr = tx.note ? `【${tx.note}】` : '';
    const title = event.type === 'auto_track' 
      ? `🎉 自动记账实时入账：${noteStr} ${amountStr}`
      : `⚡ 账本数据已实时同步：${noteStr} ${amountStr}`;

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
  else if (path.startsWith('/recurring')) navKey = 'recurring';
  else if (path.startsWith('/categories/insights')) navKey = 'insights';
  else if (path.startsWith('/categories')) navKey = 'categories';
  else if (path.startsWith('/import')) navKey = 'import';

  // 同步桌面端导航高亮
  document.querySelectorAll('.desktop-nav-links a').forEach(a => {
    a.classList.toggle('active', a.dataset.nav === navKey);
  });

  // 同步手机端底部导航高亮
  document.querySelectorAll('.mobile-bottom-nav .bnav-item').forEach(btn => {
    if (btn.dataset.nav) {
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

  if (window.CHART_DATA && document.getElementById('incomeChart')) {
    drawMonthlyDoughnut('incomeChart', window.CHART_DATA.income.labels, window.CHART_DATA.income.values);
    drawMonthlyDoughnut('expenseChart', window.CHART_DATA.expense.labels, window.CHART_DATA.expense.values);
  }

  // 历史记录表格移动端快速勾选绑定
  const tbody = document.querySelector('table tbody');
  if (tbody && !tbody.dataset.spaBound) {
    tbody.dataset.spaBound = 'true';
    tbody.addEventListener('click', function (e) {
      if (e.target.closest('.actions') || e.target.tagName === 'A' || e.target.tagName === 'BUTTON' || e.target.type === 'checkbox') {
        return;
      }
      const row = e.target.closest('tr.record-row');
      if (!row) return;
      const checkbox = row.querySelector('.record-checkbox');
      if (checkbox) {
        checkbox.checked = !checkbox.checked;
        if (typeof updateBatchBar === 'function') updateBatchBar();
      }
    });
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

const InstantNav = {
  showProgress: showProgressBar,
  finishProgress: finishProgressBar,
  updateActiveNav: updateActiveNav,
  navigate(url) {
    const container = document.getElementById('mainContainer');
    if (container) {
      container.innerHTML = renderSkeletonScreen(url);
    }
    if (typeof htmx !== 'undefined') {
      htmx.ajax('GET', url, { target: '#mainContainer', swap: 'innerHTML show:window:top' });
      history.pushState({}, '', url);
      updateActiveNav(url);
    } else {
      window.location.href = url;
    }
  }
};

document.addEventListener('DOMContentLoaded', function () {
  initPageLifecycle();

  // HTMX 事件监听器：连接顶部加载条、骨架屏与生命周期重新水合
  document.body.addEventListener('htmx:beforeRequest', function (evt) {
    showProgressBar();
    if (typeof toggleMoreSheet === 'function') {
      toggleMoreSheet(false);
    }

    // 换页时清空滞留的全局 Toast 消息变量，防止换页误触重复弹窗
    window.FLASH_SUCCESS = null;
    window.FLASH_ERROR = null;

    // 页面级导航时立即展示优雅的微光骨架屏，杜绝空白或卡顿等待
    const target = evt.detail.target;
    const elt = evt.detail.elt;
    if (target && target.id === 'mainContainer') {
      const isNav = elt && (
        elt.closest('.desktop-nav-links') || 
        elt.closest('.mobile-bottom-nav') || 
        elt.closest('.mobile-bottom-sheet') || 
        elt.tagName === 'A'
      );
      if (isNav) {
        const targetUrl = (evt.detail.requestConfig && evt.detail.requestConfig.path) || (elt && elt.getAttribute('href')) || '';
        target.innerHTML = renderSkeletonScreen(targetUrl);
        window.scrollTo({ top: 0, behavior: 'instant' });
      }
    }
  });

  document.body.addEventListener('htmx:afterRequest', function () {
    finishProgressBar();
  });

  document.body.addEventListener('htmx:afterSwap', function (evt) {
    const container = evt.detail.target;
    if (container && container.id === 'mainContainer') {
      // 执行内联数据脚本，确保 window.CATEGORY_DATA 等变量生效
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

// 检查并更新 PWA 安装入口与浮动横幅状态
function checkPwaUi() {
  const isStandalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
  const sheetBtn = document.getElementById('pwaSheetInstallBtn');
  const banner = document.getElementById('pwaInstallBanner');

  if (isStandalone) {
    if (sheetBtn) sheetBtn.style.display = 'none';
    if (banner) banner.style.display = 'none';
    return;
  }

  // 非独立 App 模式下，在“更多”面板中始终展示安装入口
  if (sheetBtn) {
    sheetBtn.style.display = 'flex';
  }

  // 手机端且当前会话尚未关闭过提示条，延迟 1 秒平滑滑出
  if (banner && !sessionStorage.getItem('pwa_banner_closed')) {
    setTimeout(() => {
      const stillNotStandalone = !(window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true);
      if (stillNotStandalone && !sessionStorage.getItem('pwa_banner_closed')) {
        banner.style.display = 'flex';
      }
    }, 1000);
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
  const banner = document.getElementById('pwaInstallBanner');
  if (banner) banner.style.display = 'none';
  const sheetBtn = document.getElementById('pwaSheetInstallBtn');
  if (sheetBtn) sheetBtn.style.display = 'none';
  if (typeof Swal !== 'undefined') {
    Swal.fire({
      toast: true,
      position: 'top',
      icon: 'success',
      title: '已成功添加到主屏幕！',
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
        const banner = document.getElementById('pwaInstallBanner');
        if (banner) banner.style.display = 'none';
      }
      deferredPwaPrompt = null;
    });
  } else {
    // 检测是否为 iOS Safari
    const isIos = /iphone|ipad|ipod/.test(window.navigator.userAgent.toLowerCase());
    if (isIos && typeof Swal !== 'undefined') {
      Swal.fire({
        title: '📲 添加到手机主屏幕',
        html: '<div style="text-align: left; font-size: 14px; line-height: 1.8; color: var(--ink-soft);">' +
              '1. 点击 Safari 底部中间的 <b>分享按钮</b> <span style="font-size: 18px;">📤</span><br>' +
              '2. 向上滑动菜单找到并点击 <b>「添加到主屏幕」</b> <span style="font-size: 18px;">➕</span><br>' +
              '3. 点击右上角「添加」，即可像原生 App 一样全屏使用！</div>',
        icon: 'info',
        confirmButtonText: '我知道了'
      });
    } else if (typeof Swal !== 'undefined') {
      Swal.fire({
        title: '📲 安装为手机应用',
        html: '<div style="text-align: left; font-size: 14px; line-height: 1.8; color: var(--ink-soft);">' +
              '1. 点击浏览器右上角或底部的 <b>菜单按钮</b>（通常是三个点 <b>⋮</b> 或图标）<br>' +
              '2. 在弹出的菜单列表中选择 <b>「安装应用」</b> 或 <b>「添加到主屏幕」</b> ➕<br>' +
              '3. 确认后手机桌面即会生成独立 App 图标，无需再开浏览器！</div>',
        icon: 'info',
        confirmButtonText: '我知道了'
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
  sessionStorage.setItem('pwa_banner_closed', 'true');
};

// 页面加载及 HTMX 切换后主动检查 PWA 入口
window.addEventListener('load', checkPwaUi);
document.addEventListener('DOMContentLoaded', checkPwaUi);
document.body.addEventListener('htmx:afterSwap', checkPwaUi);




