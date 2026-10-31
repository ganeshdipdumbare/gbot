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
  return DOMPurify.sanitize(marked.parse(src, { async: false }) as string, {
    FORBID_TAGS: ["style", "form", "input", "iframe", "img"],
    FORBID_ATTR: ["style"],
  });
}
