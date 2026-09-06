import { useTranslation } from 'react-i18next';
import { Link, Navigate } from 'react-router-dom';
import { usePortalMe, usePortalReferrals, type DistributorNode } from '../api/portal';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  PageHeader,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';
import { REFERRAL_STAGES } from '../lib/stages';

/**
 * The four screens a distributor gets once their registration is approved.
 *
 * Every one of them reads the same `/portal/me` response, which is served behind the gate — so
 * none of these components can accidentally show something the server would have withheld.
 */

// ================================================================== dashboard

export function PortalDashboard() {
  const { t } = useTranslation();
  const me = usePortalMe();

  // Until the registration is approved there is exactly one thing to do, so land on it.
  //
  // This page used to render for everybody. Somebody who had just created an account arrived at a
  // dashboard of em-dashes — no Business ID, no stages, no referrals — with the one action they
  // needed behind a link in the navigation. The page was not wrong, it was answering a question
  // they could not yet ask.
  //
  // The redirect waits for the first load rather than guessing: sending an approved customer to
  // the registration page for half a second, every time they open the portal, is worse than a
  // spinner.
  if (me.isLoading) {
    return <Spinner />;
  }
  if (me.data && me.data.access !== 'ACTIVE') {
    return <Navigate to="/portal/registration" replace />;
  }

  const stages = me.data?.stages;
  const completed = stages?.stagesCompleted ?? 0;
  const total = stages?.totalStages ?? REFERRAL_STAGES;
  const children = me.data?.children ?? [];

  return (
    <>
      <PageHeader
        title={t('Welcome, {{name}}', { name: me.data?.fullName ?? '' })}
        description={t('Your account at a glance.')}
      />

      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label={t('Business ID')}
          value={me.data?.businessId ?? '—'}
          mono
          hint={t('Give this to anyone you refer')}
        />
        <StatCard label={t('Level')} value={`${completed} / ${total}`} hint={t('One per referral')} />
        <StatCard
          label={t('Direct referrals')}
          value={String(children.length)}
          hint={t('{{left}} places left', { left: Math.max(0, REFERRAL_STAGES - children.length) })}
        />
        <StatCard
          label={t('Member since')}
          value={me.data?.joinedAt ? new Date(me.data.joinedAt).toLocaleDateString() : '—'}
          hint={
            me.data?.approvedAt
              ? t('Approved {{date}}', {
                  date: new Date(me.data.approvedAt).toLocaleDateString(),
                })
              : undefined
          }
        />
      </div>

      <RewardCard />

      <div className="mt-5 grid gap-5 lg:grid-cols-2">
        <Card title={t('Your progress')}>
          <div className="p-5">
            <StageLadder completed={completed} total={total} bonus={stages?.bonusStageEligible} />
            <Link to="/portal/stages">
              <Button size="sm" className="mt-4">
                {t('See what each level means')}
              </Button>
            </Link>
          </div>
        </Card>

        <Card title={t('Who referred you')}>
          <div className="p-5">
            {me.data?.parent ? (
              <>
                <p className="font-mono text-sm text-ink">{me.data.parent.businessId}</p>
                <p className="text-sm text-ink2">{me.data.parent.fullName}</p>
              </>
            ) : (
              <p className="text-sm text-ink2">
                {t('Nobody — you are at the top of your own tree.')}
              </p>
            )}
          </div>
        </Card>
      </div>
    </>
  );
}

function StatCard({
  label,
  value,
  hint,
  mono,
}: {
  label: string;
  value: string;
  hint?: string;
  mono?: boolean;
}) {
  return (
    <div className="rounded-xl border border-rule bg-panel p-4 shadow-card">
      <p className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">{label}</p>
      <p
        className={
          'mt-1 text-xl font-bold text-ink ' + (mono ? 'font-mono text-lg text-brand' : 'nums')
        }
      >
        {value}
      </p>
      {hint && <p className="mt-0.5 text-[11px] text-ink3">{hint}</p>}
    </div>
  );
}

// ================================================================== my details

export function PortalDetails() {
  const { t } = useTranslation();
  const me = usePortalMe();

  return (
    <>
      <PageHeader title={t('My details')} description={t('What we hold about you.')} />

      <Card title={t('Account')}>
        <dl className="grid gap-x-8 gap-y-4 p-5 sm:grid-cols-2">
          <Detail label={t('Name')} value={me.data?.fullName} />
          <Detail label={t('Business ID')} value={me.data?.businessId} mono />
          <Detail label={t('Email')} value={me.data?.email} />
          <Detail label={t('Mobile')} value={me.data?.mobile ?? '—'} />
          <Detail
            label={t('Account created')}
            value={me.data?.joinedAt ? new Date(me.data.joinedAt).toLocaleString() : '—'}
          />
          <Detail
            label={t('Approved as a customer')}
            value={me.data?.approvedAt ? new Date(me.data.approvedAt).toLocaleString() : '—'}
          />
          <Detail
            label={t('Registered with NIC ending')}
            value={me.data?.registration?.nicLast4 ? `••••${me.data.registration.nicLast4}` : '—'}
          />
          <Detail
            label={t('Referred by')}
            value={me.data?.registration?.referrerBusinessId ?? '—'}
            mono
          />
        </dl>
      </Card>

      <p className="mt-4 max-w-2xl text-xs text-ink3">
        {t('To change any of this, contact the office. Your NIC and bank details are held encrypted and are not shown here.')}
      </p>
    </>
  );
}

function Detail({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  return (
    <div>
      <dt className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">{label}</dt>
      <dd className={'mt-0.5 text-sm text-ink ' + (mono ? 'font-mono' : '')}>{value || '—'}</dd>
    </div>
  );
}

// ================================================================== stages

export function PortalStages() {
  const { t } = useTranslation();
  const me = usePortalMe();

  const stages = me.data?.stages;
  const completed = stages?.stagesCompleted ?? 0;
  const total = stages?.totalStages ?? REFERRAL_STAGES;
  const children = me.data?.children ?? [];

  return (
    <>
      <PageHeader
        title={t('My stages')}
        description={t('One level unlocks for each customer you refer, up to four.')}
      />

      <Card title={t('Level {{completed}} of {{total}}', { completed, total })}>
        <div className="p-5">
          <StageLadder completed={completed} total={total} bonus={stages?.bonusStageEligible} large />

          <div className="mt-6 flex flex-col gap-3">
            {Array.from({ length: total }, (_, index) => {
              const level = index + 1;
              const unlocked = level <= completed;
              const referral = children[index];
              return (
                <div
                  key={level}
                  className={
                    'flex flex-wrap items-center gap-3 rounded-lg border p-3 ' +
                    (unlocked ? 'border-ok bg-oksoft' : 'border-rule bg-panel2')
                  }
                >
                  <span
                    className={
                      'flex h-8 w-8 flex-none items-center justify-center rounded-full text-sm font-bold ' +
                      (unlocked ? 'bg-ok text-panel' : 'bg-panel text-ink3')
                    }
                  >
                    {level}
                  </span>
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-ink">
                      {t('Level {{level}}', { level })}
                    </p>
                    <p className="text-xs text-ink2">
                      {unlocked && referral
                        ? t('Unlocked by {{name}} ({{id}})', {
                            name: referral.fullName,
                            id: referral.businessId,
                          })
                        : unlocked
                          ? t('Unlocked')
                          : t('Refer one more customer to unlock')}
                    </p>
                  </div>
                  {unlocked && <Badge tone="ok">{t('unlocked')}</Badge>}
                </div>
              );
            })}

            <div
              className={
                'flex flex-wrap items-center gap-3 rounded-lg border border-dashed p-3 ' +
                (stages?.bonusStageEligible ? 'border-brand bg-brandsoft' : 'border-rule')
              }
            >
              <span
                className={
                  'flex h-8 w-8 flex-none items-center justify-center rounded-full text-sm font-bold ' +
                  (stages?.bonusStageEligible ? 'bg-brand text-brandink' : 'bg-panel2 text-ink3')
                }
              >
                ★
              </span>
              <div className="min-w-0">
                <p className="text-sm font-semibold text-ink">{t('Bonus stage')}</p>
                <p className="text-xs text-ink2">
                  {stages?.bonusStageEligible
                    ? t('You are eligible. The office will be in touch about what it involves.')
                    : t('Unlocks when all four levels are complete.')}
                </p>
              </div>
            </div>
          </div>

          {/* Said plainly rather than implied. The mechanic exists and is tracked; what a level is
              worth has not been decided, and inventing a figure here would be a promise the
              system cannot keep. */}
          <p className="mt-5 rounded-md border border-rule bg-panel2 p-3 text-xs text-ink2">
            {t('Levels record how many customers you have referred. What each level entitles you to is set by the office and is not shown here.')}
          </p>
        </div>
      </Card>
    </>
  );
}

/** The ladder, used small on the dashboard and large on the stages page. */
function StageLadder({
  completed,
  total,
  bonus,
  large,
}: {
  completed: number;
  total: number;
  bonus?: boolean;
  large?: boolean;
}) {
  const { t } = useTranslation();
  const size = large ? 'h-3' : 'h-2';

  return (
    <div>
      <div className="flex items-center gap-1.5">
        {Array.from({ length: total }, (_, index) => (
          <span
            key={index}
            className={
              `${size} flex-1 rounded-full ` + (index < completed ? 'bg-ok' : 'bg-panel2')
            }
          />
        ))}
        <span
          aria-hidden
          className={
            `${size} w-6 flex-none rounded-full ` + (bonus ? 'bg-brand' : 'bg-panel2 opacity-60')
          }
        />
      </div>
      <p className="mt-2 text-xs text-ink2">
        {completed >= total
          ? t('All {{total}} levels complete', { total })
          : t('{{left}} more referral to reach level {{next}}', {
              left: 1,
              next: completed + 1,
            })}
      </p>
      <span className="sr-only">
        {t('{{completed}} of {{total}} levels unlocked', { completed, total })}
      </span>
    </div>
  );
}

// ================================================================== referrals

export function PortalReferrals() {
  const { t } = useTranslation();
  const me = usePortalMe();
  const referrals = usePortalReferrals(me.data?.access === 'ACTIVE');

  const children = referrals.data ?? me.data?.children ?? [];
  const capacity = 4;

  return (
    <>
      <PageHeader
        title={t('My referrals')}
        description={t('Who referred you, and who you have referred. You can see one level in each direction.')}
      />

      <Card title={t('Your position')}>
        <div className="p-6">
          {referrals.isLoading ? (
            <Spinner />
          ) : referrals.error ? (
            <ErrorBanner error={referrals.error} />
          ) : (
            <ReferralDiagram
              parent={me.data?.parent ?? null}
              self={{
                businessId: me.data?.businessId ?? '',
                fullName: me.data?.fullName ?? '',
              }}
              children={children}
              capacity={capacity}
            />
          )}
        </div>
      </Card>

      <Card className="mt-5" title={t('Your direct referrals')}>
        {children.length === 0 ? (
          <EmptyState
            message={t('Nobody yet.')}
            hint={t('Share your Business ID — each person who registers with it unlocks a level.')}
          />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Business ID')}</Th>
                  <Th>{t('Name')}</Th>
                  <Th>{t('Joined')}</Th>
                  <Th align="right">{t('Their referrals')}</Th>
                </tr>
              </thead>
              <tbody>
                {children.map((child) => (
                  <tr key={child.id}>
                    <Td className="font-mono text-xs text-brand">{child.businessId}</Td>
                    <Td className="text-ink">{child.fullName}</Td>
                    <Td className="text-xs">
                      {child.approvedAt ? new Date(child.approvedAt).toLocaleDateString() : '—'}
                    </Td>
                    {/* A count, not a link. How their downline is doing is their business. */}
                    <Td align="right">{child.directChildCount}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>
    </>
  );
}

/**
 * Three rows: who referred you, you, and who you referred.
 *
 * Drawn rather than listed because the shape is the information — a distributor wants to see how
 * many of their four places are filled, and a row of cards with visible gaps says that instantly
 * where a table does not. Empty places are drawn as dashed outlines for the same reason.
 */
function ReferralDiagram({
  parent,
  self,
  children,
  capacity,
}: {
  parent: DistributorNode | null;
  self: { businessId: string; fullName: string };
  children: DistributorNode[];
  capacity: number;
}) {
  const { t } = useTranslation();
  const empty = Math.max(0, capacity - children.length);

  return (
    <div className="flex flex-col items-center gap-0">
      {parent ? (
        <>
          <NodeCard
            label={t('Referred you')}
            businessId={parent.businessId ?? ''}
            name={parent.fullName ?? ''}
            tone="muted"
          />
          <Connector />
        </>
      ) : (
        <p className="mb-3 text-xs text-ink3">{t('You are at the top of your own tree')}</p>
      )}

      <NodeCard label={t('You')} businessId={self.businessId} name={self.fullName} tone="self" />

      {(children.length > 0 || empty > 0) && <Connector />}

      <div className="flex flex-wrap justify-center gap-3">
        {children.map((child) => (
          <NodeCard
            key={child.id}
            businessId={child.businessId ?? ''}
            name={child.fullName ?? ''}
            tone="child"
          />
        ))}
        {Array.from({ length: empty }, (_, index) => (
          <div
            key={`empty-${index}`}
            className="flex min-w-[150px] flex-col items-center justify-center rounded-lg border border-dashed border-rule px-4 py-3 text-center"
          >
            <p className="text-xs text-ink3">{t('Place {{n}} open', { n: children.length + index + 1 })}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

function Connector() {
  return <span aria-hidden className="my-1 h-5 w-px bg-rule" />;
}

function NodeCard({
  label,
  businessId,
  name,
  tone,
}: {
  label?: string;
  businessId: string;
  name: string;
  tone: 'muted' | 'self' | 'child';
}) {
  const styles = {
    muted: 'border-rule bg-panel2',
    self: 'border-brand bg-brandsoft ring-2 ring-brand/20',
    child: 'border-ok bg-oksoft',
  }[tone];

  return (
    <div className={`min-w-[150px] rounded-lg border px-4 py-3 text-center ${styles}`}>
      {label && (
        <p className="text-[9.5px] font-semibold tracking-wider text-ink3 uppercase">{label}</p>
      )}
      <p className="font-mono text-xs font-semibold text-ink">{businessId || '—'}</p>
      <p className="truncate text-xs text-ink2">{name}</p>
    </div>
  );
}

/**
 * The item pack, once it has been earned.
 *
 * Deliberately absent until there is something to say. A card reading "you have no reward yet" on
 * somebody's first day is a reminder that they have not achieved anything, which is not what a
 * dashboard is for — the stage ladder below already shows how far along they are.
 *
 * Two states, and the difference matters to the reader: **yours to collect** means an
 * administrator still has to hand it over, and **collected** records that they have.
 */
function RewardCard() {
  const { t } = useTranslation();
  const me = usePortalMe();
  const reward = me.data?.reward;

  if (!reward) {
    return null;
  }

  const issued = reward.status === 'issued';

  return (
    <div
      className={
        'mt-5 rounded-lg border p-5 ' +
        (issued ? 'border-ok bg-oksoft' : 'border-brand bg-brandsoft')
      }
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-[11px] tracking-wide text-ink3 uppercase">
            {issued ? t('Your item pack') : t('You have earned your item pack')}
          </p>
          <p className="mt-1 text-lg font-semibold text-ink">
            {reward.itemSetName}
            <span className="ml-2 font-mono text-xs text-ink3">{reward.itemSetCode}</span>
          </p>
          <p className="mt-1 text-sm text-ink2">
            {issued
              ? t('Issued on {{when}} from {{store}}.', {
                  when: reward.issuedAt ? new Date(reward.issuedAt).toLocaleString() : '—',
                  store: reward.issuedFromStore ?? '—',
                })
              : t('All four stages are complete. An administrator will hand it over — there is nothing to pay and nothing for you to do.')}
          </p>
        </div>
        <span
          className={
            'rounded-full border px-2.5 py-1 text-[11px] font-medium ' +
            (issued ? 'border-ok text-ok' : 'border-brand text-brand')
          }
        >
          {issued ? t('collected') : t('ready to collect')}
        </span>
      </div>
    </div>
  );
}
