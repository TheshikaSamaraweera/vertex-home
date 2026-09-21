import type { ReactNode } from 'react';

/**
 * Renders an announcement body.
 *
 * <h2>There is no `dangerouslySetInnerHTML` here, and there must never be</h2>
 *
 * The body is a document of typed blocks, not HTML. This walks that structure and emits React
 * elements, so nothing an author writes can become markup — the worst a malicious or compromised
 * administrator can do is produce text that says something untrue, which is a management problem
 * rather than a security one.
 *
 * The alternative, storing HTML and sanitising it, puts every customer's browser behind a
 * sanitiser being right forever. This puts them behind a switch statement.
 *
 * **An unknown node renders as nothing.** Not as its raw JSON, not as a guess — a block type this
 * does not recognise is skipped along with its children. That is the behaviour that keeps the
 * guarantee true when somebody adds a block to the editor and forgets to add it here: the notice
 * looks incomplete, which is visible, instead of the renderer improvising, which is not.
 */

type Mark = { type?: string };
type Node = {
  type?: string;
  text?: string;
  marks?: Mark[];
  attrs?: Record<string, unknown>;
  content?: Node[];
};

export function RichText({ body }: { body: string | null | undefined }) {
  const doc = parse(body);
  if (!doc) return null;
  return <div className="flex flex-col gap-3">{renderAll(doc.content)}</div>;
}

/** A body that will not parse renders as nothing rather than throwing the page away. */
function parse(body: string | null | undefined): Node | null {
  if (!body) return null;
  try {
    return JSON.parse(body) as Node;
  } catch {
    return null;
  }
}

function renderAll(nodes: Node[] | undefined): ReactNode[] {
  return (nodes ?? []).map((node, index) => <Block key={index} node={node} />);
}

function Block({ node }: { node: Node }): ReactNode {
  switch (node.type) {
    case 'heading': {
      // Levels are clamped rather than trusted: an attrs.level of 9 is not an element.
      const level = Math.min(Math.max(Number(node.attrs?.level ?? 2), 1), 3);
      const classes =
        level === 1
          ? 'text-xl font-bold text-ink'
          : level === 2
            ? 'text-lg font-bold text-ink'
            : 'text-base font-semibold text-ink';
      // A real heading element, so the notice has an outline a screen reader can navigate.
      if (level === 1) return <h2 className={classes}>{renderAll(node.content)}</h2>;
      if (level === 2) return <h3 className={classes}>{renderAll(node.content)}</h3>;
      return <h4 className={classes}>{renderAll(node.content)}</h4>;
    }

    case 'paragraph':
      // An empty paragraph is a deliberate blank line in the editor, so it keeps its height.
      return (
        <p className="text-sm leading-relaxed text-ink2">
          {node.content ? renderAll(node.content) : ' '}
        </p>
      );

    case 'bulletList':
      return (
        <ul className="ml-5 flex list-disc flex-col gap-1 text-sm text-ink2">
          {renderAll(node.content)}
        </ul>
      );

    case 'orderedList':
      return (
        <ol className="ml-5 flex list-decimal flex-col gap-1 text-sm text-ink2">
          {renderAll(node.content)}
        </ol>
      );

    case 'listItem':
      return <li>{renderAll(node.content)}</li>;

    case 'hardBreak':
      return <br />;

    case 'text':
      return <Text node={node} />;

    default:
      // Deliberately nothing. See the header.
      return null;
  }
}

/**
 * A run of text with its marks.
 *
 * <p>Marks are applied by nesting elements rather than by building a class string, so two marks on
 * one run compose instead of one silently winning.
 */
function Text({ node }: { node: Node }): ReactNode {
  let element: ReactNode = node.text ?? '';

  for (const mark of node.marks ?? []) {
    switch (mark.type) {
      case 'bold':
        element = <strong className="font-bold text-ink">{element}</strong>;
        break;
      case 'italic':
        element = <em className="italic">{element}</em>;
        break;
      case 'underline':
        element = <u>{element}</u>;
        break;
      case 'highlight':
        // The brand tint rather than the browser's yellow, which belongs to a different century
        // and to somebody else's design.
        element = <mark className="rounded bg-warnsoft px-0.5 text-ink">{element}</mark>;
        break;
      default:
        // An unrecognised mark leaves the text alone. The words still arrive; only the emphasis
        // is lost, which is the right way round for something nobody has vetted.
        break;
    }
  }
  return element;
}
