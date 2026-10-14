# vendor

GitHub Pagesで公開するページ（`mcp-server-spec.html`）が読み込む、第三者のライブラリ。外部の配信元（CDN）に依存しないよう、リポジトリに置いている。

| ファイル | 内容 |
|---|---|
| `js-yaml.min.js` | [js-yaml](https://github.com/nodeca/js-yaml) 4.1.0（MIT。ライセンスは`js-yaml.LICENSE`）。npmの`js-yaml@4.1.0`の`dist/js-yaml.min.js`そのもの |

Dependabotも CodeQL もこのディレクトリを見ないため、更新は手で行う。

* 更新するときは、`npm view js-yaml dist.integrity`（または`https://registry.npmjs.org/js-yaml/<版>`の`dist.integrity`）と、
  取得したtarballのsha512が一致することを確かめてから、`dist/js-yaml.min.js`と`LICENSE`を置き換える。版はこのファイルの表にも書く。
* 脆弱性が公表されたときは、[GitHub Advisory Database](https://github.com/advisories?query=js-yaml)で`js-yaml`を確認する。
