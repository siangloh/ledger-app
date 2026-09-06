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
  const type = document.querySelector('input[name="type"]:checked').value;
  const groupRow = document.getElementById('groupRow');
  if (groupRow) groupRow.style.display = (type === 'income') ? 'flex' : 'none';
  populateCategories();
  syncSegStyles();
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
  Swal.fire({
    toast: true,
    position: 'top-end',
    icon: 'success',
    title: window.FLASH_SUCCESS.join('　'),
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
  errorAlert(window.FLASH_ERROR.join('<br>'), '操作未完成');
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
        html: form.dataset.confirmText || '此操作不可撤销。',
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
          HTMLFormElement.prototype.submit.call(form);
        }
      });
    });
  });
}

// ---------- SweetAlert2：表单校验 ----------

function attachAmountFormValidation() {
  const form = document.getElementById('quickAddForm');
  if (!form || form.dataset.validateBound) return;
  form.dataset.validateBound = '1';
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
    }
  });
}

function attachCategoryNameValidation() {
  document.querySelectorAll('form.js-validate-category').forEach(function (form) {
    if (form.dataset.validateBound) return;
    form.dataset.validateBound = '1';
    form.addEventListener('submit', function (e) {
      const nameInput = form.querySelector('input[name="name"]');
      if (!nameInput || nameInput.value.trim() === '') {
        e.preventDefault();
        errorAlert('分类名称不能为空，也不能只是空格。', '请检查表单');
      }
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
    const body = new URLSearchParams({
      source: 'nlp', type: v.type, group_name: v.group_name, date: v.date,
      amount: v.amount, category: v.category, note: v.note
    });
    fetch('/transactions/add', { method: 'POST', body: body })
      .then(function () { window.location.reload(); })
      .catch(function () { errorAlert('保存失败，请检查网络或重试。'); });
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
    fetch('/nlp/parse', { method: 'POST', body: new URLSearchParams({ text: text }) })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.ok) {
          errorAlert(data.message, '解析失败');
          return;
        }
        openNlpConfirmDialog(data.parsed, data.warnings);
      })
      .catch(function () {
        errorAlert('无法连接到本地服务，请确认程序仍在运行。', '解析失败');
      });
  });
}

document.addEventListener('DOMContentLoaded', function () {
  populateCategories();
  toggleTypeCol();
  attachDeleteConfirm();
  attachAmountFormValidation();
  attachCategoryNameValidation();
  attachImportFormValidation();
  attachNlpForm();
  showSuccessToasts();
  showErrorAlerts();

  if (window.CHART_DATA) {
    drawDonutChart('incomeChart', window.CHART_DATA.income.labels, window.CHART_DATA.income.values, CHART_PALETTE);
    drawDonutChart('expenseChart', window.CHART_DATA.expense.labels, window.CHART_DATA.expense.values, CHART_PALETTE);
  }
});
