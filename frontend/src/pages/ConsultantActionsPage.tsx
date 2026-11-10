import { useEffect, useMemo, useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  ApiError,
  data,
  detailOf,
  transactions,
  type CaptureLists,
  type ClientSummary,
  type ConsultantSummary,
  type NewTransaction,
  type Product,
  type SavedTransaction,
} from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import RevealIdNumber from '../components/RevealIdNumber';
import './capture.css';

const MONEY = /^\d{1,10}(\.\d{1,2})?$/;


function parseMoney(text: string): number | 'bad' | null {
  const t = text.trim().replace(/\s/g, '');
  if (t === '') return null;
  if (!MONEY.test(t)) return 'bad';
  const [whole = '0', fraction = ''] = t.split('.');
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
}

const rand = (cents: number) =>
  (cents / 100).toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR' });

const pad = (n: number) => String(n).padStart(2, '0');
function todayText() {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
const weekday = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString('en-ZA', { weekday: 'long' });

const GROUPS = [
  {
    title: 'Core services',
    codes: ['COMBO', 'IVC', 'MAFORI_VOUCHER', 'CREDIT_RESCUE', 'SPEED_SERVICE'],
  },
  { title: 'Insurance and cards', codes: ['INSURANCE', 'MAFORI_CARD', 'MAFORI_WELLNESS'] },
  {
    title: 'Credit services',
    codes: ['CREDIT_REPAIR', 'CREDIT_REPAIR_DEBIT_ORDER', 'POLICY_REPLACEMENT'],
  },
  {
    title: 'Cash and housing',
    codes: ['CASH_ADVANCE_LITE', 'GROCERY', 'HOUSING_ADMIN', 'VOLUNTARY_DISTRIBUTION'],
  },
];


function groupProducts(products: Product[]) {
  const used = new Set<number>();
  const groups = GROUPS.map((g) => {
    const items = g.codes.flatMap((code) => products.filter((p) => p.code === code));
    items.forEach((p) => used.add(p.id));
    return { title: g.title, items };
  });
  const rest = products.filter((p) => !used.has(p.id));
  if (rest.length > 0) groups.push({ title: 'Other products', items: rest });
  return groups.filter((g) => g.items.length > 0);
}

type Errors = Partial<Record<string, string>>;

interface Extras {
  amountToClient: string;
  thirdPartySettlement: string;
  paymentTermId: string;
  employerId: string;
  structureId: string;
  primaryList: string;
  primaryLoan: string;
  secondaryList: string;
  secondaryLoan: string;
}

const NO_EXTRAS: Extras = {
  amountToClient: '',
  thirdPartySettlement: '',
  paymentTermId: '',
  employerId: '',
  structureId: '',
  primaryList: '',
  primaryLoan: '',
  secondaryList: '',
  secondaryLoan: '',
};


function ClientPicker({ onPick }: { onPick: (c: ClientSummary) => void }) {
  const [text, setText] = useState('');
  const [results, setResults] = useState<ClientSummary[]>([]);
  const [state, setState] = useState<'idle' | 'searching' | 'none' | 'failed'>('idle');

  const query = text.trim();
  const digits = query.replace(/[\s-]/g, '');
  const byId = /^\d{13}$/.test(digits);

  useEffect(() => {
    if (query.length < 2) return;
    let live = true;
    const timer = setTimeout(() => {
      const run = byId
        ? transactions.lookupClient(digits).then((c) => [c])
        : data.listClientsPage({ search: query }, 0, 8).then((p) => p.items);
      run
        .then((items) => {
          if (!live) return;
          setResults(items);
          setState(items.length > 0 ? 'idle' : 'none');
        })
        .catch((err: unknown) => {
          if (!live) return;
          if (byId && err instanceof ApiError && err.status === 404) {
            setResults([]);
            setState('none');
          } else {
            setState('failed');
          }
        });
    }, 300);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [query, byId, digits]);

  const onChange = (e: ChangeEvent<HTMLInputElement>) => {
    setText(e.target.value);
    setState(e.target.value.trim().length >= 2 ? 'searching' : 'idle');
  };

  const pick = (c: ClientSummary) => {
    setText('');
    setResults([]);
    setState('idle');
    onPick(c);
  };

  const showing = query.length >= 2;
  return (
    <div className="ca-picker">
      <label htmlFor="ca-search">Find a client</label>
      <input
        id="ca-search"
        type="search"
        autoComplete="off"
        spellCheck={false}
        placeholder="First name, surname or ID number"
        value={text}
        onChange={onChange}
      />
      {showing && state === 'searching' && <p className="ca-hint">Searching...</p>}
      {showing && state === 'none' && (
        <p className="ca-hint">No client found. Clients are added on the Clients page.</p>
      )}
      {showing && state === 'failed' && (
        <p className="ca-err" role="alert">
          The search failed. Try again.
        </p>
      )}
      {showing && state === 'idle' && results.length > 0 && (
        <ul className="ca-results">
          {results.map((c) => (
            <li key={c.id}>
              <button type="button" onClick={() => pick(c)}>
                <strong>
                  {c.firstName} {c.lastName}
                </strong>
                <span>{c.idNumberMasked}</span>
                <span className="muted">{c.consultant?.fullName ?? 'No consultant'}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function ConsultantActionsPage() {
  const session = useSession();
  const manager = hasAnyRole(session, ['ADMIN', 'MANAGER']);
  const today = todayText();

  const [load, setLoad] = useState<
    { kind: 'loading' } | { kind: 'failed' } | { kind: 'ready'; lists: CaptureLists }
  >({ kind: 'loading' });
  const [staff, setStaff] = useState<ConsultantSummary[]>([]);

  const [client, setClient] = useState<ClientSummary | null>(null);
  const [date, setDate] = useState(today);
  const [amounts, setAmounts] = useState<Partial<Record<number, string>>>({});
  const [extras, setExtras] = useState<Extras>(NO_EXTRAS);
  const [consultantId, setConsultantId] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<SavedTransaction | null>(null);
  const [key, setKey] = useState(() => crypto.randomUUID());

  useEffect(() => {
    let live = true;
    transactions
      .lists()
      .then((lists) => live && setLoad({ kind: 'ready', lists }))
      .catch(() => live && setLoad({ kind: 'failed' }));
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    if (!manager) return;
    let live = true;
    data
      .listConsultants()
      .then((c) => live && setStaff(c))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [manager]);

  const products = load.kind === 'ready' ? load.lists.products : [];
  const groups = useMemo(() => groupProducts(products), [products]);


  const lines = products.flatMap((p) => {
    const cents = parseMoney(amounts[p.id] ?? '');
    return typeof cents === 'number' && cents > 0 ? [{ product: p, cents }] : [];
  });
  const sum = (pick: (p: Product) => boolean) =>
    lines.filter((l) => pick(l.product)).reduce((s, l) => s + l.cents, 0);
  const toClient = parseMoney(extras.amountToClient);
  const quotation = sum((p) => p.countsTowardQuotation);
  const banked = sum((p) => p.countsTowardBanked);

  const setAmount = (id: number) => (e: ChangeEvent<HTMLInputElement>) => {
    setAmounts((a) => ({ ...a, [id]: e.target.value }));
    setErrors((x) => ({ ...x, [`p-${id}`]: undefined, lines: undefined }));
  };
  const setExtra =
    (name: keyof Extras) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      setExtras((x) => ({ ...x, [name]: e.target.value }));
      setErrors((x) => ({ ...x, [name]: undefined }));
    };

  const pickClient = (c: ClientSummary) => {
    setClient(c);
    setConsultantId(c.consultant ? String(c.consultant.id) : '');
    setErrors((x) => ({ ...x, client: undefined }));
  };

  function validate(): Errors {
    const e: Errors = {};
    if (!client) e.client = 'Choose a client first.';
    if (!date) e.date = 'Choose the date of the sale.';
    else if (date > today) e.date = 'The date cannot be in the future.';
    for (const p of products) {
      const cents = parseMoney(amounts[p.id] ?? '');
      if (cents === 'bad' || cents === 0)
        e[`p-${p.id}`] = 'Enter an amount above 0, with at most two decimals.';
    }
    if (lines.length === 0 && !Object.keys(e).some((k) => k.startsWith('p-')))
      e.lines = 'Enter an amount for at least one product.';
    const money: (keyof Extras)[] = [
      'amountToClient',
      'thirdPartySettlement',
      'primaryLoan',
      'secondaryLoan',
    ];
    for (const name of money)
      if (parseMoney(extras[name]) === 'bad')
        e[name] = 'Enter an amount, with at most two decimals.';
    if (extras.primaryList.length > 500) e.primaryList = 'Up to 500 characters.';
    if (extras.secondaryList.length > 500) e.secondaryList = 'Up to 500 characters.';
    if (manager && !consultantId) e.consultant = 'Choose the consultant this sale belongs to.';
    return e;
  }

  const money = (text: string): number | null => {
    const c = parseMoney(text);
    return typeof c === 'number' ? c / 100 : null;
  };
  const id = (text: string): number | null => (text ? Number(text) : null);
  const words = (text: string): string | null => text.trim() || null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (saving || !client) return;
    setFailure(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    const body: NewTransaction = {
      clientId: client.id,
      ...(manager ? { consultantId: Number(consultantId) } : {}),
      transactionDate: date,
      lines: lines.map((l) => ({ productId: l.product.id, amount: l.cents / 100 })),
      amountToClient: money(extras.amountToClient),
      thirdPartySettlement: money(extras.thirdPartySettlement),
      paymentTermId: id(extras.paymentTermId),
      employerId: id(extras.employerId),
      structureId: id(extras.structureId),
      primaryStructureBudgetList: words(extras.primaryList),
      primaryStructureLoanAmount: money(extras.primaryLoan),
      secondaryStructureBudgetList: words(extras.secondaryList),
      secondaryStructureLoanAmount: money(extras.secondaryLoan),
    };
    setSaving(true);
    try {
      setSaved(await transactions.save(body, key));
    } catch (err) {
      setFailure(detailOf(err) ?? 'The sale could not be saved. Nothing was stored. Try again.');
    } finally {
      setSaving(false);
    }
  }

  function again(keepClient: boolean) {
    setSaved(null);
    setAmounts({});
    setExtras(NO_EXTRAS);
    setErrors({});
    setFailure(null);
    setKey(crypto.randomUUID());
    if (!keepClient) setClient(null);
  }

  if (load.kind === 'loading') return <p className="center-msg">Loading...</p>;
  if (load.kind === 'failed')
    return (
      <div className="ca">
        <h1>Consultant actions</h1>
        <p className="alert error" role="alert">
          The product list could not be loaded. Refresh and try again.
        </p>
      </div>
    );
  const { lists } = load;

  if (saved && client) {
    return (
      <div className="ca">
        <h1>Consultant actions</h1>
        <div className="ca-card ca-done" role="status">
          <h2>Sale saved</h2>
          <p>
            {client.firstName} {client.lastName}, {weekday(saved.transactionDate)}{' '}
            {saved.transactionDate}.
          </p>
          <ul className="ca-lines">
            {saved.lines.map((l) => (
              <li key={l.productId}>
                <span>{l.name}</span>
                <span>{rand(Math.round(l.amount * 100))}</span>
              </li>
            ))}
          </ul>
          <p className="ca-totals">
            <span>
              Quotation total <strong>{rand(Math.round(saved.quotationTotal * 100))}</strong>
            </span>
            <span>
              Banked total <strong>{rand(Math.round(saved.bankedTotal * 100))}</strong>
            </span>
          </p>
          <div className="ca-actions">
            <button type="button" className="btn primary" onClick={() => again(true)}>
              Capture another sale for this client
            </button>
            <button type="button" className="btn" onClick={() => again(false)}>
              Choose another client
            </button>
            <Link className="btn" to="/reports">
              View reports
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const field = (name: keyof Extras, label: string, kind: 'money' | 'text') => (
    <div className="ca-field">
      <label htmlFor={`ca-${name}`}>{label}</label>
      <input
        id={`ca-${name}`}
        type="text"
        inputMode={kind === 'money' ? 'decimal' : undefined}
        autoComplete="off"
        value={extras[name]}
        onChange={setExtra(name)}
        aria-invalid={errors[name] ? true : undefined}
      />
      {errors[name] && <p className="ca-err">{errors[name]}</p>}
    </div>
  );

  const select = (name: keyof Extras, label: string, options: { id: number; name: string }[]) => (
    <div className="ca-field">
      <label htmlFor={`ca-${name}`}>{label}</label>
      <select id={`ca-${name}`} value={extras[name]} onChange={setExtra(name)}>
        <option value="">Not set</option>
        {options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.name}
          </option>
        ))}
      </select>
    </div>
  );

  const picked = (list: { id: number; name: string }[] | null, value: string) =>
    list?.find((o) => String(o.id) === value)?.name;

  return (
    <div className="ca">
      <h1>Consultant actions</h1>
      <p className="muted">Capture a client&rsquo;s sale. It goes straight into the reports.</p>

      <form className="ca-grid" onSubmit={submit} noValidate>
        <div className="ca-main">
          {failure && (
            <p className="alert error" role="alert">
              {failure}
            </p>
          )}

          <section className="ca-card" aria-labelledby="ca-h-client">
            <h2 id="ca-h-client">1. Client</h2>
            {client ? (
              <div className="ca-client">
                <p>
                  <strong>
                    {client.firstName} {client.lastName}
                  </strong>
                  <span className="muted">
                    {' '}
                    {client.consultant ? `· ${client.consultant.fullName}` : '· No consultant'}
                  </span>
                </p>
                <RevealIdNumber clientId={client.id} masked={client.idNumberMasked} />
                <button type="button" className="btn" onClick={() => setClient(null)}>
                  Change client
                </button>
              </div>
            ) : (
              <ClientPicker onPick={pickClient} />
            )}
            {errors.client && <p className="ca-err">{errors.client}</p>}
            {manager && (
              <div className="ca-field">
                <label htmlFor="ca-consultant">Sale belongs to consultant</label>
                <select
                  id="ca-consultant"
                  value={consultantId}
                  onChange={(e) => setConsultantId(e.target.value)}
                >
                  <option value="">Choose a consultant</option>
                  {staff.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.fullName} ({c.consultantCode})
                    </option>
                  ))}
                </select>
                {errors.consultant && <p className="ca-err">{errors.consultant}</p>}
              </div>
            )}
          </section>

          <section className="ca-card" aria-labelledby="ca-h-date">
            <h2 id="ca-h-date">2. Date of the sale</h2>
            <div className="ca-field ca-date">
              <label htmlFor="ca-date">Date</label>
              <input
                id="ca-date"
                type="date"
                max={today}
                value={date}
                onChange={(e) => {
                  setDate(e.target.value);
                  setErrors((x) => ({ ...x, date: undefined }));
                }}
                aria-invalid={errors.date ? true : undefined}
              />
              {date && <span className="muted">{weekday(date)}</span>}
            </div>
            {errors.date && <p className="ca-err">{errors.date}</p>}
          </section>

          <section className="ca-card" aria-labelledby="ca-h-products">
            <h2 id="ca-h-products">3. Products</h2>
            <p className="ca-hint">Leave a product blank if it was not sold.</p>
            {errors.lines && <p className="ca-err">{errors.lines}</p>}
            {groups.map((g) => (
              <fieldset key={g.title} className="ca-group">
                <legend>{g.title}</legend>
                <div className="ca-products">
                  {g.items.map((p) => (
                    <div className="ca-field" key={p.id}>
                      <label htmlFor={`ca-p-${p.id}`}>{p.name}</label>
                      <div className="ca-money">
                        <span aria-hidden="true">R</span>
                        <input
                          id={`ca-p-${p.id}`}
                          type="text"
                          inputMode="decimal"
                          autoComplete="off"
                          placeholder={p.standardPrice !== null ? String(p.standardPrice) : '0.00'}
                          value={amounts[p.id] ?? ''}
                          onChange={setAmount(p.id)}
                          aria-invalid={errors[`p-${p.id}`] ? true : undefined}
                        />
                      </div>
                      {errors[`p-${p.id}`] && <p className="ca-err">{errors[`p-${p.id}`]}</p>}
                    </div>
                  ))}
                </div>
              </fieldset>
            ))}
          </section>

          <section className="ca-card" aria-labelledby="ca-h-extra">
            <h2 id="ca-h-extra">4. Other details</h2>
            <div className="ca-products">
              {field('amountToClient', 'Amount to client', 'money')}
              {field('thirdPartySettlement', 'Third party settlement', 'money')}
              {lists.paymentTerms &&
                lists.paymentTerms.length > 0 &&
                select('paymentTermId', 'Payment term', lists.paymentTerms)}
              {lists.employers.length > 0 && select('employerId', 'Employer', lists.employers)}
              {lists.structures &&
                lists.structures.length > 0 &&
                select('structureId', 'Structure', lists.structures)}
            </div>
            <div className="ca-products">
              {field('primaryList', 'Primary structure budget list', 'text')}
              {field('primaryLoan', 'Primary structure loan amount', 'money')}
              {field('secondaryList', 'Secondary structure budget list', 'text')}
              {field('secondaryLoan', 'Secondary structure loan amount', 'money')}
            </div>
          </section>
        </div>

        <aside className="ca-summary" aria-label="Summary">
          <h2>Summary</h2>
          <dl>
            <dt>Client</dt>
            <dd>{client ? `${client.firstName} ${client.lastName}` : 'Not chosen'}</dd>
            {client && (
              <>
                <dt>ID number</dt>
                <dd>{client.idNumberMasked}</dd>
              </>
            )}
            <dt>Date</dt>
            <dd>{date ? `${weekday(date)}, ${date}` : 'Not chosen'}</dd>
          </dl>
          {lines.length === 0 ? (
            <p className="ca-hint">No products entered yet.</p>
          ) : (
            <ul className="ca-lines">
              {lines.map((l) => (
                <li key={l.product.id}>
                  <span>{l.product.name}</span>
                  <span>{rand(l.cents)}</span>
                </li>
              ))}
            </ul>
          )}
          <dl>
            {typeof toClient === 'number' && toClient > 0 && (
              <>
                <dt>Amount to client</dt>
                <dd>{rand(toClient)}</dd>
              </>
            )}
            {picked(lists.paymentTerms, extras.paymentTermId) && (
              <>
                <dt>Payment term</dt>
                <dd>{picked(lists.paymentTerms, extras.paymentTermId)}</dd>
              </>
            )}
            {picked(lists.employers, extras.employerId) && (
              <>
                <dt>Employer</dt>
                <dd>{picked(lists.employers, extras.employerId)}</dd>
              </>
            )}
          </dl>
          <p className="ca-totals">
            <span>
              Quotation total <strong>{rand(quotation)}</strong>
            </span>
            <span>
              Banked total <strong>{rand(banked)}</strong>
            </span>
          </p>
          <p className="ca-hint">The server checks these totals when you submit.</p>
          <button type="submit" className="btn primary ca-submit" disabled={saving}>
            {saving ? 'Saving...' : 'Submit transaction'}
          </button>
        </aside>
      </form>
    </div>
  );
}
