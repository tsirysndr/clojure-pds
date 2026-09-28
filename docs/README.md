# Documentation site

These Markdown files are both the in-repository documentation and the source
of the [Lume](https://lume.land) static site published at
<https://clojure-pds.tsirysndr.deno.net/>. The engine lives alongside
them: `_config.ts` (navigation, table of contents, search index, repository
link resolution), `_includes/layouts/`, and `assets/` (flat violet theme, no
gradients, wordmark logo, client-side search).

```sh
deno task serve    # local preview at http://localhost:3000
deno task build    # writes the site into _site/ (git-ignored)
deno task deploy   # builds and publishes to Deno Deploy (app: clojure-pds)
```

Adding a page: create the Markdown file here, then add its slugified URL to
the `sidebar` in `_config.ts` (uppercase filenames become lowercase URLs, so
`NEW-TOPIC.md` serves at `/new-topic/`). Relative links between documents and
`../` links into the repository resolve automatically. Deployment uses the
`deno deploy` CLI with the `tsirysndr/clojure-pds` app.
