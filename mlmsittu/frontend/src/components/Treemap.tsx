import { useEffect, useMemo, useRef, useState } from 'react';
import { hierarchy, treemap, treemapSquarify } from 'd3-hierarchy';
import type { TreemapNode } from '../api/reports';

/**
 * A squarified treemap (architecture §6.4).
 *
 * **Area encodes value, colour encodes recency.** Only `d3-hierarchy` is used — the layout
 * algorithm — and the rectangles are drawn as ordinary SVG that React owns. Pulling in
 * `d3-selection` to have D3 mutate the DOM underneath React is how the two end up fighting over the
 * same nodes.
 *
 * Squarified rather than the default slice-and-dice: long thin slivers are impossible to compare by
 * eye, and comparison is the only thing a treemap is for.
 *
 * The honest caveat, which is why §6.4 insists on a table toggle: **people are bad at comparing
 * areas.** Two rectangles differing by 20% look the same. This is a shape-of-the-business view; the
 * table is for reading numbers off.
 */

type Leaf = { name: string; category: string; value: number; daysSinceLastOrder: number | null };

export function Treemap({
  data,
  height = 460,
  onSelect,
}: {
  data: TreemapNode;
  height?: number;
  onSelect?: (leaf: Leaf) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(720);

  // Measured rather than assumed, so the layout is right on first paint at any container size.
  useEffect(() => {
    const element = container.current;
    if (!element) return;
    const observer = new ResizeObserver((entries) => {
      const measured = entries[0]?.contentRect.width;
      if (measured && measured > 0) setWidth(measured);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  // One layout pass, both halves of it. Laying out twice — once for the category blocks and once
  // for the leaves — would be two chances for the two to disagree about where anything is.
  const { cells, categories } = useMemo(() => {
    const nested = hierarchy<TreemapNode>(data, (node) => node.children ?? [])
      // Leaves carry the value; branches are the sum of their leaves. Computing a branch total on
      // the server as well would give two numbers a chance to disagree.
      .sum((node) => Number(node.value ?? 0))
      .sort((a, b) => (b.value ?? 0) - (a.value ?? 0));

    // The layout assigns x0/y0/x1/y1 in place, but only the returned value is typed as carrying
    // them — hence the assignment rather than a bare call.
    const root = treemap<TreemapNode>()
      .tile(treemapSquarify)
      .size([width, height])
      .paddingTop(18)
      .paddingInner(2)
      .round(true)(nested);

    return {
      cells: root.leaves().filter((leaf) => (leaf.value ?? 0) > 0),
      categories: root.children ?? [],
    };
  }, [data, width, height]);

  const total = cells.reduce((sum, leaf) => sum + (leaf.value ?? 0), 0);

  return (
    <div ref={container} className="w-full">
      <svg
        width={width}
        height={height}
        role="img"
        aria-label={`Treemap of sales by category and customer, ${cells.length} customers, total ${total.toFixed(2)}`}
        className="max-w-full"
      >
        {categories.map((category) => (
          <g key={category.data.name ?? ''}>
            <rect
              x={category.x0}
              y={category.y0}
              width={Math.max(0, category.x1 - category.x0)}
              height={Math.max(0, category.y1 - category.y0)}
              className="fill-panel2 stroke-rule"
            />
            <text
              x={category.x0 + 4}
              y={category.y0 + 13}
              className="fill-ink2 text-[11px] font-semibold"
            >
              {category.data.name ?? ''}
            </text>
          </g>
        ))}

        {cells.map((leaf, index) => {
          const w = Math.max(0, leaf.x1 - leaf.x0);
          const h = Math.max(0, leaf.y1 - leaf.y0);
          const value = leaf.value ?? 0;
          const share = total > 0 ? (value / total) * 100 : 0;

          return (
            <g
              key={`${leaf.parent?.data.name}-${leaf.data.name}-${index}`}
              onClick={() =>
                onSelect?.({
                  name: leaf.data.name ?? '',
                  category: leaf.parent?.data.name ?? '',
                  value,
                  daysSinceLastOrder: leaf.data.daysSinceLastOrder ?? null,
                })
              }
              className={onSelect ? 'cursor-pointer' : undefined}
            >
              <rect
                x={leaf.x0}
                y={leaf.y0}
                width={w}
                height={h}
                fill={recencyColour(leaf.data.daysSinceLastOrder ?? null)}
                className="stroke-panel hover:opacity-80"
                strokeWidth={1}
              >
                {/* Native SVG tooltip — no library, works with the keyboard focus ring too. */}
                <title>
                  {`${leaf.parent?.data.name} › ${leaf.data.name}\n${value.toFixed(2)} (${share.toFixed(1)}%)\n${recencyLabel(leaf.data.daysSinceLastOrder ?? null)}`}
                </title>
              </rect>

              {/* Labels only where they fit. A clipped label is worse than none. */}
              {w > 54 && h > 24 && (
                <>
                  <text x={leaf.x0 + 4} y={leaf.y0 + 13} className="fill-ink text-[10px] font-medium">
                    {truncate(leaf.data.name ?? '', Math.floor(w / 6))}
                  </text>
                  {h > 34 && (
                    <text
                      x={leaf.x0 + 4}
                      y={leaf.y0 + 25}
                      className="fill-ink2 text-[10px] tabular-nums"
                    >
                      {value.toFixed(0)}
                    </text>
                  )}
                </>
              )}
            </g>
          );
        })}
      </svg>

      <RecencyLegend />
    </div>
  );
}

/**
 * Colour is recency, not value — value is already the area, and encoding it twice wastes the one
 * other channel available.
 *
 * A single ramp running green → amber → terracotta, matching the meaning the rest of the app gives
 * those hues: healthy, needs attention, gone cold. Every band is held light enough to carry dark
 * text, because these rectangles have labels inside them; a saturated brand-green fill would look
 * handsome and make its own label unreadable.
 *
 * Literal values rather than theme tokens on purpose — a chart scale needs even perceptual steps
 * between its bands, which is a different job from the interface tokens and would break the moment
 * somebody re-tuned one of those for a button.
 */
function recencyColour(days: number | null): string {
  if (days === null) return '#dbe4dd'; // no order in this range
  if (days <= 30) return '#7cbd97'; // this month
  if (days <= 90) return '#b3d5bd'; // this quarter
  if (days <= 180) return '#edcf94'; // going quiet
  return '#e2a88c'; // lapsed
}

function recencyLabel(days: number | null): string {
  if (days === null) return 'No recent order';
  if (days <= 30) return `Last order ${days} days ago — active`;
  if (days <= 90) return `Last order ${days} days ago — this quarter`;
  if (days <= 180) return `Last order ${days} days ago — going quiet`;
  return `Last order ${days} days ago — lapsed`;
}

const BANDS = [
  { colour: '#7cbd97', label: '≤30 days' },
  { colour: '#b3d5bd', label: '31–90' },
  { colour: '#edcf94', label: '91–180' },
  { colour: '#e2a88c', label: '180+' },
];

function RecencyLegend() {
  return (
    <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-ink3">
      <span className="font-semibold">Colour = time since last order</span>
      {BANDS.map((band) => (
        <span key={band.label} className="flex items-center gap-1.5">
          <span
            aria-hidden
            className="h-2.5 w-2.5 rounded-sm"
            style={{ backgroundColor: band.colour }}
          />
          {band.label}
        </span>
      ))}
      <span className="ml-auto">Area = value</span>
    </div>
  );
}

function truncate(text: string, maxChars: number): string {
  if (maxChars < 3) return '';
  return text.length <= maxChars ? text : `${text.slice(0, maxChars - 1)}…`;
}
