/* DOM construction deliberately uses textContent for all user-controlled data. */
(() => {
  const tr = key => window.I18N?.['cashflow.' + key] || key;
  const el = (tag, text, cls) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; };
  const fmt = (n, c) => c + ' ' + Number(n).toLocaleString(document.documentElement.lang || 'en', {minimumFractionDigits: 2, maximumFractionDigits: 2});
  const pct = n => n == null ? '—' : Number(n).toFixed(1) + '%';
  const csrf = () => document.querySelector('meta[name="csrf-token"]')?.content || document.querySelector('input[name="csrf_token"]')?.value || '';
  async function mount(panel) {
    if (panel.dataset.mounted) return;
    panel.dataset.mounted = '1';
    const content = panel.querySelector('.cashflow-content');
    const params = new URLSearchParams({month: panel.dataset.month});
    if (panel.dataset.currency) params.set('currency', panel.dataset.currency);
    try {
      let data;
      const initial = panel.querySelector('[data-cashflow-initial]');
      if (initial) {
        data = JSON.parse(initial.textContent); initial.remove();
      } else {
        const response = await fetch('/api/analytics/cashflow?' + params, {credentials: 'same-origin', cache: 'no-store'});
        data = await response.json();
        if (!response.ok || !data.ok) throw new Error(data.message || tr('error'));
      }
      if (!panel.isConnected) return;
      content.replaceChildren();
      const stats = el('div', null, 'cashflow-stats');
      for (const key of ['income', 'expense', 'net', 'reserved', 'available']) {
        const cell = el('div', tr(key)); cell.append(el('strong', fmt(data[key], data.currency))); stats.append(cell);
      }
      content.append(stats, el('h4', tr('usage')));
      if (data.income > 0) {
        // Normalize the visual track on deficit; the numeric labels keep actual income ratios.
        const denominator = Math.max(data.income, Math.max(0, data.expense) + data.reserved);
        const bar = el('div', null, 'cashflow-bar'); bar.setAttribute('aria-hidden', 'true');
        for (const [key, amount] of [['spent', Math.max(0, data.expense)], ['reserved', data.reserved], ['available', Math.max(0, data.available)]]) {
          const part = el('span', null, key); part.style.width = (amount / denominator * 100) + '%'; bar.append(part);
        }
        content.append(bar, el('p', `${tr('expense')} ${pct(data.expense_percent)} · ${tr('reserved')} ${pct(data.reserve_percent)} · ${tr('available')} ${pct(data.available_percent)}`));
      } else content.append(el('p', tr('no_ratio')));
      if (data.deficit > 0) content.append(el('p', tr('deficit') + ': ' + fmt(data.deficit, data.currency), 'cashflow-warning'));
      content.append(el('h4', tr('fixed')), el('p', `${tr('planned')}: ${fmt(data.planned, data.currency)} · ${tr('paid')}: ${fmt(data.paid, data.currency)} · ${tr('pending')}: ${fmt(data.reserved, data.currency)} · ${pct(data.payment_percent)}`));
      const progress = el('progress'); progress.max = 100; progress.value = data.payment_percent || 0; progress.setAttribute('aria-label', tr('fixed')); content.append(progress);
      content.append(el('p', tr('disclaimer'), 'cashflow-muted'));
      if (data.schedule_estimated) content.append(el('p', tr('estimate'), 'cashflow-warning'));
      if (!panel.dataset.detail) return;
      function transactionList(ids) {
        const list = el('ul');
        for (const tx of data.transactions.filter(t => ids.includes(t.id))) list.append(el('li', `#${tx.id} · ${tx.date} · ${tx.note || tx.category || ''} · ${fmt(tx.amount, data.currency)}`));
        return list;
      }
      for (const section of ['income_platforms', 'expense_categories', 'expense_platforms']) {
        content.append(el('h3', tr(section)));
        if (!data[section].length) content.append(el('p', tr('empty')));
        for (const group of data[section]) {
          const details = el('details');
          const label = section === 'expense_categories' ? (window.t?.('category.' + group.label, group.label) || group.label) : group.label;
          details.append(el('summary', `${label || tr('unknown')} — ${fmt(group.amount, data.currency)} · ${tr('total_percent')} ${pct(group.total_percent)} · ${tr('income_percent')} ${pct(group.income_percent)}`), transactionList(group.transaction_ids)); content.append(details);
        }
      }
      async function mutate(payload) {
        const response = await fetch('/api/analytics/cashflow', {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json', 'X-CSRFToken': csrf()}, body: JSON.stringify({month: data.month, ...payload})});
        const result = await response.json();
        if (!response.ok || !result.ok) throw new Error(result.message || tr('error'));
        delete panel.dataset.mounted; await mount(panel);
      }
      function action(parent, label, payload) {
        const button = el('button', label); button.type = 'button';
        button.onclick = async () => { button.disabled = true; try { await mutate(payload()); } catch (error) { const message = el('p', tr('error'), 'cashflow-warning'); message.setAttribute('role', 'alert'); parent.append(message); button.disabled = false; } };
        parent.append(button);
      }
      for (const [label, bills] of [['fixed', data.bills], ['prior', data.prior_overdue]]) {
        content.append(el('h3', tr(label)));
        if (!bills.length) content.append(el('p', tr('empty')));
        for (const bill of bills) {
          const details = el('details');
          details.append(el('summary', `${bill.title} · ${bill.due_date} · ${tr(bill.status)} · ${tr('paid')} ${fmt(bill.paid, data.currency)} / ${fmt(bill.amount, data.currency)}`));
          const select = el('select'); select.setAttribute('aria-label', tr('link'));
          select.append(new Option(tr('link'), ''));
          for (const tx of [...data.transactions, ...data.scheduled_transactions].filter(t => t.type === 'expense' && t.amount !== 0)) select.append(new Option(`#${tx.id} ${tx.date} ${tx.note || tx.category || ''} ${fmt(tx.amount, data.currency)}`, tx.id));
          details.append(select);
          action(details, tr('link'), () => { if (!select.value) throw new Error(tr('link')); return {bill_key: bill.bill_key, transaction_id: Number(select.value)}; });
          const refundSelect = el('select'); refundSelect.setAttribute('aria-label', tr('refund')); refundSelect.append(new Option(tr('refund'), ''));
          for (const tx of data.transactions.filter(t => t.type === 'income' && t.amount > 0)) refundSelect.append(new Option(`#${tx.id} ${tx.date} ${tx.note || ''} ${fmt(tx.amount, data.currency)}`, tx.id));
          details.append(refundSelect);
          action(details, tr('refund'), () => { if (!refundSelect.value) throw new Error(tr('refund')); return {bill_key: bill.bill_key, transaction_id: Number(refundSelect.value), refund: true}; });
          for (const link of data.payment_links.filter(l => l.bill_key === bill.bill_key)) action(details, tr('unlink') + ' #' + link.transaction_id, () => ({bill_key: bill.bill_key, transaction_id: link.transaction_id, unlink: true}));
          action(details, tr('cancel'), () => ({action: 'bill', bill_key: bill.bill_key, cancelled: true}));
          const input = el('input'); input.type = 'number'; input.min = '0'; input.step = '.01'; input.value = bill.amount; input.setAttribute('aria-label', tr('edit_amount')); details.append(input);
          action(details, tr('edit_amount'), () => ({action: 'bill', bill_key: bill.bill_key, amount: input.value}));
          const duplicates = el('select'); duplicates.setAttribute('aria-label', tr('duplicate')); duplicates.append(new Option(tr('duplicate'), ''));
          for (const other of data.bills.filter(b => b.bill_key !== bill.bill_key)) duplicates.append(new Option(other.title + ' · ' + other.due_date, other.bill_key));
          details.append(duplicates);
          action(details, tr('duplicate'), () => { if (!duplicates.value) throw new Error(tr('duplicate')); return {action: 'bill', bill_key: bill.bill_key, duplicate_of: duplicates.value}; });
          content.append(details);
        }
      }
      if (data.inactive_bills.length) {
        content.append(el('h3', tr('inactive')));
        for (const bill of data.inactive_bills) {
          const row = el('div', bill.title + ' · ' + bill.due_date);
          action(row, tr('restore'), () => ({action: 'bill', bill_key: bill.bill_key, cancelled: false})); content.append(row);
        }
      }
      if (data.scheduled_transactions.length) {
        content.append(el('h3', tr('scheduled')));
        for (const tx of data.scheduled_transactions) {
          const row = el('div', `#${tx.id} · ${tx.date} · ${tx.note || tx.category || ''} · ${fmt(tx.amount, data.currency)}`);
          action(row, tr('confirm'), () => ({action: 'confirm', transaction_id: tx.id})); content.append(row);
        }
      }
      for (const tx of data.confirmed_transactions) {
        const row = el('div', `#${tx.id} · ${tx.date} · ${tx.note || ''}`);
        action(row, tr('undo_confirm'), () => ({action: 'confirm', transaction_id: tx.id, undo: true})); content.append(row);
      }
    } catch (error) {
      if (!panel.isConnected) return;
      content.replaceChildren(el('p', tr('error'), 'cashflow-warning'));
      const retry = el('button', tr('refresh')); retry.onclick = () => { delete panel.dataset.mounted; mount(panel); }; content.append(retry);
    }
  }
  const init = () => document.querySelectorAll('[data-cashflow]').forEach(mount);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
  document.addEventListener('htmx:afterSwap', init);
  document.addEventListener('htmx:historyRestore', () => {
    document.querySelectorAll('[data-cashflow]').forEach(panel => { delete panel.dataset.mounted; mount(panel); });
  });
})();
