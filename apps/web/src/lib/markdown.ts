import { marked } from "marked";
import DOMPurify from "dompurify";

marked.setOptions({ gfm: true, breaks: true });

// Bot output is untrusted: render Markdown, then sanitize. Links open
// in a new tab without leaking the opener.
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A") {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer nofollow");
  }
});

export function renderMarkdown(src: string): string {
  const html = DOMPurify.sanitize(marked.parse(src, { async: false }) as string, {
    FORBID_TAGS: ["style", "form", "input", "iframe", "img", "button"],
    FORBID_ATTR: ["style"],
  });
  // Code blocks get a copy button. It's added after sanitizing, from a
  // fixed string, so bot text can never inject one.
  return html.replace(/<pre>/g, '<div class="code-block"><button type="button" class="copy-code" aria-label="Copy code">Copy</button><pre>').replace(/<\/pre>/g, "</pre></div>");
}
