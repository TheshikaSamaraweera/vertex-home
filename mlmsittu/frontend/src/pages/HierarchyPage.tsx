import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  fetchDocumentObjectUrl,
  useDistributorChildren,
  useDistributorDetail,
  useDistributorForest,
  useDistributorRoots,
  type DistributorNode,
} from '../api/onboarding';
import { ReferralTree } from '../components/ReferralTree';
import { REFERRAL_STAGES } from '../lib/stages';
import { useAuth } from '../auth/AuthContext';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Field,
  Modal,
  PageHeader,
  Select,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * The referral network (P6-05), in two readings.
 *
 * **Tree** draws it. A picture cannot be assembled a level at a time — the layout needs the whole
 * shape before it can place anything — so this view fetches a bounded forest in one request and
 * pays for that departure from §6.4 with a hard depth cap, chosen by the reader.
 *
 * **Outline** is the original: two levels open on load, everything deeper costing exactly one
 * request per node and only when somebody clicks. It stays because the drawn view stops being
 * readable long before a real network stops being navigable, and at that size expand-on-demand is
 * the only thing that works. The network tab is the proof, and Gate 6 says to look at it.
 *
 * Admin and super admin only, and enforced server-side — the whole population, including who
 * recruited whom, is commercial structure rather than general staff information.
 */
export function HierarchyPage() {
  const { t } = useTranslation();
  const roots = useDistributorRoots();

  const [view, setView] = useState<'tree' | 'outline'>('tree');
  const [depth, setDepth] = useState(3);
  const [detailFor, setDetailFor] = useState<string | null>(null);

  // Guarded, not merely unused: the outline expands one node at a time, and fetching a whole
  // bounded forest it is never going to draw would undo the point of that view.
  const forest = useDistributorForest(depth, view === 'tree');

  const source = view === 'tree' ? forest : roots;
  const empty = view === 'tree' ? (forest.data ?? []).length === 0 : (roots.data ?? []).length === 0;

  return (
    <>
      <PageHeader
        title={t('Referral hierarchy')}
        description={t('The whole network. Visible to administrators only — who recruited whom is commercial structure, not general staff information.')}
        actions={
          <div className="flex flex-wrap items-end gap-2">
            {view === 'tree' && (
              <Field label={t('Levels')} hint={t('below each root')}>
                <Select
                  className="w-24"
                  value={String(depth)}
                  onChange={(event) => setDepth(Number(event.target.value))}
                >
                  {[1, 2, 3, 4, 5].map((value) => (
                    <option key={value} value={value}>
                      {value}
                    </option>
                  ))}
                </Select>
              </Field>
            )}
            <div className="flex overflow-hidden rounded-md border border-rule">
              <ViewTab active={view === 'tree'} onClick={() => setView('tree')}>
                {t('Tree')}
              </ViewTab>
              <ViewTab active={view === 'outline'} onClick={() => setView('outline')}>
                {t('Outline')}
              </ViewTab>
            </div>
          </div>
        }
      />

      <Card
        title={view === 'tree' ? t('Referral network') : t('Customers')}
        subtitle={
          view === 'tree'
            ? t('{{count}} distributor(s) to {{depth}} level(s). Click a card for the full record.', {
                count: (forest.data ?? []).length,
                depth,
              })
            : t('Two levels load up front; anything deeper is fetched when you expand it.')
        }
      >
        {source.isLoading ? (
          <Spinner />
        ) : source.error ? (
          <div className="p-4">
            <ErrorBanner error={source.error} onRetry={() => void source.refetch()} />
          </div>
        ) : empty ? (
          <EmptyState
            message={t('No customers yet.')}
            hint={t('Approve a registration to place the first one in the tree.')}
          />
        ) : view === 'tree' ? (
          <div className="p-3">
            <ReferralTree nodes={forest.data ?? []} onOpenDetail={setDetailFor} />
          </div>
        ) : (
          <ul className="p-2">
            {(roots.data ?? []).map((node) => (
              <TreeNode
                key={node.id}
                node={node}
                depth={0}
                // Depth 2 on load: the root and its children. Deeper nodes start closed.
                openByDefault
                onOpenDetail={setDetailFor}
              />
            ))}
          </ul>
        )}
      </Card>

      {detailFor && (
        <DistributorDetailModal id={detailFor} onClose={() => setDetailFor(null)} />
      )}
    </>
  );
}

/**
 * Two readings of the same network.
 *
 * The drawn tree shows shape — who sits under whom, where a branch has stalled — and needs the
 * whole thing laid out before it can place anything. The outline walks it one node at a time and
 * stays usable when the network is far too big to draw. Neither replaces the other, so both are
 * here rather than one being chosen on the reader's behalf.
 */
function ViewTab({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={
        'px-3 py-1.5 text-[13px] transition-colors ' +
        (active ? 'bg-brand font-semibold text-brandink' : 'bg-panel text-ink2 hover:text-brand')
      }
    >
      {children}
    </button>
  );
}

/**
 * The four §0.2 stages as filled dots, plus the bonus marker.
 *
 * Shows progression and nothing else: no reward, no value, no entitlement — because the backend
 * grants none. Putting a figure here that the system cannot actually honour would be worse than
 * showing nothing.
 *
 * Reads the numbers straight off the node. They used to be fetched per distributor, which was one
 * HTTP request per row on screen — tolerable for a demo of eight, hopeless for a real downline.
 */
function StageDots({ node }: { node: DistributorNode }) {
  const { t } = useTranslation();

  const completed = node.stagesCompleted ?? 0;
  const total = REFERRAL_STAGES;
  const bonus = node.bonusEligible ?? false;

  return (
    <span
      className="flex items-center gap-1"
      title={t('{{done}} of {{total}} enrolment stages complete', { done: completed, total })}
    >
      {Array.from({ length: total }, (_, index) => (
        <span
          key={index}
          aria-hidden
          className={
            'h-2 w-2 rounded-full ' + (index < completed ? 'bg-brand' : 'border border-rule')
          }
        />
      ))}
      {bonus && (
        <span className="ml-1 rounded-full border border-ok bg-oksoft px-1.5 text-[10px] text-ok">
          {t('bonus')}
        </span>
      )}
      <span className="sr-only">
        {t('{{done}} of {{total}} stages', { done: completed, total })}
      </span>
    </span>
  );
}

function TreeNode({
  node,
  depth,
  openByDefault = false,
  onOpenDetail,
}: {
  node: DistributorNode;
  depth: number;
  openByDefault?: boolean;
  onOpenDetail: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(openByDefault);

  // The query only runs once expanded is true — that is what makes this lazy rather than a tree
  // fetched up front and hidden with CSS. One node expanded, exactly one request.
  const children = useDistributorChildren(expanded ? (node.id ?? null) : null);

  const childCount = node.directChildCount ?? 0;
  const hasChildren = childCount > 0;

  return (
    <li>
      <div
        className="flex flex-wrap items-center gap-2 rounded px-2 py-1.5 hover:bg-panel2"
        style={{ paddingLeft: `${depth * 1.25 + 0.5}rem` }}
      >
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          disabled={!hasChildren}
          aria-expanded={expanded}
          aria-label={expanded ? t('Collapse') : t('Expand')}
          className={
            'flex h-5 w-5 items-center justify-center rounded text-xs ' +
            (hasChildren ? 'text-brand hover:bg-brandsoft' : 'cursor-default text-ink3 opacity-30')
          }
        >
          {hasChildren ? (expanded ? '▾' : '▸') : '·'}
        </button>

        <button
          type="button"
          onClick={() => node.id && onOpenDetail(node.id)}
          className="font-mono text-xs text-ink hover:text-brand hover:underline"
        >
          {node.businessId ?? '—'}
        </button>
        <span className="truncate text-sm text-ink2">{node.fullName}</span>

        {node.status !== 'active' && <Badge tone="danger">{node.status}</Badge>}

        {/* Slot usage is the number an admin actually wants: how many places are left. */}
        <span className="nums ml-auto text-[11px] text-ink3">
          {childCount}/{REFERRAL_STAGES} {t('referrals')}
        </span>

        <StageDots node={node} />
      </div>

      {expanded && (
        <ul>
          {children.isLoading && (
            <li style={{ paddingLeft: `${(depth + 1) * 1.25 + 0.5}rem` }}>
              <span className="text-xs text-ink3">{t('Loading…')}</span>
            </li>
          )}
          {children.error && (
            <li className="px-2 py-1">
              <ErrorBanner error={children.error} />
            </li>
          )}
          {(children.data ?? [])
            // The endpoint returns the subtree including the node asked about; drop it so a
            // distributor does not appear as its own child.
            .filter((child) => child.id !== node.id)
            .map((child) => (
              <TreeNode
                key={child.id}
                node={child}
                depth={depth + 1}
                onOpenDetail={onOpenDetail}
              />
            ))}
        </ul>
      )}
    </li>
  );
}

// ================================================================== detail (P6-06)

function DistributorDetailModal({ id, onClose }: { id: string; onClose: () => void }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const detail = useDistributorDetail(id);
  const [viewingDocument, setViewingDocument] = useState<string | null>(null);

  const canViewDocuments = hasRole('KYC_REVIEWER', 'SUPER_ADMIN');

  return (
    <Modal title={t('Customer')} onClose={onClose} wide>
      {detail.isLoading ? (
        <Spinner />
      ) : detail.error ? (
        <ErrorBanner error={detail.error} />
      ) : (
        <div className="flex flex-col gap-5">
          <div>
            <p className="font-mono text-sm text-ink">{detail.data?.distributor?.businessId}</p>
            <p className="text-sm text-ink2">{detail.data?.distributor?.fullName}</p>
          </div>

          <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
            <Row
              label={t('Referred by')}
              value={
                detail.data?.parent
                  ? `${detail.data.parent.businessId} · ${detail.data.parent.fullName}`
                  : t('Nobody — this is a root')
              }
            />
            <Row
              label={t('Referral places used')}
              value={`${detail.data?.referralsUsed ?? 0} / ${detail.data?.referralCapacity ?? 4}`}
            />
            <Row
              label={t('Path')}
              value={detail.data?.readablePath ?? '—'}
              hint={t('Stored as {{raw}}', { raw: detail.data?.rawPath ?? '—' })}
            />
            <Row
              label={t('Approved')}
              value={
                detail.data?.distributor?.approvedAt
                  ? new Date(detail.data.distributor.approvedAt).toLocaleString()
                  : '—'
              }
            />
          </dl>

          <div>
            <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
              {t('Direct referrals')}
            </p>
            {(detail.data?.children ?? []).length === 0 ? (
              <p className="text-sm text-ink3">{t('None yet.')}</p>
            ) : (
              <TableWrap>
                <Table>
                  <thead>
                    <tr>
                      <Th>{t('Business ID')}</Th>
                      <Th>{t('Name')}</Th>
                      <Th>{t('Status')}</Th>
                      <Th align="right">{t('Their referrals')}</Th>
                    </tr>
                  </thead>
                  <tbody>
                    {(detail.data?.children ?? []).map((child) => (
                      <tr key={child.id}>
                        <Td className="font-mono text-xs">{child.businessId ?? '—'}</Td>
                        <Td className="text-ink">{child.fullName}</Td>
                        <Td>
                          <Badge tone={child.status === 'active' ? 'ok' : 'danger'}>
                            {child.status}
                          </Badge>
                        </Td>
                        <Td align="right">{child.directChildCount}</Td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              </TableWrap>
            )}
          </div>

          {detail.data?.documents && (
            <div>
              <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
                {t('KYC documents')}
              </p>
              <p className="mb-2 text-xs text-ink3">
                {t('NIC ending {{last4}}. Opening a document is authorised and written to the access log before any bytes are sent.', {
                  last4: detail.data.documents.nicLast4 ?? '••••',
                })}
              </p>
              {canViewDocuments ? (
                <div className="flex flex-wrap gap-2">
                  {detail.data.documents.nicDocumentId && (
                    <Button
                      size="sm"
                      onClick={() => setViewingDocument(detail.data!.documents!.nicDocumentId!)}
                    >
                      {t('View NIC scan')}
                    </Button>
                  )}
                  {detail.data.documents.slipDocumentId && (
                    <Button
                      size="sm"
                      onClick={() => setViewingDocument(detail.data!.documents!.slipDocumentId!)}
                    >
                      {t('View bank slip')}
                    </Button>
                  )}
                </div>
              ) : (
                <p className="text-xs text-ink3">
                  {t('Only a KYC reviewer may open these.')}
                </p>
              )}
            </div>
          )}
        </div>
      )}

      {viewingDocument && (
        <DocumentViewer
          documentId={viewingDocument}
          subjectUserId={detail.data?.distributor?.userId}
          onClose={() => setViewingDocument(null)}
        />
      )}
    </Modal>
  );
}

function Row({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <dt className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">{label}</dt>
      <dd className="text-ink2">{value}</dd>
      {hint && <dd className="font-mono text-[11px] text-ink3">{hint}</dd>}
    </div>
  );
}

/** Two steps: authorise (logged), then redeem a single-use 60-second token. */
function DocumentViewer({
  documentId,
  subjectUserId,
  onClose,
}: {
  documentId: string;
  subjectUserId?: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [state, setState] = useState<{ url?: string; type?: string; error?: unknown }>({});

  useEffect(() => {
    let objectUrl: string | undefined;
    let cancelled = false;

    void (async () => {
      try {
        const served = await fetchDocumentObjectUrl(documentId, subjectUserId);
        if (cancelled) {
          URL.revokeObjectURL(served.objectUrl);
          return;
        }
        objectUrl = served.objectUrl;
        setState({ url: served.objectUrl, type: served.contentType });
      } catch (error) {
        if (!cancelled) setState({ error });
      }
    })();

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [documentId, subjectUserId]);

  return (
    <Modal title={t('Document')} onClose={onClose} wide>
      <p className="mb-3 text-xs text-ink3">
        {t('This view was authorised and logged before the image was fetched. The link works once and expires in 60 seconds.')}
      </p>
      {state.error ? (
        <ErrorBanner error={state.error} />
      ) : !state.url ? (
        <Spinner label={t('Requesting access…')} />
      ) : state.type === 'application/pdf' ? (
        <a className="text-sm text-brand underline" href={state.url} download="document.pdf">
          {t('Download (PDF)')}
        </a>
      ) : (
        <img src={state.url} alt={t('Document')} className="max-h-[70vh] w-full object-contain" />
      )}
    </Modal>
  );
}
