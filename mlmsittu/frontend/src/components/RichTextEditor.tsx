import { useEditor, EditorContent, type Editor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Highlight from '@tiptap/extension-highlight';
import Underline from '@tiptap/extension-underline';
import { useTranslation } from 'react-i18next';
import { useEffect } from 'react';

/**
 * Writing an announcement.
 *
 * <p>Produces the same typed-block document {@link RichText} renders, which is the point: what the
 * author sees while typing and what a customer sees on the portal are two renderings of one
 * structure, not two parsers of one string.
 *
 * <p>**The extension list is the allowlist.** Only what is enabled here can end up in a document,
 * and the server refuses block types outside its own list on the way in. Adding a button means
 * adding it in three places — here, in the server's list, and in the renderer — which is
 * deliberate friction on the one part of this feature that decides what arrives in every
 * customer's browser.
 */
export function RichTextEditor({
  value,
  onChange,
}: {
  value: string;
  onChange: (json: string) => void;
}) {
  const { t } = useTranslation();

  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        // Off, all of them. A link is a URL somebody else's browser will follow, a code block and
        // a blockquote are not things a notice needs, and a horizontal rule is a decision about
        // layout rather than about content. Each one left on would be another node type the
        // renderer and the server both have to know about.
        link: false,
        codeBlock: false,
        blockquote: false,
        horizontalRule: false,
        code: false,
        strike: false,
      }),
      Highlight,
      Underline,
    ],
    content: safeParse(value),
    editorProps: {
      attributes: {
        class:
          'min-h-[14rem] px-4 py-3 text-sm text-ink focus:outline-none ' +
          '[&_h2]:text-xl [&_h2]:font-bold [&_h3]:text-lg [&_h3]:font-bold ' +
          '[&_ul]:list-disc [&_ul]:ml-5 [&_ol]:list-decimal [&_ol]:ml-5 ' +
          '[&_mark]:bg-warnsoft [&_mark]:rounded [&_mark]:px-0.5',
      },
    },
    onUpdate: ({ editor: current }) => onChange(JSON.stringify(current.getJSON())),
  });

  // Replacing the content when the caller hands us a different document — opening a second
  // announcement to edit, say. Guarded on the value actually differing, because setting content
  // on every render would move the caret to the start on every keystroke.
  useEffect(() => {
    if (!editor) return;
    const current = JSON.stringify(editor.getJSON());
    if (value && value !== current) {
      editor.commands.setContent(safeParse(value) ?? '');
    }
  }, [editor, value]);

  if (!editor) return null;

  return (
    <div className="overflow-hidden rounded-lg border-2 border-rulestrong bg-panel">
      <div className="flex flex-wrap items-center gap-1 border-b-2 border-rulestrong bg-panel2 px-2 py-1.5">
        <ToolButton editor={editor} action="toggleBold" active="bold" label={t('Bold')}>
          <span className="font-bold">B</span>
        </ToolButton>
        <ToolButton editor={editor} action="toggleItalic" active="italic" label={t('Italic')}>
          <span className="italic">I</span>
        </ToolButton>
        <ToolButton
          editor={editor}
          action="toggleUnderline"
          active="underline"
          label={t('Underline')}
        >
          <span className="underline">U</span>
        </ToolButton>
        <ToolButton
          editor={editor}
          action="toggleHighlight"
          active="highlight"
          label={t('Highlight')}
        >
          <span className="rounded bg-warnsoft px-1">H</span>
        </ToolButton>

        <Divider />

        <button
          type="button"
          onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
          className={tool(editor.isActive('heading', { level: 2 }))}
          title={t('Topic')}
        >
          {t('Topic')}
        </button>
        <button
          type="button"
          onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
          className={tool(editor.isActive('heading', { level: 3 }))}
          title={t('Sub-topic')}
        >
          {t('Sub-topic')}
        </button>

        <Divider />

        <ToolButton
          editor={editor}
          action="toggleBulletList"
          active="bulletList"
          label={t('Bullet list')}
        >
          •
        </ToolButton>
        <ToolButton
          editor={editor}
          action="toggleOrderedList"
          active="orderedList"
          label={t('Numbered list')}
        >
          1.
        </ToolButton>
      </div>

      <EditorContent editor={editor} />
    </div>
  );
}

function Divider() {
  return <span aria-hidden className="mx-1 h-5 w-px bg-rulestrong" />;
}

function tool(active: boolean): string {
  return (
    'min-w-8 rounded px-2 py-1 text-sm transition-colors ' +
    (active
      ? 'bg-brand text-brandink font-semibold'
      : 'text-ink2 hover:bg-brandsoft hover:text-brand')
  );
}

/** A toolbar button whose pressed state is the editor's, not a local copy that can drift. */
function ToolButton({
  editor,
  action,
  active,
  label,
  children,
}: {
  editor: Editor;
  action: 'toggleBold' | 'toggleItalic' | 'toggleUnderline' | 'toggleHighlight' | 'toggleBulletList' | 'toggleOrderedList';
  active: string;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={editor.isActive(active)}
      onClick={() => {
        const chain = editor.chain().focus();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (chain as any)[action]().run();
      }}
      className={tool(editor.isActive(active))}
    >
      {children}
    </button>
  );
}

/** A stored document that will not parse is treated as empty rather than crashing the editor. */
function safeParse(value: string): object | null {
  if (!value) return null;
  try {
    return JSON.parse(value) as object;
  } catch {
    return null;
  }
}
