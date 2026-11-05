# Third-party files in `codeview/`

The TextMate grammars and themes that `core:codeview` ships. Every directory holds the
license of its files as `LICENSE`. Files that VS Code converted to JSON also carry VS
Code's own MIT license as `LICENSE.vscode`. Only MIT, Apache-2.0 or BSD files are allowed
here.

Most files come from the VS Code repository, tag `1.145.0`, commit
`a717861c07586a53d83b80d742cd3f59b20a6273` (https://github.com/microsoft/vscode, MIT).
Each grammar names its own upstream source and commit in its
`information_for_contributors` field. The `LICENSE` files were taken from the upstream
repositories at those commits. The grammar and `language-configuration.json` files are
unchanged except for the fixes listed under Changes.

| Directory | Files | Taken from | Upstream grammar, version | License |
|---|---|---|---|---|
| `grammars/kotlin/` | `kotlin.tmLanguage.json`, `language-configuration.json` | https://github.com/fwcd/vscode-kotlin, commit `4a7c1538754828c1d22a8bee8ff3400045b4352a`, `syntaxes/` and the root | the same | MIT (George Fraser) |
| `grammars/java/` | `java.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/java/` | https://github.com/redhat-developer/vscode-java `language-support/java/java.tmLanguage.json`, commit `f09b712f5d6d6339e765f58c8dfab3f78a378183`; derived from https://github.com/atom/language-java (`LICENSE` from commit `047fd33bd12f4926bc5dbc0c930cd9d5fa280602`) | MIT (GitHub Inc.), with the TextMate java bundle notice it keeps |
| `grammars/javascript/` | `JavaScript.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/javascript/` | https://github.com/microsoft/TypeScript-TmLanguage, commit `48f608692aa6d6ad7bd65b478187906c798234a8` | MIT (Microsoft) |
| `grammars/typescript/` | `TypeScript.tmLanguage.json`, `TypeScriptReact.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/typescript-basics/` | https://github.com/microsoft/TypeScript-TmLanguage, commit `48f608692aa6d6ad7bd65b478187906c798234a8` | MIT (Microsoft) |
| `grammars/python/` | `MagicPython.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/python/` | https://github.com/MagicStack/MagicPython, commit `7d0f2b22a5ad8fccbd7341bc7b7a715169283044` | MIT (MagicStack Inc.) |
| `grammars/go/` | `go.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/go/` | https://github.com/worlpaker/go-syntax 0.8.6, commit `c74e22eb9ef32958e3edd130ea750ce78d8b8241` | MIT (Furkan Ozalp) |
| `grammars/rust/` | `rust.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/rust/` | https://github.com/dustypomerleau/rust-syntax 0.6.1, commit `ca34cf382a7b250144f2ccb99ff9af96912c79f2` | MIT (Dustin Pomerleau) |
| `grammars/c/` | `c.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/cpp/` | https://github.com/jeff-hykin/better-c-syntax 1.13.2, commit `34712a6106a4ffb0a04d2fa836fd28ff6c5849a4` | MIT (Jeff Hykin) |
| `grammars/shell/` | `shell-unix-bash.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/shellscript/` | https://github.com/jeff-hykin/better-shell-syntax 1.8.7, commit `35020b0bd79a90d3b262b4c13a8bb0b33adc1f45` | MIT (Jeff Hykin) |
| `grammars/json/` | `JSONC.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/json/` | https://github.com/microsoft/vscode-JSON.tmLanguage, commit `9bd83f1c252b375e957203f21793316203f61f70` | MIT (simonzack, zertosh, benvie) |
| `grammars/yaml/` | `yaml.tmLanguage.json`, `yaml-1.0` to `yaml-1.3`, `yaml-embedded.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/yaml/` | https://github.com/RedCMD/YAML-Syntax-Highlighter 1.3.2, commit `53d38bbc66b704803de54ffce5b251bf97211c60` | MIT (RedCMD) |
| `grammars/toml/` | `toml.tmLanguage.json`, `language-configuration.json`, `LICENSE` | Even Better TOML 0.21.2 (`tamasfe.even-better-toml-0.21.2.vsix` from https://open-vsx.org, SHA-256 `f3dc44f1c551edef397a79f0f8c5db4066c6a547e210b5c3cec4400bf173b008`), built from https://github.com/tamasfe/taplo `editors/vscode`, tag `release-even-better-toml-0.21.2`, commit `83deb8deb66aaee0a1a40009c391caadf2f6e564` | the same; the grammar is generated at build time and not committed upstream | MIT (Ferenc Tamás) |
| `grammars/markdown/` | `markdown.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/markdown-basics/` | https://github.com/microsoft/vscode-markdown-tm-grammar, commit `0812fc4b190efc17bfed0d5b4ff918eff8e4e377` | MIT (Microsoft) |
| `grammars/css/` | `css.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/css/` | https://github.com/microsoft/vscode-css, commit `de9e6beee756760f31b15efbd782735fc25de3db` | MIT (Microsoft), with the TextMate css bundle notice it keeps |
| `grammars/xml/` | `xml.tmLanguage.json`, `language-configuration.json` (VS Code's `xml.language-configuration.json`) | VS Code `extensions/xml/` | https://github.com/atom/language-xml 0.35.2, commit `7bc75dfe779ad5b35d9bf4013d9181864358cb49` | MIT (GitHub Inc.), with the TextMate xml bundle notice it keeps |
| `grammars/sql/` | `sql.tmLanguage.json`, `language-configuration.json` | VS Code `extensions/sql/` | https://github.com/microsoft/vscode-mssql, commit `684648ee5414eb2d9df22b0392ef05079f91e44e` | MIT (Microsoft) |
| `themes/` | `dark.json`, `light.json` | VS Code `extensions/theme-defaults/themes/` | `dark_vs.json` + `dark_plus.json`, `light_vs.json` + `light_plus.json` | MIT (Microsoft) |

## Changes

- `themes/dark.json` and `themes/light.json` are each merged from two VS Code files, because
  the TextMate theme reader does not follow `include`. Their `tokenColors` are joined, base
  file first, under the TextMate key `settings`, after a default entry with the editor
  foreground and background. Only the `editor*` colors are kept. Comments and trailing commas
  were removed. Line number, cursor, selection, current line, whitespace and indent guide
  colors, which the files leave to VS Code's built-in defaults, were added from those
  defaults. Each file says so in `information_for_contributors`.
- `grammars/xml/xml.tmLanguage.json`: in the `<%--` comment rule, upstream puts `end` and
  `name` inside `captures`; they were moved next to `captures`, where VS Code's reader takes
  them anyway. tm4e rejects a capture that is not an object.
- `grammars/yaml/yaml-1.1.tmLanguage.json`, `yaml-1.2.tmLanguage.json` and
  `yaml-embedded.tmLanguage.json`: a `comment` string inside one `beginCaptures` was removed,
  for the same reason. It held only a link to the YAML specification.
- Files were renamed only where noted: VS Code's `javascript-language-configuration.json` and
  `xml.language-configuration.json` are `language-configuration.json` here.

## Left out

- HTML: VS Code's `html.tmLanguage.json` comes from https://github.com/textmate/html.tmbundle
  under the TextMate bundle license only, which is not MIT, Apache-2.0 or BSD. HTML files are
  highlighted with the XML grammar.
- C++: VS Code records https://github.com/jeff-hykin/better-cpp-syntax as MIT, but the
  `LICENSE` file of that repository at the recorded commit
  `071dd6ecc9eda347bd84c8aa0e0b557396cb6a40` is GPL-3.0. C++ files are highlighted with the C
  grammar.
- Grammars embedded by Markdown code fences are not loaded with Markdown, so fenced code shows
  in one color.
