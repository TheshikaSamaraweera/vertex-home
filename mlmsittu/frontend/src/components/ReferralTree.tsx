import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { hierarchy, tree, type HierarchyPointNode } from 'd3-hierarchy';
import type { DistributorNode } from '../api/onboarding';
import { REFERRAL_STAGES, stageIndexes } from '../lib/stages';

/**
 * The referral network, drawn.
 *
 * d3 does the layout and nothing else — it computes coordinates, React owns every element on the
 * screen. Letting d3 touch the DOM inside a React tree means two things believing they own the
 * same nodes, and the bug that produces only appears once the data changes underneath.
 *
 * **Laid out left to right, not top down.** A referral network is deep and narrow — four places
 * per person, and depth is the thing people are reading for. Growing rightwards means names run
 * along the reading direction and stay legible at every level; a top-down tree would have to
 * either rotate the labels or space the columns far enough apart to fit them, and both get worse
 * the deeper it goes.
 *
 * Stage completion is carried by the fill, status by the border. Neither is the only signal:
 * every card also states its numbers, so the picture is readable without relying on colour.
 */

const CARD_W = 178;
const CARD_H = 52;
/** Breadth between siblings, then the gap between one level and the next. */
const ROW = 68;
const COL = 250;

type Positioned = HierarchyPointNode<TreeDatum>;

type TreeDatum = {
  node: DistributorNode | null;
  children: TreeDatum[];
};

export function ReferralTree({
  nodes,
  onOpenDetail,
}: {
  nodes: DistributorNode[];
  onOpenDetail: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [zoom, setZoom] = useState(1);
  const [focused, setFocused] = useState<string | null>(null);

  const layout = useMemo(() => {
    // A synthetic parent, because there can be several roots and d3 lays out one tree at a time.
    // It is never drawn; its children are the real roots.
    const byId = new Map<string, TreeDatum>();
    nodes.forEach((node) => {
      if (node.id) byId.set(node.id, { node, children: [] });
    });

    const roots: TreeDatum[] = [];
    nodes.forEach((node) => {
      const self = node.id ? byId.get(node.id) : undefined;
      if (!self) return;
      const parent = node.referredBy ? byId.get(node.referredBy) : undefined;
      // A node whose parent fell outside the depth cap is a root as far as this drawing is
      // concerned. Dropping it instead would silently lose people.
      if (parent && parent !== self) parent.children.push(self);
      else roots.push(self);
    });

    const virtualRoot: TreeDatum = { node: null, children: roots };
    const laid = tree<TreeDatum>().nodeSize([ROW, COL])(
      hierarchy<TreeDatum>(virtualRoot, (d) => d.children),
    );

    const points = laid.descendants().filter((point) => point.data.node !== null) as Positioned[];
    if (points.length === 0) {
      return { points, links: [], width: 0, height: 0, offsetX: 0, offsetY: 0 };
    }

    // d3 centres the layout on zero in both axes; shift it into positive space and pad for the
    // card, which is drawn from its own centre.
    const xs = points.map((p) => p.x);
    const ys = points.map((p) => p.y);
    const offsetY = -Math.min(...xs) + CARD_H;
    const offsetX = -Math.min(...ys) + CARD_W / 2 + 16;

    return {
      points,
      links: laid.links().filter((link) => link.source.data.node !== null),
      width: Math.max(...ys) - Math.min(...ys) + CARD_W + 48,
      height: Math.max(...xs) - Math.min(...xs) + CARD_H * 2,
      offsetX,
      offsetY,
    };
  }, [nodes]);

  const px = (point: { x: number; y: number }) => ({
    x: point.y + layout.offsetX,
    y: point.x + layout.offsetY,
  });

  /**
   * Hovering lights up the upline, not just the node.
   *
   * "Who is above this person" is the question a referral tree gets asked most, and tracing it by
   * eye across five levels of a wide drawing is genuinely hard. Highlighting only the node itself
   * would dim the answer along with everything else.
   */
  const lit = useMemo(() => {
    if (!focused) return null;
    const point = layout.points.find((candidate) => candidate.data.node?.id === focused);
    if (!point) return null;
    return new Set(
      point
        .ancestors()
        .map((ancestor) => ancestor.data.node?.id)
        .filter((id): id is string => Boolean(id)),
    );
  }, [focused, layout.points]);

  const isLit = (id: string | undefined) => !lit || (id != null && lit.has(id));

  if (layout.points.length === 0) {
    return null;
  }

  const levels = Math.max(...layout.points.map((point) => point.depth));

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Legend />
        <div className="flex items-center gap-1">
          <ZoomButton label={t('Zoom out')} onClick={() => setZoom((z) => Math.max(0.4, z - 0.15))}>
            −
          </ZoomButton>
          <span className="nums w-12 text-center text-[11px] text-ink3">
            {Math.round(zoom * 100)}%
          </span>
          <ZoomButton label={t('Zoom in')} onClick={() => setZoom((z) => Math.min(1.6, z + 0.15))}>
            +
          </ZoomButton>
          <ZoomButton label={t('Reset zoom')} onClick={() => setZoom(1)}>
            ⟲
          </ZoomButton>
        </div>
      </div>

      <div
        className="overflow-auto rounded-lg border border-rule bg-panel2"
        style={{ maxHeight: '70vh' }}
      >
        <svg
          role="img"
          aria-label={t('Referral tree, {{count}} distributors across {{levels}} levels', {
            count: layout.points.length,
            levels: levels,
          })}
          width={layout.width * zoom}
          height={layout.height * zoom}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          style={{ display: 'block' }}
        >
          {/* Links first so cards always sit on top of them. */}
          <g fill="none" strokeWidth={1.5}>
            {layout.links.map((link, index) => {
              const from = px(link.source as Positioned);
              const to = px(link.target as Positioned);
              const midX = (from.x + to.x) / 2;
              // A link is on the upline when both its ends are.
              const dimmed =
                !isLit((link.source as Positioned).data.node?.id) ||
                !isLit((link.target as Positioned).data.node?.id);
              return (
                <path
                  key={index}
                  stroke={dimmed ? 'var(--c-rule)' : 'var(--c-brand)'}
                  opacity={dimmed ? 0.25 : 1}
                  strokeWidth={dimmed ? 1.5 : 2.25}
                  d={`M ${from.x + CARD_W / 2} ${from.y}
                      C ${midX} ${from.y}, ${midX} ${to.y}, ${to.x - CARD_W / 2} ${to.y}`}
                />
              );
            })}
          </g>

          {layout.points.map((point) => (
            <NodeCard
              key={point.data.node?.id}
              node={point.data.node as DistributorNode}
              at={px(point)}
              dimmed={!isLit(point.data.node?.id)}
              onHover={setFocused}
              onOpen={onOpenDetail}
            />
          ))}
        </svg>
      </div>
    </div>
  );
}

function NodeCard({
  node,
  at,
  dimmed,
  onHover,
  onOpen,
}: {
  node: DistributorNode;
  at: { x: number; y: number };
  dimmed: boolean;
  onHover: (id: string | null) => void;
  onOpen: (id: string) => void;
}) {
  const { t } = useTranslation();

  const stages = node.stagesCompleted ?? 0;
  const slots = node.directChildCount ?? 0;
  const full = slots >= 4;

  // Fill deepens with progress, so a healthy branch reads as solid green at a glance and a stalled
  // one stays pale. Border carries status, which is a different fact and must not be conflated.
  const fill =
    stages >= 4
      ? 'var(--c-brand)'
      : stages === 0
        ? 'var(--c-panel)'
        : 'var(--c-brand-soft)';
  const border =
    node.status === 'active'
      ? stages >= 4
        ? 'var(--c-brand-deep)'
        : 'var(--c-brand)'
      : 'var(--c-danger)';
  const ink = stages >= 4 ? 'var(--c-brand-ink)' : 'var(--c-ink)';
  const inkSoft = stages >= 4 ? 'var(--c-brand-ink)' : 'var(--c-ink-3)';

  return (
    <g
      transform={`translate(${at.x - CARD_W / 2}, ${at.y - CARD_H / 2})`}
      opacity={dimmed ? 0.3 : 1}
      style={{ cursor: 'pointer' }}
      onMouseEnter={() => node.id && onHover(node.id)}
      onMouseLeave={() => onHover(null)}
      onClick={() => node.id && onOpen(node.id)}
      tabIndex={0}
      role="button"
      aria-label={`${node.businessId} ${node.fullName}, ${stages} of 4 stages, ${slots} of 4 referral places used`}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          if (node.id) onOpen(node.id);
        }
      }}
    >
      <rect
        width={CARD_W}
        height={CARD_H}
        rx={9}
        fill={fill}
        stroke={border}
        strokeWidth={node.bonusEligible ? 2.25 : 1.25}
      />

      <text x={11} y={20} fontSize={11} fontFamily="var(--font-mono, monospace)" fill={ink}>
        {node.businessId ?? '—'}
      </text>
      <text x={11} y={34} fontSize={11.5} fill={ink} fontWeight={500}>
        {truncate(node.fullName ?? '', 22)}
      </text>

      {/* Four dots for the four §0.2 stages, and the slot count. Both numbers are stated, so the
          colour is reinforcement rather than the only way to read the card. */}
      <g transform={`translate(11, ${CARD_H - 11})`}>
        {stageIndexes().map((index) => (
          <circle
            key={index}
            cx={index * 9}
            cy={0}
            r={3}
            fill={index < stages ? (stages >= 4 ? 'var(--c-brand-ink)' : 'var(--c-brand)') : 'none'}
            stroke={index < stages ? 'none' : border}
            strokeWidth={1}
            opacity={index < stages ? 1 : 0.5}
          />
        ))}
      </g>

      <text
        x={CARD_W - 11}
        y={CARD_H - 7}
        fontSize={10}
        textAnchor="end"
        fill={full ? 'var(--c-warn)' : inkSoft}
      >
        {slots}/{REFERRAL_STAGES} {t('places')}
      </text>

      {node.bonusEligible && (
        <text x={CARD_W - 11} y={19} fontSize={11} textAnchor="end" fill={ink}>
          ★
        </text>
      )}
    </g>
  );
}

function Legend() {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[11px] text-ink3">
      <span className="flex items-center gap-1.5">
        <span className="h-3 w-5 rounded border border-brand bg-panel" />
        {t('no stages yet')}
      </span>
      <span className="flex items-center gap-1.5">
        <span className="h-3 w-5 rounded border border-brand bg-brandsoft" />
        {t('in progress')}
      </span>
      <span className="flex items-center gap-1.5">
        <span className="h-3 w-5 rounded border border-brand-deep bg-brand" />
        {t('all four complete')}
      </span>
      <span className="flex items-center gap-1.5">
        <span className="h-3 w-5 rounded border border-danger bg-panel" />
        {t('not active')}
      </span>
      <span>★ {t('bonus eligible')}</span>
    </div>
  );
}

function ZoomButton({
  label,
  onClick,
  children,
}: {
  label: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className="flex h-7 w-7 items-center justify-center rounded border border-rule bg-panel text-sm text-ink2 transition-colors hover:border-brand hover:text-brand"
    >
      {children}
    </button>
  );
}

function truncate(value: string, max: number) {
  return value.length <= max ? value : value.slice(0, max - 1) + '…';
}
