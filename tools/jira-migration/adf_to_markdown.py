"""Convert Atlassian Document Format (ADF) to GitHub-Flavored Markdown.

Scope is deliberately narrow: this converter handles exactly the node and mark
types that occur in the committed Jira export under ``docs/jira-export/``, and
raises on anything else.  It is not a general-purpose ADF converter, and it
should not be made into one -- an unknown node type is a signal that the corpus
changed and the output needs re-reviewing, so failing loudly beats guessing.

The inventory it covers, measured over all 260 ADF bodies in the export
(164 descriptions + 96 comments):

    blocks  doc paragraph heading bulletList orderedList listItem taskList
            taskItem table tableRow tableHeader tableCell codeBlock
            blockquote rule
    marks   code strong em strike link

Three properties of that corpus make the mapping to Markdown lossless, and all
three are asserted by ``verify_corpus_assumptions`` so that a future export
cannot quietly violate them:

    1. list nesting never exceeds one level;
    2. every listItem and every table cell holds exactly one paragraph;
    3. every table's first row is entirely tableHeader cells, and no later row
       contains one.

Without (2) the GFM pipe-table and list syntaxes could not express the content
at all, since neither admits block children.

Two deliberate departures from a literal transcription, both evidence-driven:

    * Backticks typed literally into Jira prose (440 of them) are emitted as
      real code spans rather than escaped.  Jira rendered them as literal
      backticks, but the authorial intent is unambiguous -- ``the `code` word``
      -- and GitHub renders the intent.  Where a paragraph's plain-text
      backtick count is odd the run is escaped instead, since an unpaired
      backtick would otherwise swallow the rest of the line.
    * ``#NN`` in prose is left unescaped so that GitHub auto-links it.  Every
      occurrence in the corpus is a pull-request reference ("PRs #22, #23, #24")
      and those pull requests live in this repository, so the auto-link is
      correct.  A ``#`` that begins a line is still escaped, as it would
      otherwise become a heading.

Jira links are rewritten rather than carried over, because the Jira site is
being decommissioned and every one of its URLs is about to die:

    /browse/EOP-N   ->  the key as text, plus the GitHub issue number once the
                        mapping is known.  Lossless: all 902 such links in the
                        corpus have link text identical to the key.
    /browse/ADR-N   ->  a link to the ADR file in this repository.  These 533
                        links are Jira smart-links to a project ``ADR`` that
                        never existed; they were always dead, and the real
                        target is docs/adr/ADR-N-*.md.
    other Jira URL  ->  link text only, dead URL dropped.
    relative path   ->  a blob link into this repository.

The same converter produces both passes of the migration.  Pass one runs with
``xref=None`` because GitHub issue numbers are not known until the issues
exist; pass two re-converts from this same ADF with the mapping supplied.
Re-converting is what keeps cross-reference rewriting safe: the converter knows
structurally which text is code, so the 51 ``EOP-N`` mentions that sit inside
code spans and code blocks are never rewritten, whereas a regex pass over
finished Markdown would corrupt them -- several are example commit messages of
the form ``[EOP-166] chore: ...``.
"""

from __future__ import annotations

import re
from collections import Counter
from pathlib import Path

REPO = "maglez/eop-threat-medeling"
REPO_BLOB = f"https://github.com/{REPO}/blob/main"

KEY_PATTERN = re.compile(r"\bEOP-\d+\b")
JIRA_BROWSE = re.compile(r"atlassian\.net/browse/([A-Z]+-\d+)\b")

# A Jira browse URL sitting in running text rather than behind a link mark.
JIRA_URL_PREFIX = r"https?://[^\s)\]]*atlassian\.net/browse/"

# Matches a Jira browse URL or a bare key, in one alternation so that a single
# pass consumes the whole URL and can never rewrite the key inside it.  Doing
# this in two passes corrupts the second import pass, turning
# ".../browse/EOP-147" into ".../browse/EOP-147 (#268)".
REFERENCE_PATTERN = re.compile(
    rf"(?P<url>{JIRA_URL_PREFIX}(?P<url_key>[A-Z]+-\d+))" r"|(?P<key>\bEOP-\d+\b)"
)

# A complete Markdown link typed literally into a Jira field.  Five comment
# bodies carry one (all of the form "PR [#93](https://github.com/.../pull/93)"),
# and escaping the brackets would destroy a link GitHub renders correctly, so
# these spans are passed through verbatim.
MARKDOWN_LINK = re.compile(r"\[[^\]\n]*\]\(https?://[^\s)]+\)")

# Keys whose half-parsed link syntax _repair_leaked_link_syntax may strip.  ADR
# is included because two comments wrap an auto-linkified ADR id in Markdown
# pointing at the real file, e.g. link(ADR-020) + "](docs/adr/ADR-020-....md)".
REPAIRABLE_KEY = re.compile(r"(?:EOP|ADR)-\d+")

# Emphasis markers, outermost first.  Applied to maximal runs of adjacent nodes
# sharing a mark rather than per node -- see _apply_marks.
EMPHASIS_MARKERS = (("strong", "**"), ("em", "*"), ("strike", "~~"))

BLOCK_NODES = frozenset(
    {
        "paragraph",
        "heading",
        "bulletList",
        "orderedList",
        "taskList",
        "table",
        "codeBlock",
        "blockquote",
        "rule",
    }
)


class UnsupportedNode(RuntimeError):
    """Raised when the ADF contains a node or mark the converter does not cover."""


def discover_adr_files(adr_dir: Path) -> dict[str, str]:
    """Map each ADR number to its filename.

    :param adr_dir: directory holding ``ADR-NNN-slug.md`` files.
    :return: mapping of ADR key (e.g. ``ADR-009``) to bare filename.
    """
    found: dict[str, str] = {}
    for path in sorted(adr_dir.glob("ADR-*.md")):
        match = re.match(r"(ADR-\d+)-", path.name)
        if match:
            found[match.group(1)] = path.name
    return found


class Converter:
    """Render ADF documents as Markdown, collecting statistics as it goes."""

    def __init__(self, adr_files: dict[str, str], xref: dict[str, int] | None = None):
        """Create a converter.

        :param adr_files: mapping from ADR key to filename, per :func:`discover_adr_files`.
        :param xref: mapping from Jira key to GitHub issue number, or ``None``
            during the first pass when those numbers are not yet known.
        """
        self.adr_files = adr_files
        self.xref = xref or {}
        self.stats: Counter[str] = Counter()
        self.unresolved_keys: Counter[str] = Counter()
        self.missing_adrs: Counter[str] = Counter()

    # ----------------------------------------------------------------- helpers

    def _key_reference(self, key: str) -> str:
        """Render a reference to a Jira key, adding the issue number when known."""
        number = self.xref.get(key)
        if number is None:
            self.unresolved_keys[key] += 1
            self.stats["xref_unresolved"] += 1
            return key
        self.stats["xref_resolved"] += 1
        return f"{key} (#{number})"

    def key_reference(self, key: str) -> str:
        """Render a reference to a Jira key for use outside converted prose.

        The provenance and relationship sections are assembled from Jira fields
        rather than from ADF, but must render keys exactly as the converted
        prose does so that both passes stay consistent.

        :param key: a Jira issue key such as ``EOP-12``.
        :return: the key, followed by the GitHub issue number when known.
        """
        return self._key_reference(key)

    def _rewrite_keys(self, text: str) -> str:
        """Deprecated alias retained only so no call site silently misses the URL pass."""
        raise NotImplementedError("use _rewrite_references, which handles URLs and keys together")


    def _rewrite_references(self, text: str) -> str:
        """Rewrite Jira browse URLs and bare keys in one left-to-right pass.

        Both forms are handled by a single :func:`re.sub` so that a URL is
        consumed whole; rewriting keys separately would corrupt the key embedded
        in a surviving URL during the second pass.

        :param text: already-escaped plain text.
        :return: the text with references rewritten.
        """

        def replace(match: re.Match[str]) -> str:
            if match.group("key"):
                return self._key_reference(match.group("key"))
            key = match.group("url_key")
            self.stats["bare_jira_url_rewritten"] += 1
            if key.startswith("ADR-"):
                return self._adr_link(key, key)
            if KEY_PATTERN.fullmatch(key):
                return self._key_reference(key)
            return key

        return REFERENCE_PATTERN.sub(replace, text)

    def _repair_leaked_link_syntax(self, nodes: list[dict]) -> list[dict]:
        """Strip link syntax that Jira stored as literal text.

        Some descriptions were authored with Markdown or Jira wiki link syntax
        that Jira only half-parsed: it linkified the key but left the
        surrounding punctuation and the URL as plain text, producing runs such
        as ``[`` + link(``EOP-147``) + ``](https://.../browse/EOP-147)``.  Left
        alone these render as visible broken syntax wrapped around a dead URL.

        Only a remnant naming the very key the adjacent link already carries is
        removed, so no reference is ever lost.  The remnant may be either the
        dying Jira URL or a relative path -- two comments wrap an
        auto-linkified ADR id in Markdown pointing at the real file, whose path
        is exactly the target ``_adr_link`` already rewrites to, so discarding
        it loses nothing.

        :param nodes: the inline nodes of one paragraph.
        :return: a repaired shallow copy, or the original list when untouched.
        """
        if len(nodes) < 2:
            return nodes
        out: list[dict] = [dict(n) for n in nodes]
        for index, node in enumerate(out):
            if node.get("type") != "text":
                continue
            if "link" not in {m.get("type") for m in (node.get("marks") or [])}:
                continue
            key = node.get("text", "").strip()
            if not REPAIRABLE_KEY.fullmatch(key) or index + 1 >= len(out):
                continue
            following = out[index + 1]
            if following.get("type") != "text":
                continue
            text = following.get("text", "")
            url = JIRA_URL_PREFIX + re.escape(key) + r"\b"
            # A relative path naming the same key, e.g.
            # "docs/adr/ADR-020-session-concurrency-control.md".  Bounded to a
            # single token so it cannot swallow prose.
            relative = r"[^)\s|\]]*" + re.escape(key) + r"[^)\s|\]]*"
            target = rf"(?:{url}|{relative})"
            # (pattern, whether it closes a bracket opened before the link)
            remnants = (
                (rf"^\]\(\s*{target}\s*\)", True),
                (rf"^\|\s*{target}\s*\]", True),
                # Colon form is URL-only: a bare relative path after a colon is
                # ordinary prose, not leaked syntax.
                (rf"^:\s*{url}", False),
            )
            for pattern, bracketed in remnants:
                match = re.match(pattern, text)
                if not match:
                    continue
                following["text"] = text[match.end() :]
                self.stats["leaked_link_syntax_repaired"] += 1
                if bracketed and index > 0:
                    previous = out[index - 1]
                    if previous.get("type") == "text" and previous.get("text", "").endswith("["):
                        previous["text"] = previous["text"][:-1]
                break
        return out

    def _adr_link(self, key: str, label: str) -> str:
        """Render a link to an ADR file, or plain text when no such file exists."""
        filename = self.adr_files.get(key)
        if filename is None:
            self.missing_adrs[key] += 1
            self.stats["adr_link_missing"] += 1
            return label
        self.stats["adr_link_rewritten"] += 1
        return f"[{label}]({REPO_BLOB}/docs/adr/{filename})"

    @staticmethod
    def _code_span(text: str) -> str:
        """Wrap text in a code span with a fence long enough to contain it."""
        longest = max((len(run) for run in re.findall(r"`+", text)), default=0)
        fence = "`" * (longest + 1)
        pad = " " if text.startswith("`") or text.endswith("`") else ""
        return f"{fence}{pad}{text}{pad}{fence}"

    @staticmethod
    def _escape_plain(text: str, *, in_table: bool, escape_backticks: bool) -> str:
        """Escape Markdown metacharacters in a plain-text run.

        Escaping is confined to characters that would otherwise change how the
        text renders.  ``_`` is left alone between alphanumerics because GFM
        does not treat intraword underscores as emphasis, which keeps
        identifiers such as ``snake_case`` readable in the raw body.

        A complete Markdown link typed literally into Jira is passed through
        verbatim: Jira showed it as broken syntax, but the author's intent is
        unambiguous and GitHub renders it, so escaping the brackets would be the
        one case where faithfulness to Jira loses information.

        :param text: the literal text from the ADF ``text`` node.
        :param in_table: whether the run sits inside a table cell, where ``|``
            would otherwise end the cell.
        :param escape_backticks: whether to neutralise backticks rather than
            letting them form code spans.
        :return: the escaped text.
        """

        def escape(chunk: str) -> str:
            out = chunk.replace("\\", "\\\\")
            out = out.replace("<", "\\<")
            out = out.replace("*", "\\*")
            out = out.replace("[", "\\[").replace("]", "\\]")
            out = re.sub(r"(?<![0-9A-Za-z])_|_(?![0-9A-Za-z])", lambda m: "\\_", out)
            if escape_backticks:
                out = out.replace("`", "\\`")
            if in_table:
                out = out.replace("|", "\\|")
            return out

        pieces: list[str] = []
        position = 0
        for match in MARKDOWN_LINK.finditer(text):
            pieces.append(escape(text[position : match.start()]))
            pieces.append(match.group(0))
            position = match.end()
        pieces.append(escape(text[position:]))
        return "".join(pieces)

    @staticmethod
    def _emphasise(body: str, marker: str) -> str:
        """Apply an emphasis marker, keeping surrounding whitespace outside it.

        GFM does not recognise ``**bold **``, so any leading or trailing
        whitespace has to sit outside the markers.

        :param body: the already-rendered inline content.
        :param marker: the emphasis marker, e.g. ``**``.
        :return: the emphasised content.
        """
        stripped = body.strip()
        if not stripped:
            return body
        lead = body[: len(body) - len(body.lstrip())]
        trail = body[len(body.rstrip()) :]
        return f"{lead}{marker}{stripped}{marker}{trail}"

    @staticmethod
    def _protect_line_start(text: str) -> str:
        """Escape a leading character that would turn a paragraph into another block.

        A paragraph is emitted on its own line, so text beginning with ``#``,
        ``>``, ``-``, ``+`` or ``1.`` would otherwise be parsed as a heading,
        blockquote or list.  Mid-line occurrences are deliberately left alone --
        every ``#NN`` in the corpus is a pull-request reference that should
        auto-link.

        :param text: the rendered paragraph.
        :return: the paragraph, with any block-starting marker neutralised.
        """
        if re.match(r"[#>+=-]", text) or re.match(r"\d+[.)]\s", text):
            return "\\" + text
        return text

    def _render_link(self, label: str, href: str, raw_text: str) -> str:
        """Render a link mark, rewriting or dropping dying Jira URLs."""
        browse = JIRA_BROWSE.search(href)
        if browse:
            key = browse.group(1)
            if key.startswith("EOP-"):
                self.stats["link_jira_issue"] += 1
                # Link text equals the key throughout the corpus, so the key
                # reference carries everything the link did.
                return self._key_reference(key)
            if key.startswith("ADR-"):
                return self._adr_link(key, label)
            self.stats["link_jira_other_project"] += 1
            return label
        if "atlassian.net" in href:
            self.stats["link_jira_dropped"] += 1
            return label
        if href.startswith(("http://", "https://", "mailto:")):
            self.stats["link_external"] += 1
            return f"[{label}]({href})"
        self.stats["link_relative_rewritten"] += 1
        return f"[{label}]({REPO_BLOB}/{href.lstrip('/')})"

    # ------------------------------------------------------------------ inline

    def _apply_marks(
        self, items: list[tuple[set[str], str]], markers: tuple[tuple[str, str], ...]
    ) -> str:
        """Wrap maximal runs of adjacent items sharing an emphasis mark.

        Jira stores emphasis per text node, so a bolded phrase containing a link
        arrives as several adjacent nodes each carrying ``strong``.  Emitting a
        marker pair per node bolds the fragments separately -- and where a node
        renders as a bare key reference rather than a link, the emphasis was
        being dropped altogether, yielding ``**Replaces** EOP-12**,**`` for what
        Jira showed as one continuous ``**Replaces EOP-12,**``.

        :param items: ``(mark names, rendered core)`` per inline node, in order.
        :param markers: remaining ``(mark name, Markdown marker)`` pairs to
            apply, outermost first.
        :return: the rendered run.
        """
        if not markers:
            return "".join(core for _, core in items)
        name, marker = markers[0]
        rest = markers[1:]
        out: list[str] = []
        index = 0
        while index < len(items):
            present = name in items[index][0]
            end = index
            while end < len(items) and (name in items[end][0]) == present:
                end += 1
            inner = self._apply_marks(items[index:end], rest)
            out.append(self._emphasise(inner, marker) if present else inner)
            index = end
        return "".join(out)

    def _inline(self, nodes: list[dict], *, in_table: bool = False) -> str:
        """Render a run of inline nodes.

        :param nodes: the ADF inline nodes.
        :param in_table: whether the run sits inside a table cell.
        :return: the rendered Markdown.
        """
        nodes = self._repair_leaked_link_syntax(nodes)
        plain_backticks = 0
        for node in nodes:
            if node.get("type") != "text":
                continue
            marks = {m.get("type") for m in (node.get("marks") or [])}
            if "code" not in marks:
                plain_backticks += node.get("text", "").count("`")
        escape_backticks = plain_backticks % 2 == 1
        if escape_backticks:
            self.stats["paragraph_backticks_escaped"] += 1

        items: list[tuple[set[str], str]] = []
        for node in nodes:
            node_type = node.get("type")
            if node_type != "text":
                raise UnsupportedNode(f"unsupported inline node: {node_type!r}")
            text = node.get("text", "")
            marks = node.get("marks") or []
            mark_types = [m.get("type") for m in marks]
            for mark_type in mark_types:
                if mark_type not in {"code", "strong", "em", "strike", "link"}:
                    raise UnsupportedNode(f"unsupported mark: {mark_type!r}")
                self.stats[f"mark_{mark_type}"] += 1

            if "code" in mark_types:
                body = self._code_span(text)
            else:
                body = self._escape_plain(
                    text, in_table=in_table, escape_backticks=escape_backticks
                )
                body = self._rewrite_references(body)
            # The link is applied before emphasis, so that a link rendering as a
            # bare key reference still carries the run's emphasis.
            link = next((m for m in marks if m.get("type") == "link"), None)
            if link:
                body = self._render_link(body, (link.get("attrs") or {}).get("href", ""), text)
            items.append(({m for m in mark_types if m is not None}, body))
        return self._apply_marks(items, EMPHASIS_MARKERS)

    # ------------------------------------------------------------------ blocks

    @staticmethod
    def _only_paragraph(node: dict, context: str) -> list[dict]:
        """Return the single paragraph's inline content, enforcing assumption (2)."""
        children = node.get("content") or []
        if len(children) != 1 or children[0].get("type") != "paragraph":
            kinds = [c.get("type") for c in children]
            raise UnsupportedNode(f"{context} must hold exactly one paragraph, got {kinds}")
        return children[0].get("content") or []

    def _table(self, node: dict) -> str:
        """Render a table as a GFM pipe table."""
        rows = [r for r in (node.get("content") or []) if r.get("type") == "tableRow"]
        if not rows:
            return ""
        rendered: list[list[str]] = []
        for row in rows:
            cells = row.get("content") or []
            rendered.append(
                [
                    self._inline(self._only_paragraph(c, "table cell"), in_table=True).strip()
                    or " "
                    for c in cells
                ]
            )
        width = max(len(r) for r in rendered)
        for row in rendered:
            row.extend([" "] * (width - len(row)))
        header, *body = rendered
        lines = [
            "| " + " | ".join(header) + " |",
            "| " + " | ".join(["---"] * width) + " |",
        ]
        lines += ["| " + " | ".join(r) + " |" for r in body]
        self.stats["table"] += 1
        return "\n".join(lines)

    def _list(self, node: dict, ordered: bool) -> str:
        """Render a bullet or ordered list."""
        lines = []
        for index, item in enumerate(node.get("content") or [], start=1):
            if item.get("type") != "listItem":
                raise UnsupportedNode(f"unsupported list child: {item.get('type')!r}")
            marker = f"{index}." if ordered else "-"
            text = self._inline(self._only_paragraph(item, "list item")).strip()
            lines.append(f"{marker} {text}")
        self.stats["list"] += 1
        return "\n".join(lines)

    def _task_list(self, node: dict) -> str:
        """Render a task list as GFM checkboxes."""
        lines = []
        for item in node.get("content") or []:
            if item.get("type") != "taskItem":
                raise UnsupportedNode(f"unsupported taskList child: {item.get('type')!r}")
            state = (item.get("attrs") or {}).get("state")
            box = "x" if state == "DONE" else " "
            text = self._inline(item.get("content") or []).strip()
            lines.append(f"- [{box}] {text}")
        self.stats["task_list"] += 1
        return "\n".join(lines)

    def _code_block(self, node: dict) -> str:
        """Render a code block, widening the fence if the body contains one."""
        text = "".join(c.get("text", "") for c in (node.get("content") or []))
        language = (node.get("attrs") or {}).get("language") or ""
        longest = max((len(run) for run in re.findall(r"^`{3,}", text, re.MULTILINE)), default=0)
        fence = "`" * max(3, longest + 1)
        self.stats["code_block"] += 1
        return f"{fence}{language}\n{text}\n{fence}"

    def _block(self, node: dict) -> str:
        """Render a single block-level node."""
        node_type = node.get("type")
        self.stats[f"node_{node_type}"] += 1
        if node_type == "paragraph":
            return self._protect_line_start(self._inline(node.get("content") or []).rstrip())
        if node_type == "heading":
            level = (node.get("attrs") or {}).get("level", 2)
            return f"{'#' * level} {self._inline(node.get('content') or []).strip()}"
        if node_type == "bulletList":
            return self._list(node, ordered=False)
        if node_type == "orderedList":
            return self._list(node, ordered=True)
        if node_type == "taskList":
            return self._task_list(node)
        if node_type == "table":
            return self._table(node)
        if node_type == "codeBlock":
            return self._code_block(node)
        if node_type == "blockquote":
            inner = self._blocks(node.get("content") or [])
            return "\n".join(
                f"> {line}" if line else ">" for line in inner.split("\n")
            )
        if node_type == "rule":
            return "---"
        raise UnsupportedNode(f"unsupported block node: {node_type!r}")

    def _blocks(self, nodes: list[dict]) -> str:
        """Render a sequence of block nodes, separated by blank lines."""
        rendered = [self._block(n) for n in nodes]
        return "\n\n".join(block for block in rendered if block.strip())

    def convert(self, doc: dict) -> str:
        """Convert a whole ADF document.

        :param doc: an ADF ``doc`` node.
        :return: the rendered Markdown, with a trailing newline stripped.
        """
        if doc.get("type") != "doc":
            raise UnsupportedNode(f"expected a doc node, got {doc.get('type')!r}")
        text = self._blocks(doc.get("content") or [])
        # A paragraph that escaped to nothing can leave a run of blank lines.
        return re.sub(r"\n{3,}", "\n\n", text).strip()


def verify_corpus_assumptions(doc: dict) -> list[str]:
    """Check the three structural assumptions the Markdown mapping relies on.

    :param doc: an ADF ``doc`` node.
    :return: a list of human-readable violations, empty when the document is
        within the converter's proven scope.
    """
    problems: list[str] = []

    def walk(node: dict, list_depth: int) -> None:
        node_type = node.get("type")
        children = node.get("content") or []
        if node_type in {"bulletList", "orderedList", "taskList"}:
            list_depth += 1
            if list_depth > 1:
                problems.append(f"list nesting depth {list_depth}")
        if node_type in {"listItem", "tableCell", "tableHeader"}:
            kinds = [c.get("type") for c in children]
            if kinds != ["paragraph"]:
                problems.append(f"{node_type} holds {kinds}")
        if node_type == "table":
            rows = [r for r in children if r.get("type") == "tableRow"]
            if rows:
                first = [c.get("type") for c in (rows[0].get("content") or [])]
                if any(k != "tableHeader" for k in first):
                    problems.append(f"table first row is {first}")
                for row in rows[1:]:
                    if any(c.get("type") == "tableHeader" for c in (row.get("content") or [])):
                        problems.append("tableHeader outside the first row")
        for child in children:
            walk(child, list_depth)

    walk(doc, 0)
    return problems
