import lume from "lume/mod.ts";
import codeHighlight from "lume/plugins/code_highlight.ts";
import resolveUrls from "lume/plugins/resolve_urls.ts";
import sitemap from "lume/plugins/sitemap.ts";
import slugifyUrls from "lume/plugins/slugify_urls.ts";

const REPO_BLOB = "https://github.com/tsirysndr/clojure-pds/blob/main";

const sidebar = [
  {
    title: "Getting started",
    items: [
      { url: "/", label: "Introduction" },
      { url: "/get-started/", label: "Get started" },
      { url: "/installation/", label: "Installation" },
      { url: "/configuration/", label: "Configuration" },
      { url: "/development/", label: "Development" },
    ],
  },
  {
    title: "Identity & accounts",
    items: [
      { url: "/identity/", label: "Hosted identities" },
      { url: "/accounts/", label: "Sessions & lifecycle" },
      { url: "/account-security/", label: "Account security" },
      { url: "/oauth/", label: "OAuth" },
      { url: "/identity-cache/", label: "Identity cache" },
    ],
  },
  {
    title: "Key custody",
    items: [
      { url: "/master-key/", label: "Master key" },
      { url: "/key-rotation/", label: "Key rotation" },
      { url: "/plc-recovery-keys/", label: "Recovery keys" },
      { url: "/plc-recovery/", label: "Recovery forks" },
      { url: "/plc-reconciliation/", label: "PLC reconciliation" },
    ],
  },
  {
    title: "Data & federation",
    items: [
      { url: "/migration/", label: "Account migration" },
      { url: "/repo-export/", label: "Repository exports" },
      { url: "/repo-import/", label: "Repository imports" },
      { url: "/proxy/", label: "Service proxy" },
      { url: "/relay/", label: "Relay announcements" },
      { url: "/preferences/", label: "Preferences" },
      { url: "/lexicon-resolution/", label: "Lexicon resolution" },
      { url: "/email/", label: "Email delivery" },
    ],
  },
  {
    title: "Operations",
    items: [
      { url: "/deployment/", label: "Deployment" },
      { url: "/sqlite/", label: "SQLite backend" },
      { url: "/database/", label: "Database pool" },
      { url: "/backup/", label: "Backup & restore" },
      { url: "/s3/", label: "S3 blob storage" },
      { url: "/redis/", label: "Redis rate limits" },
      { url: "/admin/", label: "Admin & invites" },
      { url: "/admin-accounts/", label: "Account recovery" },
      { url: "/moderation/", label: "Moderation" },
    ],
  },
  {
    title: "Status",
    items: [
      { url: "/compatibility/", label: "Compatibility matrix" },
      { url: "/limits/", label: "Current limits" },
      { url: "/roadmap/", label: "Roadmap" },
    ],
  },
];

const flatNav = sidebar.flatMap((section) =>
  section.items.map((item) => ({ ...item, section: section.title }))
);

// Override with SITE_URL=https://docs.example.com deno task build
const site = lume({
  src: ".",
  dest: "_site",
  location: new URL(Deno.env.get("SITE_URL") ?? "http://localhost:3000/"),
});

site.ignore("README.md", "node_modules", "_site");

site.data("sidebar", sidebar);
site.data("repo", "https://github.com/tsirysndr/clojure-pds");

site.add("assets");

site.use(slugifyUrls());
site.use(resolveUrls());
site.use(codeHighlight({
  options: { cssSelector: "pre code[class*='language-']" },
}));
site.use(sitemap());

site.preprocess([".md"], (pages) => {
  for (const page of pages) {
    const content = page.data.content as string;

    const heading = content.match(/^\s*#\s+(.+?)\s*$/m);
    if (heading && !page.data.title) {
      page.data.title = heading[1];
      page.data.content = content.slice(
        content.indexOf(heading[0]) + heading[0].length,
      ).replace(/^\n+/, "");
    }

    if (!page.data.description) {
      const body = page.data.content as string;
      const paragraph = body
        .split(/\n\s*\n/)
        .map((block) => block.trim())
        .find((block) =>
          block.length > 0 &&
          !block.startsWith("#") &&
          !block.startsWith("|") &&
          !block.startsWith("```") &&
          !block.startsWith("- ")
        );
      if (paragraph) {
        page.data.description = stripMarkdown(paragraph).slice(0, 200);
      }
    }

    const index = flatNav.findIndex((item) => item.url === page.data.url);
    if (index !== -1) {
      page.data.navLabel = flatNav[index].label;
      page.data.navSection = flatNav[index].section;
      page.data.prev = index > 0 ? flatNav[index - 1] : undefined;
      page.data.next = index < flatNav.length - 1
        ? flatNav[index + 1]
        : undefined;
    }
  }
});

interface SearchRecord {
  url: string;
  title: string;
  section: string;
  anchor: string;
  text: string;
}

const searchRecords: SearchRecord[] = [];
const CHUNK = 900;

site.process([".html"], (pages) => {
  for (const page of pages) {
    const document = page.document;
    const article = document.querySelector(".doc-body");
    if (!article) continue;

    const headings = [...article.querySelectorAll("h2, h3, h4, h5")]
      .filter((heading) => !inCard(heading));
    const minLevel = headings.length
      ? Math.min(...headings.map((h) => Number(h.tagName.slice(1))))
      : 2;

    const used = new Set<string>();
    const toc: { id: string; text: string; depth: number }[] = [];

    for (const heading of headings) {
      const text = heading.textContent?.trim() ?? "";
      const depth = Number(heading.tagName.slice(1)) - minLevel;
      const id = uniqueSlug(heading.getAttribute("id") || slugify(text), used);
      heading.setAttribute("id", id);
      if (depth === 0) heading.setAttribute("class", "section-head");

      const anchor = document.createElement("a");
      anchor.setAttribute("class", "heading-anchor");
      anchor.setAttribute("href", `#${id}`);
      anchor.setAttribute("aria-label", `Permalink to “${text}”`);
      anchor.textContent = "#";
      heading.appendChild(anchor);

      if (depth < 2) toc.push({ id, text, depth });
    }

    for (const link of article.querySelectorAll("a[href]")) {
      const href = link.getAttribute("href") ?? "";
      if (href === "./" || href === ".") {
        link.setAttribute("href", "/");
        continue;
      }
      if (href.startsWith("../") || href.startsWith("./../")) {
        link.setAttribute("href", `${REPO_BLOB}/${href.replace(/^(\.\/)?\.\.\//, "")}`);
      }
      const resolved = link.getAttribute("href") ?? "";
      if (/^https?:\/\//.test(resolved)) {
        link.setAttribute("target", "_blank");
        link.setAttribute("rel", "noopener noreferrer");
        link.setAttribute("class", "external-link");
      }
    }

    for (const table of article.querySelectorAll("table")) {
      const wrapper = document.createElement("div");
      wrapper.setAttribute("class", "table-wrap");
      table.parentNode?.insertBefore(wrapper, table);
      wrapper.appendChild(table);
    }

    const tocContainer = document.querySelector("#toc-list");
    if (tocContainer) {
      if (toc.length < 2) {
        document.querySelector(".doc-aside")?.remove();
      } else {
        tocContainer.innerHTML = toc
          .map((entry) =>
            `<li class="toc-d${entry.depth}"><a href="#${entry.id}">${
              escapeHtml(entry.text)
            }</a></li>`
          )
          .join("");
      }
    }

    collectSearchRecords(
      page.data.url as string,
      page.data.title as string,
      article,
      [`h${minLevel}`, `h${minLevel + 1}`],
      searchRecords,
    );
  }
});

site.process(async () => {
  const index = await site.getOrCreatePage("/search-index.json");
  index.text = JSON.stringify(searchRecords);
});


// deno-lint-ignore no-explicit-any
function inCard(node: any): boolean {
  for (let el = node.parentElement; el; el = el.parentElement) {
    if (el.classList?.contains("card") || el.classList?.contains("card-grid")) {
      return true;
    }
  }
  return false;
}

function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[`'"“”‘’]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "") || "section";
}

function uniqueSlug(base: string, used: Set<string>): string {
  let slug = base;
  let n = 2;
  while (used.has(slug)) slug = `${base}-${n++}`;
  used.add(slug);
  return slug;
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"]/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
}

function stripMarkdown(text: string): string {
  return text
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/[`*_>]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

function collectSearchRecords(
  url: string,
  title: string,
  // deno-lint-ignore no-explicit-any
  article: any,
  splitTags: string[],
  out: SearchRecord[],
) {
  let section = title;
  let anchor = "";
  let buffer: string[] = [];

  const flush = () => {
    const text = buffer.join(" ").replace(/\s+/g, " ").trim();
    buffer = [];
    if (!text) return;

    for (let at = 0; at < text.length;) {
      let end = Math.min(at + CHUNK, text.length);
      if (end < text.length) {
        const space = text.lastIndexOf(" ", end);
        if (space > at + CHUNK / 2) end = space;
      }
      out.push({ url, title, section, anchor, text: text.slice(at, end).trim() });
      at = end;
    }
  };

  for (const node of article.children) {
    const tag = node.tagName?.toLowerCase();
    if (tag && splitTags.includes(tag)) {
      flush();
      section = node.textContent?.replace(/#$/, "").trim() ?? title;
      anchor = node.getAttribute("id") ?? "";
    } else {
      buffer.push(node.textContent ?? "");
    }
  }
  flush();
}

export default site;
