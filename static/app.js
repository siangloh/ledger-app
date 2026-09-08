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
  if (badge) badge.textContent = '数据计算中...';

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

var CHARTJS_INCOME_COLOR = 'rgba(16, 33, 59, 0.88)';
var CHARTJS_INCOME_HOVER = 'rgba(30, 63, 111, 0.95)';
var CHARTJS_EXPENSE_COLOR = 'rgba(168, 71, 90, 0.88)';
var CHARTJS_EXPENSE_HOVER = 'rgba(191, 83, 104, 0.95)';
var CHARTJS_PALETTE = ['#10213b', '#b6902f', '#1e7a5c', '#7a5c8a', '#4d7ea8', '#a0522d', '#5c6b73', '#b04a56'];

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
          backgroundColor: CHARTJS_INCOME_COLOR,
          hoverBackgroundColor: CHARTJS_INCOME_HOVER,
          borderRadius: 4,
          borderSkipped: 'bottom'
        },
        {
          label: '支出',
          data: expenseVals,
          backgroundColor: CHARTJS_EXPENSE_COLOR,
          hoverBackgroundColor: CHARTJS_EXPENSE_HOVER,
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
          backgroundColor: 'rgba(255,255,255,0.97)',
          titleColor: '#10213b',
          bodyColor: '#46453f',
          borderColor: 'rgba(27,27,31,0.1)',
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
            color: '#6f6c66',
            font: { family: 'ui-monospace, SFMono-Regular, Consolas, monospace', size: 11 },
            maxRotation: 45
          }
        },
        y: {
          grid: {
            color: 'rgba(27,27,31,0.06)',
            borderColor: 'rgba(27,27,31,0.18)'
          },
          ticks: {
            color: '#8a877e',
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

  var instance = new Chart(canvas.getContext('2d'), {
    type: 'doughnut',
    data: {
      labels: labels,
      datasets: [{
        data: values,
        backgroundColor: CHARTJS_PALETTE.slice(0, labels.length),
        hoverBackgroundColor: CHARTJS_PALETTE.slice(0, labels.length).map(function (c) { return c; }),
        borderWidth: 2,
        borderColor: '#ffffff',
        hoverBorderColor: '#ffffff'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: true,
      cutout: '58%',
      plugins: {
        legend: {
          position: 'right',
          labels: {
            font: { family: 'Segoe UI, sans-serif', size: 12 },
            color: '#3a3a40',
            padding: 14,
            generateLabels: function (chart) {
              var data = chart.data;
              return data.labels.map(function (lbl, i) {
                var val = data.datasets[0].data[i] || 0;
                var pct = total > 0 ? (val / total * 100).toFixed(1) : '0.0';
                return {
                  text: lbl + '  ' + formatMoney(val) + ' (' + pct + '%)',
                  fillStyle: CHARTJS_PALETTE[i % CHARTJS_PALETTE.length],
                  hidden: false,
                  index: i
                };
              });
            }
          }
        },
        tooltip: {
          backgroundColor: 'rgba(255,255,255,0.97)',
          titleColor: '#10213b',
          bodyColor: '#46453f',
          borderColor: 'rgba(27,27,31,0.1)',
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

  // 自定义中心文字插件
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
      ctx.fillStyle = '#6f6c66';
      ctx.fillText(centerTitle, centerX, centerY - 10);

      // 金额
      ctx.font = '600 15px ui-monospace, SFMono-Regular, Consolas, monospace';
      ctx.fillStyle = '#10213b';
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
        backgroundColor: values.length > 0 && total > 0 ? CHARTJS_PALETTE.slice(0, labels.length) : ['#e0e0e0'],
        borderWidth: 2,
        borderColor: '#ffffff',
        hoverBorderColor: '#ffffff'
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
          position: 'bottom',
          labels: {
            font: { family: 'Segoe UI, sans-serif', size: 12 },
            color: '#3a3a40',
            padding: 16,
            usePointStyle: true,
            pointStyle: 'circle',
            generateLabels: function (chart) {
              if (!total || values.length === 0) {
                return [{ text: '暂无数据', fillStyle: '#e0e0e0', hidden: false, index: 0 }];
              }
              var data = chart.data;
              return data.labels.map(function (lbl, i) {
                var val = values[i] || 0;
                var pct = total > 0 ? (val / total * 100).toFixed(1) : '0.0';
                return {
                  text: lbl + ': ' + formatMoney(val) + ' (' + pct + '%)',
                  fillStyle: CHARTJS_PALETTE[i % CHARTJS_PALETTE.length],
                  strokeStyle: CHARTJS_PALETTE[i % CHARTJS_PALETTE.length],
                  hidden: false,
                  index: i
                };
              });
            }
          }
        },
        tooltip: {
          enabled: total > 0,
          backgroundColor: 'rgba(255,255,255,0.97)',
          titleColor: '#10213b',
          bodyColor: '#46453f',
          borderColor: 'rgba(27,27,31,0.1)',
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
  if (currentOverviewData && document.getElementById('overviewSection').style.display !== 'none') {
    drawTrendBarChart('overviewTrendChart', currentOverviewData.trend || []);
    const expCats = currentOverviewData.expense_categories || { labels: [], values: [] };
    drawDonutChart('overviewExpenseChart', expCats.labels, expCats.values, CHART_PALETTE);
    const incGrp = currentOverviewData.income_group || { main: 0, side: 0 };
    drawDonutChart('overviewIncomeChart', ['主业收入', '副业收入'], [incGrp.main, incGrp.side], CHART_PALETTE);
  }
});

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

// 页面全局生命周期初始化函数
function initPageLifecycle() {
  populateCategories();
  toggleTypeCol();
  attachDeleteConfirm();
  attachAmountFormValidation();
  attachCategoryNameValidation();
  attachImportFormValidation();
  attachNlpForm();
  showSuccessToasts();
  showErrorAlerts();
  syncSegStyles();

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

// ---------- 零延迟即时换页引擎 (InstantNav SPA Engine) ----------

const InstantNav = {
  cache: new Map(),
  isNavigating: false,
  progressTimer: null,

  init() {
    // 1. 拦截站内内部链接点击，走局部无刷新秒开
    document.addEventListener('click', (e) => {
      const link = e.target.closest('a');
      if (!link) return;

      const href = link.getAttribute('href');
      if (!href || href.startsWith('#') || href.startsWith('javascript:') || href.startsWith('mailto:') || href.startsWith('tel:') || link.target === '_blank' || link.hasAttribute('download')) {
        return;
      }

      const url = new URL(href, window.location.origin);
      if (url.origin !== window.location.origin) return;

      e.preventDefault();
      this.navigate(url.href, true);
    });

    // 2. 触控与鼠标悬停即时静默预加载 (Touch / Hover Preload)
    const triggerPreload = (e) => {
      const link = e.target.closest('a');
      if (!link) return;
      const href = link.getAttribute('href');
      if (!href || href.startsWith('#') || href.startsWith('javascript:') || link.target === '_blank' || link.hasAttribute('download')) return;
      const url = new URL(href, window.location.origin);
      if (url.origin === window.location.origin) {
        this.preload(url.href);
      }
    };

    document.addEventListener('touchstart', triggerPreload, { passive: true });
    document.addEventListener('mouseover', triggerPreload, { passive: true });

    // 表单提交后清空页面缓存以保证数据最新
    document.addEventListener('submit', () => {
      this.cache.clear();
    });

    // 3. 浏览器与 Android 硬件返回/前进键无缝支持
    window.addEventListener('popstate', () => {
      this.navigate(window.location.href, false);
    });
  },

  async preload(url) {
    if (this.cache.has(url)) return this.cache.get(url);
    try {
      const promise = fetch(url, { headers: { 'X-Requested-With': 'InstantNav' } })
        .then(res => {
          if (!res.ok) throw new Error('HTTP ' + res.status);
          return res.text();
        });
      this.cache.set(url, promise);
      return promise;
    } catch (err) {
      return null;
    }
  },

  showProgress() {
    const bar = document.getElementById('appProgressBar');
    if (!bar) return;
    bar.classList.add('loading');
    bar.style.width = '35%';
    clearTimeout(this.progressTimer);
    this.progressTimer = setTimeout(() => {
      bar.style.width = '78%';
    }, 120);
  },

  finishProgress() {
    const bar = document.getElementById('appProgressBar');
    if (!bar) return;
    clearTimeout(this.progressTimer);
    bar.style.width = '100%';
    setTimeout(() => {
      bar.classList.remove('loading');
      bar.style.width = '0%';
    }, 240);
  },

  updateActiveNav(urlStr) {
    const url = new URL(urlStr, window.location.origin);
    const path = url.pathname;

    let navKey = 'index';
    if (path === '/') navKey = 'index';
    else if (path.startsWith('/records')) navKey = 'records';
    else if (path.startsWith('/split-bill')) navKey = 'split-bill';
    else if (path.startsWith('/auto-track')) navKey = 'auto-track';
    else if (path.startsWith('/recurring')) navKey = 'recurring';
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
  },

  async navigate(url, pushState = true) {
    if (this.isNavigating) return;
    this.isNavigating = true;

    // 0ms 瞬间反馈：立即高亮目标 Tab 并启动顶端极速进度条
    this.updateActiveNav(url);
    this.showProgress();

    // 关闭打开的“更多”抽屉
    if (typeof toggleMoreSheet === 'function') {
      toggleMoreSheet(false);
    }

    try {
      let htmlPromise = this.cache.get(url);
      if (!htmlPromise) {
        htmlPromise = this.preload(url);
      }
      const htmlText = await htmlPromise;
      if (!htmlText) {
        window.location.href = url;
        return;
      }

      const parser = new DOMParser();
      const doc = parser.parseFromString(htmlText, 'text/html');

      if (doc.title) {
        document.title = doc.title;
      }

      if (pushState && window.location.href !== url) {
        window.history.pushState({ url }, '', url);
      }

      const currentContainer = document.getElementById('mainContainer');
      const newContainer = doc.getElementById('mainContainer');

      if (currentContainer && newContainer) {
        currentContainer.classList.add('page-fade-out');

        setTimeout(() => {
          currentContainer.innerHTML = newContainer.innerHTML;
          currentContainer.classList.remove('page-fade-out');
          currentContainer.classList.add('page-fade-in');
          setTimeout(() => currentContainer.classList.remove('page-fade-in'), 220);

          // 提取并执行新容器内部与页面专属的 script
          newContainer.querySelectorAll('script').forEach(s => {
            const newScript = document.createElement('script');
            Array.from(s.attributes).forEach(attr => newScript.setAttribute(attr.name, attr.value));
            newScript.textContent = s.textContent;
            document.body.appendChild(newScript);
            newScript.remove();
          });

          // 执行可能在 head 或 body 底部的动态数据变量
          doc.querySelectorAll('script').forEach(s => {
            const txt = s.textContent;
            if (txt.includes('window.FLASH_SUCCESS') || txt.includes('window.FLASH_ERROR') || txt.includes('window.CATEGORY_DATA') || txt.includes('window.CHART_DATA')) {
              try {
                eval(txt);
              } catch (e) {
                console.error(e);
              }
            }
          });

          window.scrollTo({ top: 0, behavior: 'instant' });
          initPageLifecycle();

          this.finishProgress();
          this.isNavigating = false;
        }, 60);
      } else {
        window.location.href = url;
      }
    } catch (err) {
      console.error('Instant navigation error:', err);
      this.finishProgress();
      this.isNavigating = false;
      window.location.href = url;
    }
  }
};

document.addEventListener('DOMContentLoaded', function () {
  InstantNav.init();
  initPageLifecycle();
});


