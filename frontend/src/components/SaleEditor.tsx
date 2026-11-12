import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react';
import { detailOf, transactions, type CaptureLists, type SaleDetail } from '../api';

const MONEY = /^\d{1,10}(\.\d{1,2})?$/;

const pad = (n: number) => String(n).padStart(2, '0');

function todayText() {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

interface Line {
  productId: number;
  name: string;
  amount: string;
}

interface Props {
  detail: SaleDetail;
  lists: CaptureLists;
  onSaved: () => void;
  onCancel: () => void;
}

function EditForm({ detail, lists, onSaved, onCancel }: Props) {
  const [date, setDate] = useState(detail.transactionDate);
  const [lines, setLines] = useState<Line[]>(() =>
    detail.lines.map((l) => ({
      productId: l.productId,
      name: l.name,
      amount: l.amount.toFixed(2),
    })),
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const addable = lists.products.filter((p) => !lines.some((l) => l.productId === p.id));

  function setAmount(productId: number, amount: string) {
    setLines((all) => all.map((l) => (l.productId === productId ? { ...l, amount } : l)));
  }

  function add(e: ChangeEvent<HTMLSelectElement>) {
    const product = lists.products.find((p) => p.id === Number(e.target.value));
    if (!product) return;
    const amount = product.standardPrice === null ? '' : product.standardPrice.toFixed(2);
    setLines((all) => [...all, { productId: product.id, name: product.name, amount }]);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (date === '' || date > todayText()) {
      setError('Choose a date that is not in the future.');
      return;
    }
    if (lines.length === 0) {
      setError('Keep at least one product.');
      return;
    }
    const bad = lines.find((l) => !MONEY.test(l.amount.trim()) || Number(l.amount) <= 0);
    if (bad) {
      setError(`Enter an amount for ${bad.name}: above 0, with at most two decimals.`);
      return;
    }

    setSaving(true);
    setError(null);
    try {
      await transactions.update(detail.id, {
        version: detail.version,
        transactionDate: date,
        lines: lines.map((l) => ({ productId: l.productId, amount: Number(l.amount) })),
        amountToClient: detail.amountToClient,
        thirdPartySettlement: detail.thirdPartySettlement,
        paymentTermId: detail.paymentTermId,
        employerId: detail.employerId,
        structureId: detail.structureId,
        primaryStructureBudgetList: detail.primaryStructureBudgetList,
        primaryStructureLoanAmount: detail.primaryStructureLoanAmount,
        secondaryStructureBudgetList: detail.secondaryStructureBudgetList,
        secondaryStructureLoanAmount: detail.secondaryStructureLoanAmount,
      });
      onSaved();
    } catch (err) {
      setError(detailOf(err) ?? 'Could not save the sale.');
      setSaving(false);
    }
  }

  if (!detail.editable) {
    return (
      <div className="se">
        <p className="muted">
          This sale can no longer be changed: its week has been imported. Fix it in the spreadsheet.
        </p>
        <button type="button" className="btn" onClick={onCancel}>
          Close
        </button>
      </div>
    );
  }

  return (
    <form className="se" onSubmit={(e) => void submit(e)} noValidate>
      <div className="se-field">
        <label htmlFor={`se-date-${detail.id}`}>Date</label>
        <input
          id={`se-date-${detail.id}`}
          type="date"
          max={todayText()}
          value={date}
          onChange={(e) => setDate(e.target.value)}
        />
      </div>

      <ul className="se-lines">
        {lines.map((l) => (
          <li key={l.productId}>
            <label htmlFor={`se-amount-${detail.id}-${l.productId}`}>{l.name}</label>
            <input
              id={`se-amount-${detail.id}-${l.productId}`}
              inputMode="decimal"
              value={l.amount}
              onChange={(e) => setAmount(l.productId, e.target.value)}
            />
            <button
              type="button"
              className="btn"
              onClick={() => setLines((all) => all.filter((x) => x.productId !== l.productId))}
              aria-label={`Remove ${l.name}`}
            >
              Remove
            </button>
          </li>
        ))}
      </ul>

      {addable.length > 0 && (
        <div className="se-field">
          <label htmlFor={`se-add-${detail.id}`}>Add a product</label>
          <select id={`se-add-${detail.id}`} value="" onChange={add}>
            <option value="">Choose a product...</option>
            {addable.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
      )}

      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}

      <div className="se-actions">
        <button type="submit" className="btn primary" disabled={saving}>
          {saving ? 'Saving...' : 'Save changes'}
        </button>
        <button type="button" className="btn" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
      </div>
    </form>
  );
}

export default function SaleEditor({
  saleId,
  onSaved,
  onCancel,
}: {
  saleId: number;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [loaded, setLoaded] = useState<{ detail: SaleDetail; lists: CaptureLists } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    Promise.all([transactions.get(saleId), transactions.lists()])
      .then(([detail, lists]) => {
        if (live) setLoaded({ detail, lists });
      })
      .catch((err: unknown) => {
        if (live) setError(detailOf(err) ?? 'Could not load the sale.');
      });
    return () => {
      live = false;
    };
  }, [saleId]);

  if (error)
    return (
      <div className="se">
        <p className="alert error" role="alert">
          {error}
        </p>
        <button type="button" className="btn" onClick={onCancel}>
          Close
        </button>
      </div>
    );
  if (!loaded) return <p className="muted">Loading...</p>;
  return (
    <EditForm detail={loaded.detail} lists={loaded.lists} onSaved={onSaved} onCancel={onCancel} />
  );
}
