import { useEffect, useState } from 'react';
import {
  data,
  reports,
  type ConsultantSummary,
  type RangeBudgetResponse,
  type RangeConsultantsResponse,
  type RangeProductsResponse,
  type RangeSummaryResponse,
} from '../api';
import {
  compact,
  DAY_NAMES,
  day,
  Failed,
  KpiRow,
  periodLabel,
  rand,
  useReport,
} from './reportShared';

/**
 * Reports for a month or any date range (GET /api/reports/range/...).
 * Every sale counts on its own date, so a week that runs from September into
 * October is split between the two months.
 */

export type RangeTab = 'summary' | 'consultants' | 'products' | 'budget';

const shortDate = (iso: string) =>
  new Date(iso + 'T00:00:00').toLocaleDateString('en-ZA', { day: 'numeric', month: 'short' });

export function RangeKpis({ r, label }: { r: RangeSummaryResponse; label: string }) {
  const own = r.scope === 'OWN';
  const t = r.totals;
  return (
    <KpiRow
      items={[
        { label: 'Quotation', value: rand(t.quotationTotal), note: `Quoted, ${label}` },
        { label: 'Banked', value: rand(t.bankedTotal), note: `Banked, ${label}` },
        {
          label: 'Sales',
          value: t.transactionCount.toLocaleString('en-ZA'),
          note: own ? `Your sales, ${label}` : `Sales, ${label}`,
        },
        own || t.daysJudged === null || t.daysWithSales === 0
          ? {
              label: 'Clients',
              value: t.clientCount.toLocaleString('en-ZA'),
              note: `Clients, ${label}`,
            }
          : t.daysJudged === 0 || t.daysTargetMet === null
            ? {
                label: 'Daily target',
                value: 'Not configured',
                note: 'No daily target was set for the days in this period',
              }
            : {
                label: 'Daily target met',
                value: `${t.daysTargetMet} of ${t.daysJudged}`,
                note:
                  t.daysJudged < t.daysWithSales
                    ? `Days with sales and a target that reached it (${t.daysWithSales - t.daysJudged} day(s) had no target)`
                    : 'Days with sales that reached that day’s target',
              },
      ]}
    />
  );
}

/** One bar pair per day for up to a month, otherwise one per week. */
export function RangeChart({ r }: { r: RangeSummaryResponse }) {
  const byDay = r.days.length <= 31;
  const items = byDay
    ? r.days.map((d) => ({
        key: d.date,
        label: r.days.length <= 7 ? DAY_NAMES[d.dayOfWeek - 1] : String(Number(d.date.slice(8))),
        title: day(d.date),
        quotation: d.quotationTotal,
        banked: d.bankedTotal,
      }))
    : r.weeks.map((w) => ({
        key: w.weekStarting,
        label: shortDate(w.from),
        title: `${periodLabel(w.from, w.to)}`,
        quotation: w.quotationTotal,
        banked: w.bankedTotal,
      }));
  const top = Math.max(1, ...items.flatMap((d) => [d.quotation, d.banked]));
  const showValues = items.length <= 7;
  return (
    <figure className="rp-card rp-daily">
      <figcaption className="rp-card-head">
        <div>
          <h2>{byDay ? 'Daily' : 'Weekly'} quotations and banked</h2>
          <p className="muted">{periodLabel(r.from, r.to)} · exact figures in the summary below</p>
        </div>
        <ul className="rp-legend">
          <li>
            <span className="rp-swatch banked" /> Banked
          </li>
          <li>
            <span className="rp-swatch quotation" /> Quotation
          </li>
        </ul>
      </figcaption>
      <div
        className={`rp-cols ${showValues ? '' : 'dense'}`}
        style={{ gridTemplateColumns: `repeat(${items.length}, minmax(0, 1fr))` }}
        role="img"
        aria-label={`${byDay ? 'Daily' : 'Weekly'} banked and quotation amounts`}
      >
        {items.map((d) => {
          const empty = d.quotation === 0 && d.banked === 0;
          return (
            <div
              key={d.key}
              className="rp-col"
              title={`${d.title}: quotation ${rand(d.quotation)}, banked ${rand(d.banked)}`}
            >
              {showValues && (
                <div className="rp-col-vals" aria-hidden="true">
                  {empty ? (
                    <span>R0</span>
                  ) : (
                    <>
                      <span>{compact(d.banked)}</span>
                      <span>{compact(d.quotation)}</span>
                    </>
                  )}
                </div>
              )}
              <div className={`rp-col-bars ${empty ? 'empty' : ''}`}>
                {empty ? (
                  <>
                    <span className="rp-colbar ghost" />
                    <span className="rp-colbar ghost" />
                  </>
                ) : (
                  <>
                    <span
                      className="rp-colbar banked"
                      style={{ height: `${(d.banked / top) * 100}%` }}
                    />
                    <span
                      className="rp-colbar quotation"
                      style={{ height: `${(d.quotation / top) * 100}%` }}
                    />
                  </>
                )}
              </div>
              <span className="rp-col-day">{d.label}</span>
            </div>
          );
        })}
      </div>
      <p className="rp-foot">
        {showValues
          ? 'Chart labels rounded to the nearest R1k'
          : 'Point at a bar to see its exact figures'}
      </p>
    </figure>
  );
}

function weekResult(w: RangeSummaryResponse['weeks'][number]) {
  if (w.weeklyTarget === null) return <span className="muted">Not configured</span>;
  if (!w.wholeWeek)
    return (
      <span className="muted" title="Only part of this week is in the period">
        Part week, not judged
      </span>
    );
  return (
    <span className={w.targetMet ? 'rp-yes' : 'rp-no'}>{w.targetMet ? 'Met' : 'Not met'}</span>
  );
}

export function RangeSummary({ r }: { r: RangeSummaryResponse }) {
  const own = r.scope === 'OWN';
  const longPeriod = r.days.length > 31;
  const days = (
    <table className="rp-table">
      <thead>
        <tr>
          <th>Day</th>
          <th>Date</th>
          <th>Sales</th>
          <th>Clients</th>
          <th>Quotation</th>
          <th>Banked</th>
          {!own && <th>Daily target</th>}
        </tr>
      </thead>
      <tbody>
        {r.days.map((d) => (
          <tr key={d.date}>
            <td>{DAY_NAMES[d.dayOfWeek - 1]}</td>
            <td>{day(d.date)}</td>
            <td>{d.transactionCount}</td>
            <td>{d.clientCount}</td>
            <td className="nowrap">{rand(d.quotationTotal)}</td>
            <td className="nowrap">{rand(d.bankedTotal)}</td>
            {!own && (
              <td>
                {d.dailyTarget === null ? (
                  <span className="muted">Not configured</span>
                ) : d.transactionCount === 0 ? (
                  <span className="muted">No sales</span>
                ) : (
                  <span
                    className={d.targetMet ? 'rp-yes' : 'rp-no'}
                    title={`Target that day: ${rand(d.dailyTarget)}`}
                  >
                    {d.targetMet ? 'Met' : 'Not met'}
                  </span>
                )}
              </td>
            )}
          </tr>
        ))}
      </tbody>
    </table>
  );
  return (
    <>
      <div className="rp-card-head">
        <div>
          <h2>Summary</h2>
          <p className="muted">{periodLabel(r.from, r.to)}. Each sale counts on its own date.</p>
        </div>
      </div>
      {!own && r.totals.unattributedCount ? (
        <p className="rp-warn">
          {r.totals.unattributedCount} sale{r.totals.unattributedCount === 1 ? ' has' : 's have'} no
          consultant in the register.
        </p>
      ) : null}

      <h3 className="rp-sub">By week</h3>
      <div className="table-wrap">
        <table className="rp-table">
          <thead>
            <tr>
              <th>Week of</th>
              <th>Days in this period</th>
              <th>Sales</th>
              <th>Quotation</th>
              <th>Banked</th>
              {!own && <th>Target for that week</th>}
              {!own && <th>Result</th>}
            </tr>
          </thead>
          <tbody>
            {r.weeks.map((w) => (
              <tr key={w.weekStarting}>
                <td>{day(w.weekStarting)}</td>
                <td>{w.wholeWeek ? 'Whole week' : periodLabel(w.from, w.to)}</td>
                <td>{w.transactionCount}</td>
                <td className="nowrap">{rand(w.quotationTotal)}</td>
                <td className="nowrap">{rand(w.bankedTotal)}</td>
                {!own && (
                  <td className="nowrap">
                    {w.weeklyTarget === null ? 'Not configured' : rand(w.weeklyTarget)}
                  </td>
                )}
                {!own && <td>{weekResult(w)}</td>}
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th colSpan={2}>Period</th>
              <th>{r.totals.transactionCount}</th>
              <th className="nowrap">{rand(r.totals.quotationTotal)}</th>
              <th className="nowrap">{rand(r.totals.bankedTotal)}</th>
              {!own && <th />}
              {!own && <th />}
            </tr>
          </tfoot>
        </table>
      </div>
      {!own && (
        <p className="rp-foot">
          Each week is measured against the target that was in force that week. A week that is only
          partly in the period is not judged against a full week’s target.
        </p>
      )}

      <h3 className="rp-sub">By day</h3>
      {longPeriod ? (
        <details className="rp-details">
          <summary>Show all {r.days.length} days</summary>
          <div className="table-wrap">{days}</div>
        </details>
      ) : (
        <div className="table-wrap">{days}</div>
      )}
      <p className="rp-foot">
        Clients are counted once per period, so the period figure ({r.totals.clientCount}) can be
        lower than the sum of the days.
      </p>
    </>
  );
}

export function RangeConsultants({ from, to }: { from: string; to: string }) {
  const { value: r, error } = useReport<RangeConsultantsResponse>(
    () => reports.rangeConsultants(from, to),
    [from, to],
  );
  if (error) return <Failed message={error} />;
  if (!r) return <p className="muted">Loading...</p>;
  if (r.consultants.length === 0)
    return <p className="muted">Nobody sold anything in this period.</p>;
  return (
    <div className="table-wrap">
      <table className="rp-table">
        <thead>
          <tr>
            <th>Consultant</th>
            <th className="num">Sales</th>
            <th className="num">Clients</th>
            <th className="num">Quotation</th>
            <th className="num">Banked</th>
          </tr>
        </thead>
        <tbody>
          {r.consultants.map((c) => (
            <tr key={c.consultant?.id ?? 'none'}>
              <td>
                {c.consultant ? (
                  <>
                    {c.consultant.fullName}{' '}
                    <span className="muted mono">{c.consultant.consultantCode}</span>
                  </>
                ) : (
                  <span className="muted">No consultant</span>
                )}
              </td>
              <td className="num">{c.transactionCount}</td>
              <td className="num">{c.clientCount}</td>
              <td className="num nowrap">{rand(c.quotationTotal)}</td>
              <td className="num nowrap">{rand(c.bankedTotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function RangeProducts({
  from,
  to,
  manager,
  consultantId,
  setConsultantId,
}: {
  from: string;
  to: string;
  manager: boolean;
  consultantId: string;
  setConsultantId: (id: string) => void;
}) {
  const [people, setPeople] = useState<ConsultantSummary[]>([]);
  useEffect(() => {
    if (!manager) return;
    let live = true;
    data
      .listConsultants()
      .then((r) => live && setPeople(r))
      .catch(() => undefined); // the filter is optional
    return () => {
      live = false;
    };
  }, [manager]);
  const { value: r, error } = useReport<RangeProductsResponse>(
    () => reports.rangeProducts(from, to, consultantId ? Number(consultantId) : undefined),
    [from, to, consultantId],
  );
  return (
    <>
      {manager && (
        <div className="rp-pick">
          <label htmlFor="rp-range-consultant">Consultant</label>
          <select
            id="rp-range-consultant"
            value={consultantId}
            onChange={(e) => setConsultantId(e.target.value)}
          >
            <option value="">Everyone</option>
            {people.map((c) => (
              <option key={c.id} value={c.id}>
                {c.fullName}
              </option>
            ))}
          </select>
        </div>
      )}
      {error && <Failed message={error} />}
      {!error && !r && <p className="muted">Loading...</p>}
      {r && (
        <>
          <div className="table-wrap">
            <table className="rp-table">
              <thead>
                <tr>
                  <th>Product</th>
                  <th className="num">Lines</th>
                  <th className="num">Amount</th>
                </tr>
              </thead>
              <tbody>
                {r.products.map((p) => (
                  <tr key={p.product.code}>
                    <td>{p.product.name}</td>
                    <td className={`num ${p.lineCount === 0 ? 'muted' : ''}`}>{p.lineCount}</td>
                    <td className={`num nowrap ${p.totalAmount === 0 ? 'muted' : ''}`}>
                      {rand(p.totalAmount)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted">
            Product amounts are not the same as quotation or banked, because some products count
            toward only one of them.
          </p>
        </>
      )}
    </>
  );
}

export function RangeBudget({ from, to }: { from: string; to: string }) {
  const { value: r, error } = useReport<RangeBudgetResponse>(
    () => reports.rangeBudget(from, to),
    [from, to],
  );
  if (error) return <Failed message={error} />;
  if (!r) return <p className="muted">Loading...</p>;
  if (r.structures.length === 0)
    return <p className="muted">No budget structures in this period.</p>;
  return (
    <div className="table-wrap">
      <table className="rp-table">
        <thead>
          <tr>
            <th>Budget structure</th>
            <th className="num">Entries</th>
            <th className="num">Loan amount</th>
          </tr>
        </thead>
        <tbody>
          {r.structures.map((s) => (
            <tr key={s.budgetStructure}>
              <td>{s.budgetStructure}</td>
              <td className="num">{s.entryCount}</td>
              <td className="num nowrap">{rand(s.loanAmountTotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
