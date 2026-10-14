// mcp-server-spec.html の描画。同じ場所の mcp-server-spec.yaml を読み込み、ツールごとの仕様を表示する。
// YAMLの値は textContent / テキストノードとしてだけ入れる（innerHTML は使わない）。
// 公開するYAMLはタグの時点のもので、このスクリプトはワークフローと同じ版のため、省略できる項目は無くても描画できるようにしておく。
(() => {
  'use strict';

  const SPEC_URL = './mcp-server-spec.yaml';

  /** 子の文字列はテキストとして入れる。配列は入れ子のままでよく、null・undefined・false は無視する */
  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'class') node.className = value;
      else node.setAttribute(key, value);
    }
    for (const child of children.flat(Infinity)) {
      if (child === undefined || child === null || child === false) continue;
      node.append(child);
    }
    return node;
  }

  const code = (text) => el('code', null, String(text));

  /** 検索用に、全角半角・大文字小文字をそろえる */
  const normalize = (text) => String(text).normalize('NFKC').toLowerCase();

  /** ref で共通の引数を使っているものを、その定義に置き換える */
  function parametersOf(tool, common) {
    return (tool.parameters || []).map((parameter) => {
      if (!parameter.ref) return parameter;
      if (!common[parameter.ref]) {
        throw new Error(`${tool.name} が参照する共通の引数 ${parameter.ref} が、commonParameters に定義されていません`);
      }
      return common[parameter.ref];
    });
  }

  function rangeText(parameter) {
    const hasMin = parameter.min !== undefined;
    const hasMax = parameter.max !== undefined;
    if (hasMin && hasMax) return `${parameter.min}〜${parameter.max}`;
    if (hasMin) return `${parameter.min}以上`;
    if (hasMax) return `${parameter.max}以下`;
    return null;
  }

  function parameterRow(parameter) {
    const range = rangeText(parameter);
    return el('tr', null,
      el('th', { scope: 'row' }, code(parameter.name), parameter.required && el('span', { class: 'req' }, '必須')),
      el('td', null,
        code(parameter.type),
        range && el('span', { class: 'range' }, range),
        parameter.enum && el('div', { class: 'chips' }, parameter.enum.map((value) => code(value)))),
      el('td', { class: 'default' }, parameter.default === undefined ? '' : code(parameter.default)),
      el('td', null, parameter.description));
  }

  function parametersTable(parameters) {
    if (parameters.length === 0) return el('p', { class: 'muted' }, '引数はありません。');
    return el('div', { class: 'table-wrap' },
      el('table', null,
        el('thead', null, el('tr', null,
          el('th', { scope: 'col' }, '引数'),
          el('th', { scope: 'col' }, '型・値'),
          el('th', { scope: 'col' }, '既定'),
          el('th', { scope: 'col' }, '説明'))),
        el('tbody', null, parameters.map(parameterRow))));
  }

  function resultBlock(result) {
    if (!result) return [];
    const fields = Object.entries(result.fields || {});
    return [
      el('h3', null, '結果'),
      el('p', null, result.summary),
      fields.length > 0 && el('dl', { class: 'fields' },
        fields.map(([name, description]) => [el('dt', null, code(name)), el('dd', null, description)])),
    ];
  }

  function exampleBlocks(examples) {
    if (!examples || examples.length === 0) return [];
    return [
      el('h3', null, '例'),
      examples.map((example) => [
        el('p', { class: 'muted' }, example.description),
        el('pre', null, code(String(example.json).replace(/\n$/, ''))),
      ]),
    ];
  }

  function toolSection(tool, parameters) {
    return el('section', { class: 'tool', id: tool.name },
      el('h2', null,
        code(tool.name),
        el('span', { class: 'title' }, tool.title),
        el('a', { class: 'anchor', href: `#${tool.name}`, 'aria-label': `${tool.name} へのリンク` }, '#')),
      el('p', { class: 'summary' }, tool.summary),
      el('h3', null, '引数'),
      parametersTable(parameters),
      resultBlock(tool.result),
      tool.notes && tool.notes.length > 0 && [
        el('h3', null, '注意点'),
        el('ul', { class: 'notes' }, tool.notes.map((note) => el('li', null, note))),
      ],
      exampleBlocks(tool.examples));
  }

  function conventionsPanel(spec) {
    return el('section', { class: 'panel' },
      el('h2', null, '共通のルール'),
      el('ul', null, (spec.conventions || []).map((rule) => el('li', null, rule))),
      spec.compatibility && el('p', { class: 'muted' }, spec.compatibility));
  }

  /** 絞り込みの入力に合わせて、一覧と本文を出し入れする */
  function setUpFilter(entries, app) {
    const input = document.getElementById('filter');
    const empty = el('p', { class: 'muted', hidden: '' }, '該当するツールがありません。');
    app.append(empty);
    input.addEventListener('input', () => {
      const words = normalize(input.value).split(/\s+/).filter(Boolean);
      let shown = 0;
      for (const entry of entries) {
        const matches = words.every((word) => entry.haystack.includes(word));
        entry.link.hidden = !matches;
        entry.section.hidden = !matches;
        if (matches) shown++;
      }
      empty.hidden = shown > 0;
    });
  }

  function render(spec) {
    const common = spec.commonParameters || {};
    const tools = spec.tools || [];
    const app = document.getElementById('app');
    const toc = document.getElementById('toc');
    const entries = tools.map((tool) => {
      const parameters = parametersOf(tool, common);
      const haystack = normalize([
        tool.name, tool.title, tool.summary,
        parameters.map((parameter) => `${parameter.name} ${parameter.description}`).join(' '),
      ].join(' '));
      return {
        haystack,
        link: el('a', { href: `#${tool.name}` }, code(tool.name), el('span', { class: 'title' }, tool.title)),
        section: toolSection(tool, parameters),
      };
    });
    app.replaceChildren(conventionsPanel(spec), ...entries.map((entry) => entry.section));
    toc.replaceChildren(...entries.map((entry) => entry.link));
    setUpFilter(entries, app);
    if (location.hash.length > 1) {
      const target = document.getElementById(decodeURIComponent(location.hash.slice(1)));
      // 読み込み直後のジャンプは、CSSの scroll-behavior: smooth に任せず即時に行う
      if (target) target.scrollIntoView({ behavior: 'instant' });
    }
  }

  function showError(error) {
    document.getElementById('app').replaceChildren(
      el('div', { class: 'error' },
        el('p', null, '仕様を表示できませんでした。'),
        el('p', null, `原因: ${error.message}`),
        el('p', null, 'ファイルを直接開いている場合は、HTTPサーバー経由で開いてください。',
          el('a', { href: SPEC_URL }, '仕様のYAML'), 'は直接読めます。')));
  }

  async function main() {
    const response = await fetch(SPEC_URL, { cache: 'no-cache' });
    if (!response.ok) throw new Error(`${SPEC_URL} を取得できません（HTTP ${response.status}）`);
    const spec = window.jsyaml.load(await response.text());
    if (!spec || !Array.isArray(spec.tools)) throw new Error('YAMLに tools の一覧がありません');
    render(spec);
  }

  main().catch(showError);
})();
