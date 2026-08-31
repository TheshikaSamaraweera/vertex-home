import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Item } from '../api/types';

/**
 * Pick an item by browsing or by typing.
 *
 * A plain `<select>` is fine for a handful of options and miserable for a catalogue — with a few
 * hundred items you are scrolling a list looking for something you could have named in three
 * keystrokes. This is a combobox: click it and the whole catalogue drops down, type and it filters
 * on SKU *or* name.
 *
 * Built rather than pulled in, for the same reason the rest of the primitives are: a dependency
 * for one control is a poor trade, and the accessibility here is a handful of ARIA attributes and
 * arrow-key handling rather than anything exotic.
 */
export function ItemPicker({
  items,
  value,
  onChange,
  exclude = [],
  placeholder,
  autoFocus,
}: {
  items: Item[];
  /** The selected item's id, or '' for nothing chosen yet. */
  value: string;
  onChange: (itemId: string) => void;
  /** Ids already used elsewhere in the same form — an item cannot be in a set twice. */
  exclude?: string[];
  placeholder?: string;
  autoFocus?: boolean;
}) {
  const { t } = useTranslation();

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [highlighted, setHighlighted] = useState(0);

  const container = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);

  const selected = items.find((item) => item.id === value) ?? null;

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return items
      .filter((item) => item.id !== value && !exclude.includes(item.id ?? ''))
      .filter(
        (item) =>
          !needle ||
          (item.sku ?? '').toLowerCase().includes(needle) ||
          (item.name ?? '').toLowerCase().includes(needle),
      )
      .slice(0, 50); // A dropdown longer than this is a search problem, not a scrolling one.
  }, [items, query, value, exclude]);

  // Clicking anywhere else closes it. Without this the list stays open behind the next field and
  // covers whatever the user moved on to.
  useEffect(() => {
    if (!open) return;
    const onDocumentClick = (event: MouseEvent) => {
      if (!container.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocumentClick);
    return () => document.removeEventListener('mousedown', onDocumentClick);
  }, [open]);

  useEffect(() => setHighlighted(0), [query]);

  function choose(item: Item) {
    onChange(item.id ?? '');
    setQuery('');
    setOpen(false);
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
      setHighlighted((current) => {
        const next = event.key === 'ArrowDown' ? current + 1 : current - 1;
        return Math.max(0, Math.min(next, matches.length - 1));
      });
      return;
    }
    if (event.key === 'Enter' && open && matches[highlighted]) {
      // The picker sits inside a form; without this, Enter submits it half-filled.
      event.preventDefault();
      choose(matches[highlighted]);
      return;
    }
    if (event.key === 'Escape') {
      setOpen(false);
    }
  }

  const inputClass =
    'w-full rounded-md border border-rule bg-panel px-3 py-2 text-sm text-ink transition-colors ' +
    'placeholder:text-ink3 focus:border-brand focus:ring-2 focus:ring-brandsoft focus:outline-none';

  return (
    <div ref={container} className="relative">
      {selected && !open ? (
        // Once something is chosen, show it plainly rather than leaving a search box that has
        // apparently forgotten the answer.
        <button
          type="button"
          className={inputClass + ' flex items-center justify-between gap-2 text-left'}
          onClick={() => {
            setOpen(true);
            setQuery('');
            requestAnimationFrame(() => input.current?.focus());
          }}
        >
          <span className="min-w-0 truncate">
            <span className="font-mono text-xs text-ink2">{selected.sku}</span>
            <span className="ml-2">{selected.name}</span>
          </span>
          <span aria-hidden className="flex-none text-ink3">
            ▾
          </span>
        </button>
      ) : (
        <input
          ref={input}
          type="text"
          role="combobox"
          aria-expanded={open}
          aria-autocomplete="list"
          aria-label={t('Search for an item')}
          autoFocus={autoFocus}
          className={inputClass}
          placeholder={placeholder ?? t('Type to search, or click to browse…')}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
        />
      )}

      {open && (
        <ul
          role="listbox"
          className="absolute z-20 mt-1 max-h-64 w-full overflow-y-auto rounded-md border border-rule bg-panel shadow-float"
        >
          {matches.length === 0 ? (
            <li className="px-3 py-3 text-xs text-ink3">
              {query ? t('No item matches “{{query}}”', { query }) : t('Every item is already in this set.')}
            </li>
          ) : (
            matches.map((item, index) => (
              <li key={item.id}>
                <button
                  type="button"
                  role="option"
                  aria-selected={index === highlighted}
                  className={
                    'flex w-full items-baseline gap-2 px-3 py-2 text-left text-sm ' +
                    (index === highlighted ? 'bg-brandsoft text-brand' : 'hover:bg-panel2')
                  }
                  onMouseEnter={() => setHighlighted(index)}
                  onClick={() => choose(item)}
                >
                  <span className="font-mono text-xs text-ink3">{item.sku}</span>
                  <span className="min-w-0 truncate text-ink">{item.name}</span>
                </button>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  );
}
